package com.prism.claims;

import com.prism.common.error.ApiException;
import com.prism.corpus.CorpusAccessService;
import com.prism.graph.GraphService;
import com.prism.graph.GraphService.Scope;
import com.prism.user.Role;
import com.prism.user.User;
import com.prism.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Set;

/**
 * Claim verification (Redline) and human adjudication.
 *
 * <p>Verification is asynchronous because it calls a model. Adjudication is
 * synchronous because it is a pure database decision that must complete before
 * the client can rely on it.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Verification", description = "Claim verification, evidence, verdicts, adjudication")
public class VerificationController {

    private final VerificationService verification;
    private final VerdictRepository verdicts;
    private final VerdictPassageRepository passages;
    private final VerdictHistoryRepository historyRows;
    private final ClaimRepository claims;
    private final CorpusAccessService access;
    private final UserService users;
    private final GraphService graph;

    public VerificationController(VerificationService verification, VerdictRepository verdicts,
                                 VerdictPassageRepository passages, VerdictHistoryRepository history,
                                 ClaimRepository claims, CorpusAccessService access,
                                 UserService users, GraphService graph,
                                 @Qualifier(com.prism.config.AsyncAndCacheConfig.LLM_EXECUTOR)
                                 org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor llmExecutor) {
        this.verification = verification;
        this.verdicts = verdicts;
        this.passages = passages;
        this.historyRows = history;
        this.claims = claims;
        this.access = access;
        this.users = users;
        this.graph = graph;
    }

    public record VerifyRequest(@NotNull Long claimId) {
    }

    public record AdjudicateRequest(
            @NotNull VerdictType verdict,
            @Size(max = 2000) String note) {
    }

    public record PassageResponse(Long chunkId, Long documentId, String documentTitle,
                                  String chunkText, int retrievalRank, Double retrievalScore) {
        static PassageResponse from(VerdictPassage p) {
            return new PassageResponse(p.getChunkId(), p.getDocumentId(), p.getDocumentTitle(),
                    p.getChunkText(), p.getRetrievalRank(),
                    p.getRetrievalScore() == null ? null : p.getRetrievalScore().doubleValue());
        }
    }

    /**
     * The verdict projection.
     *
     * <p>Machine score, rule penalty, fused score, and evidence status are all
     * surfaced separately. They are never collapsed into a single "confidence",
     * and {@code fusedScore} is documented as an uncalibrated ranking score.
     */
    public record VerdictResponse(
            Long id, Long claimId, Long corpusId, String claimText, String subject,
            VerdictType verdictType, VerdictType machineVerdictType, VerdictType humanVerdictType,
            AdjudicationState adjudicationState, String adjudicator, Instant adjudicatedAt,
            String adjudicationNote, boolean overridden,
            Double llmScore, Double rulePenalty, Double fusedScore, EvidenceStatus evidenceStatus,
            String ruleVersion, String verdictReason, String llmReasoning,
            String model, String promptVersion, String retrievalQuery, Long traceRunId,
            Instant createdAt, List<PassageResponse> evidence) {
    }

    private VerdictResponse toResponse(Verdict v, boolean withEvidence) {
        List<PassageResponse> evidence = withEvidence
                ? passages.findByVerdictIdOrderByRetrievalRankAsc(v.getId()).stream()
                .map(PassageResponse::from)
                .toList()
                : List.of();
        return new VerdictResponse(v.getId(), v.getClaim().getId(), v.getCorpus().getId(),
                v.getClaim().getClaimText(), v.getClaim().getSubject(),
                v.getVerdictType(), v.getMachineVerdictType(), v.getHumanVerdictType(),
                v.getAdjudicationState(),
                v.getAdjudicator() == null ? null : v.getAdjudicator().getUsername(),
                v.getAdjudicatedAt(), v.getAdjudicationNote(), v.wasOverridden(),
                v.getLlmScore() == null ? null : v.getLlmScore().doubleValue(),
                v.getRulePenalty() == null ? null : v.getRulePenalty().doubleValue(),
                v.getFusedScore() == null ? null : v.getFusedScore().doubleValue(),
                v.getEvidenceStatus(), v.getRuleVersion(), v.getVerdictReason(), v.getLlmReasoning(),
                v.getModel(), v.getPromptVersion(), v.getRetrievalQuery(), v.getTraceRunId(),
                v.getCreatedAt(), evidence);
    }

    @PostMapping("/claims/verify")
    @Operation(summary = "Verify one claim against retrieved evidence")
    public ResponseEntity<VerificationService.VerificationOutcome> verify(
            @Valid @RequestBody VerifyRequest request) {
        Long userId = access.requireCurrentUserId();
        VerificationService.VerificationOutcome outcome = verification.verify(userId, request.claimId());
        if (!outcome.succeeded()) {
            throw new ApiException(com.prism.common.error.ErrorCode.LLM_UNAVAILABLE,
                    "verification did not complete: " + outcome.error());
        }
        return ResponseEntity.ok(outcome);
    }

    @PostMapping("/claims/verify-all")
    @Operation(summary = "Verify every approved-but-unverified claim in a corpus. Serial by design: "
            + "each verification performs its own corpus-scoped retrieval and model call.")
    public Map<String, Object> verifyAll(@RequestParam Long corpusId,
                                         @RequestParam(defaultValue = "10") int limit) {
        Long userId = access.requireCurrentUserId();
        access.requireAccessible(corpusId, userId);
        List<Claim> pending = claims.findVerifiableInCorpus(corpusId,
                PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));

        int succeeded = 0;
        int failed = 0;
        List<Long> verdictIds = new java.util.ArrayList<>();
        for (Claim claim : pending) {
            try {
                var outcome = verification.verify(userId, claim.getId());
                if (outcome.succeeded()) {
                    succeeded++;
                    verdictIds.add(outcome.verdictId());
                } else {
                    failed++;
                }
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempted", pending.size());
        body.put("succeeded", succeeded);
        body.put("failed", failed);
        body.put("verdictIds", verdictIds);
        return body;
    }

    @GetMapping("/verdicts")
    @Operation(summary = "List verdicts in a corpus")
    public Map<String, Object> listVerdicts(@RequestParam Long corpusId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        access.requireAccessible(corpusId, userId);
        var result = verdicts.findByCorpusIdOrderByCreatedAtDesc(corpusId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return Map.of("content", result.getContent().stream().map(v -> toResponse(v, false)).toList(),
                "total", result.getTotalElements(), "page", result.getNumber(), "size", result.getSize());
    }

    @GetMapping("/verdicts/{id}")
    @Operation(summary = "Fetch one verdict with its evidence passages")
    @Transactional(readOnly = true)
    public VerdictResponse getVerdict(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Verdict verdict = verdicts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Verdict", id));
        access.requireAccessible(verdict.getCorpus().getId(), userId);
        return toResponse(verdict, true);
    }

    /**
     * Human adjudication.
     *
     * <p>The machine verdict is preserved; this sets the human verdict
     * alongside it. Both remain readable afterwards.
     */
    @PostMapping("/verdicts/{id}/adjudicate")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Record a human decision. The machine verdict is preserved, never overwritten.")
    @Transactional
    public VerdictResponse adjudicate(@PathVariable Long id, @Valid @RequestBody AdjudicateRequest request) {
        Long userId = access.requireCurrentUserId();
        Verdict verdict = verdicts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Verdict", id));
        access.requireAccessible(verdict.getCorpus().getId(), userId);
        access.requireVerifier(userId);

        if (verdict.getAdjudicationState() == AdjudicationState.HUMAN_DECISION) {
            throw ApiException.stateConflict("verdict " + id + " already carries a human decision");
        }
        User human = users.getById(userId);
        verdict.adjudicate(human, request.verdict(), request.note(), Instant.now());
        verdict.getClaim().markAdjudicated(Instant.now());
        verdicts.save(verdict);
        claims.save(verdict.getClaim());
        graph.invalidateAll();
        return toResponse(verdict, true);
    }

    @GetMapping("/verdicts/{id}/history")
    @Operation(summary = "Superseded machine verdicts for the same claim")
    public List<Map<String, Object>> history(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Verdict verdict = verdicts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Verdict", id));
        access.requireAccessible(verdict.getCorpus().getId(), userId);
        return historyRows.findByClaimIdOrderBySupersededAtDesc(verdict.getClaim().getId()).stream()
                .map(h -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", h.getId());
                    row.put("verdictType", h.getVerdictType());
                    row.put("evidenceStatus", h.getEvidenceStatus());
                    row.put("llmScore", h.getLlmScore() == null ? null : h.getLlmScore().doubleValue());
                    row.put("fusedScore", h.getFusedScore() == null ? null : h.getFusedScore().doubleValue());
                    row.put("model", h.getModel());
                    row.put("supersededAt", h.getSupersededAt());
                    row.put("supersededReason", h.getSupersededReason());
                    return row;
                })
                .toList();
    }
}
