package com.prism.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory directed graph over approved entities and approved triples.
 *
 * <p>Deliberately not a general graph library: it carries exactly the semantics
 * PRISM needs and is trivially testable.
 *
 * <p><b>Edge semantics.</b> A triple is stored once per distinct
 * (subject, predicate, object) fact. If two documents assert the same
 * relation, extraction collapses them into one triple with a higher
 * evidence count rather than creating parallel edges. Without that, duplicated
 * extraction would inflate out-degree and distort every centrality metric
 * computed here.
 *
 * <p>Self-loops are dropped: they carry no structural information and would
 * otherwise distort the dangling-node and degree accounting.
 */
public final class KnowledgeGraph {

    private final Map<Long, String> nodeNames = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> outEdges = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> inEdges = new LinkedHashMap<>();
    private final Map<Long, Set<String>> outPredicates = new LinkedHashMap<>();
    private final Map<Long, Integer> tripleCounts = new LinkedHashMap<>();
    private final Set<String> edgeKeys = new LinkedHashSet<>();

    /**
     * Adds a node if absent. Idempotent, so node order in the graph does not
     * depend on how many times a node is referenced.
     */
    public void addNode(long nodeId, String name) {
        nodeNames.putIfAbsent(nodeId, name);
        outEdges.computeIfAbsent(nodeId, k -> new LinkedHashSet<>());
        inEdges.computeIfAbsent(nodeId, k -> new LinkedHashSet<>());
        outPredicates.computeIfAbsent(nodeId, k -> new LinkedHashSet<>());
        tripleCounts.putIfAbsent(nodeId, 0);
    }

    /**
     * Adds a directed edge, deduplicated by
     * {@code (subject, predicate, object)} rather than by endpoints alone.
     *
     * <p>Deduplicating by endpoints alone would merge "X controls Y" and
     * "X funds Y" into one edge and silently drop a real distinction.
     */
    public void addEdge(long from, long to, String predicate) {
        addNode(from, nodeNames.getOrDefault(from, "node-" + from));
        addNode(to, nodeNames.getOrDefault(to, "node-" + to));
        if (from == to) {
            // Self-loops carry no structural signal; ignore rather than distort.
            return;
        }
        String key = from + "|" + predicate + "|" + to;
        if (edgeKeys.add(key)) {
            outEdges.get(from).add(to);
            inEdges.get(to).add(from);
            tripleCounts.merge(from, 1, Integer::sum);
        }
        outPredicates.get(from).add(predicate);
    }

    public boolean hasNode(long nodeId) {
        return nodeNames.containsKey(nodeId);
    }

    public Set<Long> nodes() {
        return Collections.unmodifiableSet(nodeNames.keySet());
    }

    /** Nodes in a stable ascending order, so iteration is reproducible. */
    public List<Long> sortedNodes() {
        List<Long> ids = new ArrayList<>(nodeNames.keySet());
        Collections.sort(ids);
        return ids;
    }

    public String nameOf(long nodeId) {
        return nodeNames.getOrDefault(nodeId, "node-" + nodeId);
    }

    public Map<Long, String> names() {
        return Collections.unmodifiableMap(nodeNames);
    }

    public Set<Long> successorsOf(long nodeId) {
        return Collections.unmodifiableSet(outEdges.getOrDefault(nodeId, Set.of()));
    }

    public Set<Long> predecessorsOf(long nodeId) {
        return Collections.unmodifiableSet(inEdges.getOrDefault(nodeId, Set.of()));
    }

    public Set<String> predicatesFrom(long nodeId) {
        return Collections.unmodifiableSet(outPredicates.getOrDefault(nodeId, Set.of()));
    }

    public int outDegree(long nodeId) {
        return outEdges.getOrDefault(nodeId, Set.of()).size();
    }

    public int inDegree(long nodeId) {
        return inEdges.getOrDefault(nodeId, Set.of()).size();
    }

    public int edgeCount() {
        return edgeKeys.size();
    }

    public int nodeCount() {
        return nodeNames.size();
    }

    public double density() {
        int n = nodeCount();
        if (n < 2) {
            return 0.0;
        }
        int possible = n * (n - 1);
        return (double) edgeCount() / possible;
    }
}
