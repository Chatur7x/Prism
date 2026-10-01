package com.prism.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic label-propagation community detection.
 *
 * <p>Label propagation is order-sensitive by nature: which node updates first
 * changes the outcome. Randomizing the traversal — the usual approach — would
 * make the same graph produce different communities on every run, which is
 * unacceptable for an auditable system where the Skeptic is shown community
 * membership as machine evidence.
 *
 * <p>Determinism is achieved by:
 * <ol>
 *   <li>visiting nodes in ascending id order,</li>
 *   <li>iterating a node's neighbours in ascending id order,</li>
 *   <li>breaking label-count ties by choosing the smallest label id rather
 *       than by hash-map iteration order,</li>
 *   <li>running a fixed number of sweeps,</li>
 *   <li>assigning a final community id by sorting communities on their minimum
 *       member id.</li>
 * </ol>
 *
 * <p>This is a heuristic, not a guarantee of modularity quality. It is
 * guaranteed to be reproducible, which is the property that actually matters
 * here.
 */
public final class CommunityDetection {

    private static final int DEFAULT_SWEEPS = 8;

    private final int sweeps;

    public CommunityDetection() {
        this(DEFAULT_SWEEPS);
    }

    public CommunityDetection(int sweeps) {
        if (sweeps < 1) {
            throw new IllegalArgumentException("sweeps must be at least 1");
        }
        this.sweeps = sweeps;
    }

    /**
     * @return community id to sorted member ids. Community ids are 0-based and
     *         assigned in ascending order of each community's smallest member.
     */
    public Map<Integer, List<Long>> detect(KnowledgeGraph graph) {
        List<Long> nodes = graph.sortedNodes();
        Map<Integer, List<Long>> result = new LinkedHashMap<>();
        if (nodes.isEmpty()) {
            return result;
        }

        // Every node starts in its own community, labelled by its own id.
        Map<Long, Long> label = new HashMap<>();
        for (Long id : nodes) {
            label.put(id, id);
        }

        for (int sweep = 0; sweep < sweeps; sweep++) {
            boolean changed = false;
            for (Long node : nodes) {
                Map<Long, Integer> counts = new HashMap<>();
                for (Long neighbour : neighbours(graph, node)) {
                    long neighbourLabel = label.get(neighbour);
                    counts.merge(neighbourLabel, 1, Integer::sum);
                }
                if (counts.isEmpty()) {
                    continue;
                }
                long bestLabel = argmaxLabel(counts);
                if (label.get(node) != bestLabel) {
                    label.put(node, bestLabel);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        // Group by final label, then renumber by ascending smallest member id.
        Map<Long, List<Long>> groups = new LinkedHashMap<>();
        for (Long id : nodes) {
            groups.computeIfAbsent(label.get(id), k -> new ArrayList<>()).add(id);
        }
        List<List<Long>> ordered = new ArrayList<>(groups.values());
        for (List<Long> members : ordered) {
            Collections.sort(members);
        }
        ordered.sort((a, b) -> Long.compare(a.get(0), b.get(0)));

        int communityId = 0;
        for (List<Long> members : ordered) {
            result.put(communityId++, List.copyOf(members));
        }
        return result;
    }

    /**
     * Deterministic argmax over label counts: highest count wins; on a tie the
     * smallest label id wins.
     *
     * <p>The tie-break is explicit rather than delegated to {@code HashMap}
     * iteration order, which is stable for a given JVM but is not part of any
     * contract and would make the result depend on hash internals.
     */
    static long argmaxLabel(Map<Long, Integer> counts) {
        long bestLabel = Long.MAX_VALUE;
        int bestCount = Integer.MIN_VALUE;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            long label = entry.getKey();
            if (count > bestCount || (count == bestCount && label < bestLabel)) {
                bestCount = count;
                bestLabel = label;
            }
        }
        return bestCount == Integer.MIN_VALUE ? -1L : bestLabel;
    }

    /**
     * Weakly-connected neighbours, returned in ascending id order so the update
     * order never depends on set iteration.
     */
    private Set<Long> neighbours(KnowledgeGraph graph, Long node) {
        Set<Long> all = new HashSet<>(graph.successorsOf(node));
        all.addAll(graph.predecessorsOf(node));
        List<Long> ordered = new ArrayList<>(all);
        Collections.sort(ordered);
        return new java.util.LinkedHashSet<>(ordered);
    }

    /** Maps each node to its community id. */
    public Map<Long, Integer> assign(KnowledgeGraph graph) {
        Map<Long, Integer> assignment = new HashMap<>();
        detect(graph).forEach((communityId, members) -> members.forEach(m -> assignment.put(m, communityId)));
        return assignment;
    }
}
