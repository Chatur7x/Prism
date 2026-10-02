package com.prism.graph;

import com.prism.corpus.CorpusAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph", description = "Trusted knowledge graph, PageRank, and communities")
public class GraphController {

    private final GraphService graph;
    private final CorpusAccessService access;

    public GraphController(GraphService graph, CorpusAccessService access) {
        this.graph = graph;
        this.access = access;
    }

    private GraphService.Scope scopeOf(String scope) {
        if (scope == null) {
            return GraphService.Scope.ALL_APPROVED;
        }
        try {
            return GraphService.Scope.valueOf(scope.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw com.prism.common.error.ApiException.validation(
                    "scope must be ALL_APPROVED or VERIFIED_ONLY");
        }
    }

    @GetMapping("/{corpusId}")
    @Operation(summary = "Nodes and edges of the trusted graph",
            description = "Only APPROVED triples become edges. PENDING and REJECTED proposals are "
                    + "structurally excluded, not filtered by convention.")
    public GraphService.GraphView graph(@PathVariable Long corpusId,
                                       @RequestParam(defaultValue = "ALL_APPROVED") String scope) {
        Long userId = access.requireCurrentUserId();
        // The service authorises and resolves the verified-id set itself. This
        // controller used to resolve the verified ids and pass them in, which ran
        // that corpus query BEFORE the access check -- so it executed for
        // corpora the caller could not reach and its result became part of the
        // cache key.
        return graph.graphView(userId, corpusId, scopeOf(scope));
    }

    @GetMapping("/{corpusId}/pagerank")
    @Operation(summary = "PageRank over the trusted graph",
            description = "A structural centrality metric computed by deterministic Java. It is NOT a "
                    + "truth score, a confidence value, or a measure of reliability.")
    public List<Map<String, Object>> pagerank(@PathVariable Long corpusId,
                                              @RequestParam(defaultValue = "ALL_APPROVED") String scope,
                                              @RequestParam(defaultValue = "50") int limit) {
        Long userId = access.requireCurrentUserId();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        int position = 1;
        for (var entry : graph.pagerank(userId, corpusId, scopeOf(scope))) {
            if (position > Math.max(1, limit)) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", position++);
            row.put("entityId", entry.getKey());
            row.put("pagerank", entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    @GetMapping("/{corpusId}/communities")
    @Operation(summary = "Deterministic label-propagation communities over the trusted graph",
            description = "Ties are broken by smallest entity id and nodes are visited in ascending id "
                    + "order, so the same graph always produces the same communities.")
    public List<Map<String, Object>> communities(@PathVariable Long corpusId,
                                                 @RequestParam(defaultValue = "ALL_APPROVED") String scope) {
        Long userId = access.requireCurrentUserId();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        graph.communitiesFor(userId, corpusId, scopeOf(scope))
                .forEach((communityId, members) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("communityId", communityId);
                    row.put("size", members.size());
                    row.put("entityIds", members);
                    rows.add(row);
                });
        return rows;
    }
}
