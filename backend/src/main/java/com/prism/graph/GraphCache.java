package com.prism.graph;

import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cached derivation of the trusted graph.
 *
 * <p><b>This bean deliberately has no access to {@code CorpusAccessService}.</b>
 * That is the whole design, and it exists because of a cross-corpus data leak
 * that a security probe reproduced:
 *
 * <pre>
 *   owner    GET /api/graph/1   -> cache MISS -> buildGraph runs -> access
 *                                                    check passes -> cached
 *   attacker GET /api/graph/1   -> cache HIT  -> method body NEVER RUNS
 *                                       -> access check never runs
 *                                       -> attacker receives the whole graph
 * </pre>
 *
 * {@code @Cacheable} answers from the cache without invoking the method, so any
 * access check written <em>inside</em> the cached method is skipped on a hit.
 * The bug was not that the check was missing; it was that the check was in the
 * one place a cache hit could skip.
 *
 * <p>The fix is structural rather than positional. Authorization lives in
 * {@link GraphService}, which always runs. This class cannot authorize even if it
 * wanted to -- it holds no access service -- so a caller's only route to cached
 * graph data is through a method that has already checked. Putting the check in
 * the caller instead would also have worked, but it relies on every future
 * caller remembering; making the cached class incapable of the mistake does not.
 *
 * <p>Cache keys therefore legitimately omit the user id: the cached value is
 * corpus-scoped data with no per-user component, and authorization is no longer
 * conditional on the cache's state.
 */
@Service
public class GraphCache {

    private final TripleRepository triples;
    private final PageRank pageRank;
    private final CommunityDetection communities;

    public GraphCache(TripleRepository triples, com.prism.config.PrismTuningProperties tuning) {
        this.triples = triples;
        // Constructed rather than injected, matching GraphService. Both are pure
        // functions of the graph plus the tuning constants, so there is nothing
        // to share and making them beans would only add a bean to track.
        this.pageRank = new PageRank(tuning.graph().pagerankDamping(),
                tuning.graph().pagerankIterations());
        this.communities = new CommunityDetection();
    }

    /**
     * Nodes, edges, and structural metrics.
     *
     * <p>Call only from a path that has already authorised the corpus.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "graphSnapshot", key = "#corpusId + ':' + #scope.name() + ':' + #verifiedIds.hashCode()")
    public GraphService.GraphView graphView(Long corpusId, GraphService.Scope scope,
                                            Set<Long> verifiedIds) {
        KnowledgeGraph graph = buildGraph(corpusId, scope, verifiedIds);
        Map<Long, Double> ranks = pageRank.compute(graph);
        Map<Long, Integer> assignment = communities.assign(graph);
        Set<Long> verified = verifiedIds == null ? Set.of() : verifiedIds;

        List<GraphService.NodeView> nodes = graph.sortedNodes().stream()
                .map(id -> new GraphService.NodeView(id, graph.nameOf(id), graph.inDegree(id),
                        graph.outDegree(id), ranks.getOrDefault(id, 0.0), assignment.get(id)))
                .toList();

        List<GraphService.EdgeView> edges = new ArrayList<>();
        for (Triple t : triples.findApprovedInCorpus(corpusId)) {
            if (scope == GraphService.Scope.VERIFIED_ONLY && !verified.contains(t.getId())) {
                continue;
            }
            edges.add(new GraphService.EdgeView(t.getSubjectEntity().getId(), t.getObjectEntity().getId(),
                    t.getPredicate(), t.getSubject() + " " + t.getPredicate() + " " + t.getObject()));
        }

        int maxIn = nodes.stream().mapToInt(GraphService.NodeView::inDegree).max().orElse(0);
        int maxOut = nodes.stream().mapToInt(GraphService.NodeView::outDegree).max().orElse(0);
        GraphService.GraphStats stats = new GraphService.GraphStats(graph.nodeCount(), graph.edgeCount(),
                graph.density(), assignment.size(), maxIn, maxOut);
        return new GraphService.GraphView(nodes, edges, stats, scope);
    }

    /** Entities ordered by descending PageRank. Call only after authorization. */
    @Transactional(readOnly = true)
    @Cacheable(value = "pagerank", key = "#corpusId + ':' + #scope.name() + ':' + #verifiedIds.hashCode()")
    public List<Map.Entry<Long, Double>> pagerank(Long corpusId, GraphService.Scope scope,
                                                   Set<Long> verifiedIds) {
        return pageRank.ranked(buildGraph(corpusId, scope, verifiedIds));
    }

    /** Community assignment. Call only after authorization. */
    @Transactional(readOnly = true)
    @Cacheable(value = "communities", key = "#corpusId + ':' + #scope.name() + ':' + #verifiedIds.hashCode()")
    public Map<Integer, List<Long>> communitiesFor(Long corpusId, GraphService.Scope scope,
                                                    Set<Long> verifiedIds) {
        return communities.detect(buildGraph(corpusId, scope, verifiedIds));
    }

    /**
     * Builds the graph from approved triples only.
     *
     * <p>No access check, because this class cannot perform one. See the class
     * comment for why that is the safer arrangement rather than the sloppier one.
     */
    @Transactional(readOnly = true)
    protected KnowledgeGraph buildGraph(Long corpusId, GraphService.Scope scope, Set<Long> verifiedIds) {
        KnowledgeGraph graph = new KnowledgeGraph();
        Set<Long> verified = verifiedIds == null ? Set.of() : verifiedIds;

        for (Triple t : triples.findApprovedInCorpus(corpusId)) {
            if (scope == GraphService.Scope.VERIFIED_ONLY && !verified.contains(t.getId())) {
                continue;
            }
            graph.addNode(t.getSubjectEntity().getId(), t.getSubjectEntity().getDisplayName());
            graph.addEdge(t.getSubjectEntity().getId(), t.getObjectEntity().getId(), t.getPredicate());
        }
        return graph;
    }

    /**
     * Drops every cached graph artefact.
     *
     * <p>Wholesale rather than per-key, because keys embed a hash of the verified
     * id set and cannot be enumerated. These caches hold derived, recomputable
     * data with no authorization decision attached, so clearing them all is cheap
     * and always correct.
     */
    @CacheEvict(value = {"graphSnapshot", "pagerank", "communities"}, allEntries = true)
    public void invalidateAll() {
        // Eviction is the behaviour.
    }
}