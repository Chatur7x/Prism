package com.prism.graph;

import com.prism.claims.VerifiedGraphScope;
import com.prism.corpus.CorpusAccessService;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><b>Every read authorises before it touches anything.</b> This service is the
 * only route to {@link GraphCache}, and it authorises first — including
 * resolving the verified-id set, which is itself a corpus query. That ordering
 * is not incidental: the verified-id lookup used to run in the controller,
 * <em>before</em> the access check, so it executed for corpora the caller could
 * not reach and fed its result into the cache key.
 *
 * @see GraphCache for why the cached half holds no access service at all
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

    // ---- wire shapes --------------------------------------------------------

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

    // ---- collaborators ------------------------------------------------------

    private final TripleRepository triples;
    private final CorpusAccessService access;
    private final VerifiedGraphScope verifiedScope;
    private final GraphCache cache;
    private final com.prism.graph.PageRank pageRank;

    public GraphService(TripleRepository triples, CorpusAccessService access,
                        VerifiedGraphScope verifiedScope, GraphCache cache,
                        com.prism.config.PrismTuningProperties tuning) {
        this.triples = triples;
        this.access = access;
        this.verifiedScope = verifiedScope;
        this.cache = cache;
        this.pageRank = new com.prism.graph.PageRank(
                tuning.graph().pagerankDamping(), tuning.graph().pagerankIterations());
    }

    // ---- graph construction ------------------------------------------------

    /**
     * The in-memory graph for a corpus, authorised.
     *
     * <p>Uncached and used by callers that need the raw graph rather than a
     * view — PageRank for chat and Skeptic context, for instance. Authorises
     * here because this method is public and reachable directly.
     *
     * @param verifiedIds triple ids qualifying for {@link Scope#VERIFIED_ONLY};
     *                    ignored for {@link Scope#ALL_APPROVED}
     */
    @Transactional(readOnly = true)
    public KnowledgeGraph buildGraph(Long userId, Long corpusId, Scope scope, Set<Long> verifiedIds) {
        access.requireAccessible(corpusId, userId);
        return cache.buildGraph(corpusId, scope, verifiedIds);
    }

    // ---- views -------------------------------------------------------------

    /** Nodes, edges, and structural metrics for an authorised corpus. */
    public GraphView graphView(Long userId, Long corpusId, Scope scope) {
        access.requireAccessible(corpusId, userId);
        Set<Long> verifiedIds = resolveVerifiedIds(corpusId, scope);
        return cache.graphView(corpusId, scope, verifiedIds);
    }

    /** PageRank over an authorised corpus. */
    public List<Map.Entry<Long, Double>> pagerank(Long userId, Long corpusId, Scope scope) {
        access.requireAccessible(corpusId, userId);
        return cache.pagerank(corpusId, scope, resolveVerifiedIds(corpusId, scope));
    }

    /** Communities over an authorised corpus. */
    public Map<Integer, List<Long>> communitiesFor(Long userId, Long corpusId, Scope scope) {
        access.requireAccessible(corpusId, userId);
        return cache.communitiesFor(corpusId, scope, resolveVerifiedIds(corpusId, scope));
    }

    /**
     * The verified-id set for a corpus, or empty when the scope does not use it.
     *
     * <p>Called only after authorization, and skipped entirely for
     * {@link Scope#ALL_APPROVED} because the ids cannot change that view. Skipping
     * it is not just an optimisation: the lookup is a pair of queries over
     * verdicts and approved triples, and doing it for a scope that ignores the
     * answer was pure cost on every read.
     */
    private Set<Long> resolveVerifiedIds(Long corpusId, Scope scope) {
        if (scope != Scope.VERIFIED_ONLY) {
            return Set.of();
        }
        return verifiedScope.verifiedTripleIds(corpusId);
    }

    /** Entity ids ordered by descending PageRank, for chat and Skeptic context. */
    @Transactional(readOnly = true)
    public List<Map.Entry<Long, Double>> topEntities(Long userId, Long corpusId, int limit) {
        access.requireAccessible(corpusId, userId);
        return pageRank.ranked(cache.buildGraph(corpusId, Scope.ALL_APPROVED, Set.of())).stream()
                .limit(Math.max(1, limit))
                .sorted(Comparator.comparingDouble(Map.Entry<Long, Double>::getValue).reversed())
                .toList();
    }

    /**
     * Drops cached graph artefacts after trusted knowledge changes.
     *
     * <p>Delegates to {@link GraphCache#invalidateAll()}, which owns the cache
     * annotations.
     */
    @CacheEvict(value = {"graphSnapshot", "pagerank", "communities"}, allEntries = true)
    public void invalidateAll() {
        cache.invalidateAll();
    }
}