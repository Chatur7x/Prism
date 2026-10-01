package com.prism.knowledge;

import com.prism.claims.Claim;
import com.prism.claims.ClaimPolarity;
import com.prism.claims.ClaimStatus;
import com.prism.corpus.CorpusAccessService;
import com.prism.graph.GraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval queue and the knowledge read model.
 *
 * <p>Approval endpoints require the VERIFIER or ADMIN role. Read endpoints
 * require corpus access, which is checked per request against the specific
 * resource — a valid token alone never grants access to a given triple.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Knowledge", description = "Approval queue, triples, entities, and claims")
public class KnowledgeController {

    private final ApprovalService approvals;
    private final TripleRepository triples;
    private final EntityRepository entities;
    private final EntityResolutionService entityResolution;
    private final com.prism.claims.ClaimRepository claims;
    private final CorpusAccessService access;
    private final GraphService graph;

    public KnowledgeController(ApprovalService approvals, TripleRepository triples,
                               EntityRepository entities, EntityResolutionService entityResolution,
                               com.prism.claims.ClaimRepository claims,
                               CorpusAccessService access, GraphService graph) {
        this.approvals = approvals;
        this.triples = triples;
        this.entities = entities;
        this.entityResolution = entityResolution;
        this.claims = claims;
        this.access = access;
        this.graph = graph;
    }

    // ---- approval queue ----------------------------------------------------

    public record DecisionRequest(@Size(max = 2000) String note) {
    }

    public record TripleResponse(Long id, Long corpusId, String subject, String predicate, String object,
                                 String sourceSentence, Long sourceChunkId, String sourceDocumentTitle,
                                 ProposalStatus status, String decidedBy, Instant decidedAt,
                                 String decisionNote, int evidenceChunkCount, Instant createdAt) {

        static TripleResponse from(Triple t) {
            return new TripleResponse(t.getId(), t.getCorpus().getId(), t.getSubject(), t.getPredicate(),
                    t.getObject(), t.getSourceSentence(), t.getSourceChunk().getId(),
                    t.getSourceChunk().getDocument().getTitle(), t.getStatus(),
                    t.getDecidedBy() == null ? null : t.getDecidedBy().getUsername(),
                    t.getDecidedAt(), t.getDecisionNote(), t.getEvidenceChunkCount(), t.getCreatedAt());
        }
    }

    public record ClaimResponse(Long id, Long corpusId, String subject, String claimText,
                                ClaimPolarity polarity, String predicate, String objectText,
                                String sourceSentence, Long sourceChunkId, String sourceDocumentTitle,
                                ClaimStatus status, String decidedBy, Instant decidedAt,
                                Instant createdAt) {

        static ClaimResponse from(Claim c) {
            return new ClaimResponse(c.getId(), c.getCorpus().getId(), c.getSubject(), c.getClaimText(),
                    c.getPolarity(), c.getPredicate(), c.getObjectText(), c.getSourceSentence(),
                    c.getSourceChunk().getId(), c.getSourceChunk().getDocument().getTitle(),
                    c.getStatus(), c.getDecidedBy() == null ? null : c.getDecidedBy().getUsername(),
                    c.getDecidedAt(), c.getCreatedAt());
        }
    }

    @GetMapping("/approval-queue")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Pending proposals awaiting a human decision")
    public Map<String, Object> approvalQueue(@RequestParam Long corpusId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        Long userId = access.requireCurrentUserId();
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var triplePage = approvals.pendingTripleQueue(userId, corpusId, pageable);
        var claimPage = approvals.pendingClaimQueue(userId, corpusId, pageable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("triples", triplePage.getContent().stream().map(TripleResponse::from).toList());
        body.put("claims", claimPage.getContent().stream().map(ClaimResponse::from).toList());
        body.put("pendingTripleCount", triples.countByCorpusIdAndStatus(corpusId, ProposalStatus.PENDING));
        body.put("pendingClaimCount", claims.countByCorpusIdAndStatus(corpusId, ClaimStatus.PROPOSED));
        body.put("page", page);
        body.put("size", size);
        return body;
    }

    @PostMapping("/triples/{id}/approve")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Approve a proposed triple. Requires VERIFIER; records who decided.")
    public TripleResponse approveTriple(@PathVariable Long id,
                                        @Valid @RequestBody(required = false) DecisionRequest request) {
        Long userId = access.requireCurrentUserId();
        approvals.approveTriple(userId, id, request == null ? null : request.note());
        return TripleResponse.from(triples.findById(id).orElseThrow());
    }

    @PostMapping("/triples/{id}/reject")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Reject a proposed triple. The record is retained permanently.")
    public TripleResponse rejectTriple(@PathVariable Long id,
                                       @Valid @RequestBody DecisionRequest request) {
        Long userId = access.requireCurrentUserId();
        approvals.rejectTriple(userId, id, request == null ? null : request.note());
        return TripleResponse.from(triples.findById(id).orElseThrow());
    }

    @PostMapping("/claims/{id}/approve")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Approve a claim for verification")
    public ClaimResponse approveClaim(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        approvals.approveClaim(userId, id);
        return ClaimResponse.from(claims.findById(id).orElseThrow());
    }

    @PostMapping("/claims/{id}/reject")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Reject a claim outright")
    public ClaimResponse rejectClaim(@PathVariable Long id, @RequestBody(required = false) DecisionRequest request) {
        Long userId = access.requireCurrentUserId();
        approvals.rejectClaim(userId, id, request == null ? null : request.note());
        return ClaimResponse.from(claims.findById(id).orElseThrow());
    }

    // ---- triples -----------------------------------------------------------

    @GetMapping("/triples")
    @Operation(summary = "List triples in a corpus, filtered by status")
    public Map<String, Object> listTriples(@RequestParam Long corpusId,
                                           @RequestParam(required = false) ProposalStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        access.requireAccessible(corpusId, userId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var result = status == null
                ? triples.findByCorpusIdOrderByCreatedAtDesc(corpusId, pageable)
                : triples.findByCorpusIdAndStatusOrderByCreatedAtDesc(corpusId, status, pageable);
        return Map.of("content", result.getContent().stream().map(TripleResponse::from).toList(),
                "total", result.getTotalElements(), "page", result.getNumber(), "size", result.getSize());
    }

    @GetMapping("/triples/{id}")
    @Operation(summary = "Fetch one triple with its provenance")
    public TripleResponse getTriple(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Triple triple = triples.findById(id)
                .orElseThrow(() -> com.prism.common.error.ApiException.notFound("Triple", id));
        access.requireAccessible(triple.getCorpus().getId(), userId);
        return TripleResponse.from(triple);
    }

    // ---- entities ----------------------------------------------------------

    public record EntityResponse(Long id, Long corpusId, String displayName, String normalizedName,
                                 String type, ResolutionState resolutionState, int supportCount,
                                 Long firstSeenChunkId) {
        static EntityResponse from(Entity e) {
            return new EntityResponse(e.getId(), e.getCorpus().getId(), e.getDisplayName(),
                    e.getNormalizedName(), e.getType(), e.getResolutionState(), e.getSupportCount(),
                    e.getFirstSeenChunkId());
        }
    }

    @GetMapping("/entities")
    @Operation(summary = "List entities in a corpus")
    public List<EntityResponse> listEntities(@RequestParam Long corpusId,
                                            @RequestParam(required = false) String search) {
        Long userId = access.requireCurrentUserId();
        var corpus = access.requireAccessible(corpusId, userId);
        List<Entity> found = (search == null || search.isBlank())
                ? entities.findLiveInCorpus(corpusId)
                : entities.searchInCorpus(corpusId, "%" + search.toLowerCase() + "%",
                PageRequest.of(0, 200));
        return found.stream().map(EntityResponse::from).toList();
    }

    @GetMapping("/entities/{id}")
    @Operation(summary = "Fetch one entity with its aliases and approved relations")
    public Map<String, Object> getEntity(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Entity entity = entities.findById(id)
                .orElseThrow(() -> com.prism.common.error.ApiException.notFound("Entity", id));
        access.requireAccessible(entity.getCorpus().getId(), userId);

        var aliases = entityResolution.knownAliases(entity.getCorpus()).stream()
                .filter(a -> String.valueOf(a.entityId()).equals(String.valueOf(id)))
                .map(EntityResolver.KnownAlias::normalizedAlias)
                .toList();
        var outgoing = triples.findApprovedBySubject(entity.getCorpus().getId(), id).stream()
                .map(t -> Map.of("tripleId", t.getId(), "predicate", t.getPredicate(),
                        "object", t.getObject()))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity", EntityResponse.from(entity));
        body.put("aliases", aliases);
        body.put("approvedRelations", outgoing);
        return body;
    }

    // ---- claims ------------------------------------------------------------

    @GetMapping("/claims")
    @Operation(summary = "List claims in a corpus")
    public Map<String, Object> listClaims(@RequestParam Long corpusId,
                                          @RequestParam(required = false) ClaimStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        access.requireAccessible(corpusId, userId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var result = status == null
                ? claims.findByCorpusIdOrderByCreatedAtDesc(corpusId, pageable)
                : claims.findByCorpusIdAndStatusOrderByCreatedAtDesc(corpusId, status, pageable);
        return Map.of("content", result.getContent().stream().map(ClaimResponse::from).toList(),
                "total", result.getTotalElements(), "page", result.getNumber(), "size", result.getSize());
    }

    @GetMapping("/claims/{id}")
    @Operation(summary = "Fetch one claim with its source provenance")
    public ClaimResponse getClaim(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Claim claim = claims.findById(id)
                .orElseThrow(() -> com.prism.common.error.ApiException.notFound("Claim", id));
        access.requireAccessible(claim.getCorpus().getId(), userId);
        return ClaimResponse.from(claim);
    }
}
