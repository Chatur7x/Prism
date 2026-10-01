package com.prism.graph;

import com.prism.corpus.CorpusAccessService;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the trusted knowledge graph and computes structural metrics.
 *
 * <p><b>The single gate.</b> The graph is built from
 * {@code findApprovedInCorpus} and nothing else. PENDING and REJECTED triples
 * are structurally incapable of becoming edges, which makes "graph edges only
 * represent approved knowledge" an enforced property rather than a convention
 * each call site must remember.
 *
 * <p>All access is corpus-scoped and authorised before any query runs.
 */
@Service
public class GraphService {

    /** Which slice of trusted knowledge the graph represents. */
    public enum Scope {
        /** Every approved triple. The default trusted view. */
        ALL_APPROVED,
        /**
         * Only approved triples backed by a settled, unchallenged SUPPORTED
         * verdict.
         *
         * <p>Stated precisely because "verified only" is easily misread: a
         * triple qualifies when the claim built on it carries a SUPPORTED
         * verdict that no human has contested. INSUFFICIENT_EVIDENCE and
         * SOURCE_MISSING do not qualify, because neither establishes the fact.
         */
        VERIFIED_ONLY
    }

    private final TripleRepository triples;
    private final CorpusAccessService access;
    private final PageRank pageRank;
    private final CommunityDetection communities;

    public GraphService(TripleRepository triples, CorpusAccessService access,
                        com.prism.config.PrismTuningProperties tuning) {
        this.triples = triples;
        this.access = access;
        this.pageRank = new PageRank(
                tuning.graph().pagerankDamping(), tuning.graph().pagerankIterations());
        this.communities = new CommunityDetection();
    }

    // ---- views -------------------------------------------------------------

    public record NodeView(Long id, String name, int inDegree, int outDegree, double pagerank,
                           Integer community) {
    }

    public record EdgeView(Long from, Long to, String predicate, String label) {
    }

    public record GraphStats(int nodeCount, int edgeCount, double density, int communityCount,
                             int maxInDegree, int maxOutDegree) {
    }

    public record GraphView(List<NodeView> nodes, List<EdgeView> edges, GraphStats stats, Scope scope) {
    }

    // ---- graph construction ------------------------------------------------

    /**
     * Builds the in-memory graph for a corpus.
     *
     * @param verifiedIds triple ids that qualify for {@link Scope#VERIFIED_ONLY};
     *                    ignored for {@link Scope#ALL_APPROVED}
     */
    @Transactional(readOnly = true)
    public KnowledgeGraph buildGraph(Long userId, Long corpusId, Scope scope, Set<Long> verifiedIds) {
        access.requireAccessible(corpusId, userId);
        KnowledgeGraph graph = new KnowledgeGraph();
        Set<Long> verified = verifiedIds == null ? Set.of() : verifiedIds;

        for (Triple t : triples.findApprovedInCorpus(corpusId)) {
            if (scope == Scope.VERIFIED_ONLY && !verified.contains(t.getId())) {
                continue;
            }
            graph.addNode(t.getSubjectEntity().getId(), t.getSubjectEntity().getDisplayName());
            graph.addNode(t.getObjectEntity().getId(), t.getObjectEntity().getDisplayName());
            graph.addEdge(t.getSubjectEntity().getId(), t.getObjectEntity().getId(), t.getPredicate());
        }
        return graph;
    }

    // ---- cached metrics ----------------------------------------------------

    @Transactional(readOnly = true)
    @Cacheable(value = "graphSnapshot", key = "#corpusId + ':' + #scope.name() + ':' + #verifiedIds.hashCode()")
    public GraphView graphView(Long userId, Long corpusId, Scope scope, Set<Long> verifiedIds) {
        KnowledgeGraph graph = buildGraph(userId, corpusId, scope, verifiedIds);
        Map<Long, Double> ranks = pageRank.compute(graph);
        Map<Long, Integer> assignment = communities.assign(graph);
        Set<Long> verified = verifiedIds == null ? Set.of() : verifiedIds;

        List<NodeView> nodes = graph.sortedNodes().stream()
                .map(id -> new NodeView(id, graph.nameOf(id), graph.inDegree(id), graph.outDegree(id),
                        ranks.getOrDefault(id, 0.0), assignment.get(id)))
                .toList();

        List<EdgeView> edges = new ArrayList<>();
        for (Triple t : triples.findApprovedInCorpus(corpusId)) {
            if (scope == Scope.VERIFIED_ONLY && !verified.contains(t.getId())) {
                continue;
            }
            edges.add(new EdgeView(t.getSubjectEntity().getId(), t.getObjectEntity().getId(),
                    t.getPredicate(), t.getSubject() + " " + t.getPredicate() + " " + t.getObject()));
        }

        int maxIn = nodes.stream().mapToInt(NodeView::inDegree).max().orElse(0);
        int maxOut = nodes.stream().mapToInt(NodeView::outDegree).max().orElse(0);
        GraphStats stats = new GraphStats(graph.nodeCount(), graph.edgeCount(), graph.density(),
                assignment.size(), maxIn, maxOut);
        return new GraphView(nodes, edges, stats, scope);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "pagerank", key = "#corpusId + ':' + #scope.name() + ':' + #verifiedIds.hashCode()")
    public List<Map.Entry<Long, Double>> pagerank(Long userId, Long corpusId, Scope scope,
                                                   Set<Long> verifiedIds) {
        return pageRank.ranked(buildGraph(userId, corpusId, scope, verifiedIds));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "communities", key = "#corpusId + ':' + #scope.name() + ':' + #verifiedIds.hashCode()")
    public Map<Integer, List<Long>> communitiesFor(Long userId, Long corpusId, Scope scope,
                                                   Set<Long> verifiedIds) {
        return communities.detect(buildGraph(userId, corpusId, scope, verifiedIds));
    }

    /**
     * Clears cached graph artefacts after trusted knowledge changes.
     *
     * <p>Cache keys embed the corpus id, the scope, and a hash of the verified
     * id set, so they cannot be enumerated to build precise evictions. Clearing
     * the three graph caches wholesale is cheap and correct: they are derived,
     * recomputable data with no authorization decision attached.
     */
    @CacheEvict(value = {"graphSnapshot", "pagerank", "communities"}, allEntries = true)
    public void invalidateAll() {
        // Eviction is the behaviour.
    }

    /** Entity ids ordered by descending PageRank, for chat and Skeptic context. */
    @Transactional(readOnly = true)
    public List<Map.Entry<Long, Double>> topEntities(Long userId, Long corpusId, int limit) {
        access.requireAccessible(corpusId, userId);
        return pageRank.ranked(buildGraph(userId, corpusId, Scope.ALL_APPROVED, Set.of())).stream()
                .limit(Math.max(1, limit))
                .sorted(Comparator.comparingDouble(Map.Entry<Long, Double>::getValue).reversed())
                .toList();
    }
}
