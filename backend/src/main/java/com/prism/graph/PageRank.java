package com.prism.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic PageRank.
 *
 * <p><b>What this measures.</b> Structural centrality: how connected a node is
 * to the rest of the approved graph. It is a property of the graph's shape.
 * It is <b>not</b> a truth score, an importance score, or a confidence value.
 * A page with many inbound links from a peripheral cluster can score highly
 * while being substantively irrelevant. Presenting PageRank as "how reliable
 * is this entity" would be a category error.
 *
 * <p><b>Documented parameters.</b>
 * <ul>
 *   <li><b>damping</b> — probability of following an edge. Default 0.85, the
 *       value from the original PageRank paper. Lower values flatten the
 *       distribution; higher values concentrate it on the core.</li>
 *   <li><b>initialization</b> — uniform, {@code 1/n} for every node. Not random,
 *       not a privilege vector, so results do not depend on insertion order.</li>
 *   <li><b>iteration</b> — fixed count, not a convergence threshold. A fixed
 *       count makes the result exactly reproducible, which a tolerance-based
 *       early exit does not guarantee across platforms.</li>
 *   <li><b>dangling nodes</b> — nodes with no out-edges have their rank
 *       redistributed uniformly across all nodes. Ignoring them would leak
 *       probability mass and make the scores sum to less than 1.</li>
 *   <li><b>edge semantics</b> — an edge is a distinct approved triple. Multiple
 *       documents supporting the same relation are one edge (see
 *       {@link KnowledgeGraph#addEdge}), so corroboration does not inflate
 *       centrality.</li>
 * </ul>
 */
public final class PageRank {

    public static final double DEFAULT_DAMPING = 0.85;
    public static final int DEFAULT_ITERATIONS = 50;

    private final double damping;
    private final int iterations;

    public PageRank() {
        this(DEFAULT_DAMPING, DEFAULT_ITERATIONS);
    }

    public PageRank(double damping, int iterations) {
        if (damping < 0.0 || damping > 1.0) {
            throw new IllegalArgumentException("damping must be within [0, 1], got " + damping);
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1, got " + iterations);
        }
        this.damping = damping;
        this.iterations = iterations;
    }

    /**
     * @return node id to rank. Every node is present, and the values sum to
     *         approximately 1.0. Iteration order is by ascending node id.
     */
    public Map<Long, Double> compute(KnowledgeGraph graph) {
        List<Long> nodes = graph.sortedNodes();
        Map<Long, Double> scores = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            return scores;
        }
        int n = nodes.size();
        double initial = 1.0 / n;
        for (Long id : nodes) {
            scores.put(id, initial);
        }

        for (int iteration = 0; iteration < iterations; iteration++) {
            Map<Long, Double> next = new LinkedHashMap<>();

            // 1. Base term: the teleportation probability spread over all nodes.
            double base = (1.0 - damping) / n;

            // 2. Sum the contribution arriving at each node from its predecessors.
            Map<Long, Double> incoming = new LinkedHashMap<>();
            for (Long id : nodes) {
                incoming.put(id, 0.0);
            }
            for (Long id : nodes) {
                int outDegree = graph.outDegree(id);
                if (outDegree == 0) {
                    // Dangling node: its rank is redistributed uniformly, not lost.
                    double share = damping * scores.get(id) / n;
                    for (Long target : nodes) {
                        incoming.merge(target, share, Double::sum);
                    }
                    continue;
                }
                double share = damping * scores.get(id) / outDegree;
                for (Long target : graph.successorsOf(id)) {
                    incoming.merge(target, share, Double::sum);
                }
            }

            for (Long id : nodes) {
                next.put(id, base + incoming.get(id));
            }
            scores = next;
        }

        // Normalize so the scores sum to exactly 1 despite floating-point drift.
        double total = 0.0;
        for (double value : scores.values()) {
            total += value;
        }
        if (total > 0.0) {
            Map<Long, Double> normalized = new LinkedHashMap<>();
            for (Long id : nodes) {
                normalized.put(id, round9(scores.get(id) / total));
            }
            return normalized;
        }
        Map<Long, Double> uniform = new LinkedHashMap<>();
        for (Long id : nodes) {
            uniform.put(id, initial);
        }
        return uniform;
    }

    /**
     * Ranks nodes descending, breaking ties by ascending node id so the ordering
     * is fully deterministic.
     */
    public List<Map.Entry<Long, Double>> ranked(KnowledgeGraph graph) {
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(compute(graph).entrySet());
        entries.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : Long.compare(a.getKey(), b.getKey());
        });
        return Collections.unmodifiableList(entries);
    }

    static double round9(double value) {
        return Math.round(value * 1_000_000_000.0) / 1_000_000_000.0;
    }
}
