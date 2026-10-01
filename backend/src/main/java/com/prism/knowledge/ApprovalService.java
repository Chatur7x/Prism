package com.prism.knowledge;

import com.prism.common.error.ApiException;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import com.prism.graph.GraphService;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceStep;
import com.prism.user.User;
import com.prism.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The human gate into trusted knowledge.
 *
 * <p>This is the only class permitted to move a triple or claim out of
 * PENDING. It requires the VERIFIER or ADMIN role, records who decided and
 * when, and invalidates the graph cache so the trusted view is never stale.
 *
 * <p><b>Concurrency.</b> {@code approve} / {@code reject} are guarded twice: by a
 * status check inside the transaction and by JPA optimistic locking on the
 * entity's {@code @Version}. Two simultaneous clicks therefore produce one
 * success and one conflict, never a double write and never a silent overwrite.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final TripleRepository triples;
    private final com.prism.claims.ClaimRepository claims;
    private final CorpusAccessService access;
    private final UserService users;
    private final TraceRecorder traces;
    private final GraphService graphService;
    private final com.prism.contradiction.ContradictionScanService contradictionScan;

    public ApprovalService(TripleRepository triples,
                          com.prism.claims.ClaimRepository claims,
                          CorpusAccessService access,
                          UserService users,
                          TraceRecorder traces,
                          GraphService graphService,
                          com.prism.contradiction.ContradictionScanService contradictionScan) {
        this.triples = triples;
        this.claims = claims;
        this.access = access;
        this.users = users;
        this.traces = traces;
        this.graphService = graphService;
        this.contradictionScan = contradictionScan;
    }

    // ---- queue ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Triple> pendingTripleQueue(Long userId, Long corpusId, Pageable pageable) {
        access.requireVerifier(userId);
        access.requireAccessible(corpusId, userId);
        return triples.findByCorpusIdAndStatusOrderByCreatedAtDesc(corpusId, ProposalStatus.PENDING, pageable);
    }

    @Transactional(readOnly = true)
    public Page<com.prism.claims.Claim> pendingClaimQueue(Long userId, Long corpusId, Pageable pageable) {
        access.requireVerifier(userId);
        access.requireAccessible(corpusId, userId);
        return claims.findByCorpusIdAndStatusOrderByCreatedAtDesc(corpusId,
                com.prism.claims.ClaimStatus.PROPOSED, pageable);
    }

    // ---- decisions --------------------------------------------------------

    /**
     * Approves a triple.
     *
     * @return true when this call performed the approval
     * @throws ApiException 403 without the verifier role, 404 when out of scope,
     *                      409 when another decision already landed
     */
    @Transactional
    public boolean approveTriple(Long userId, Long tripleId, String note) {
        access.requireVerifier(userId);
        Triple triple = triples.findById(tripleId)
                .orElseThrow(() -> ApiException.notFound("Triple", tripleId));
        // Object-level authorization: resolve through the corpus, not by id alone.
        Corpus corpus = access.requireAccessible(triple.getCorpus().getId(), userId);
        User verifier = users.getById(userId);

        TraceRun traceRun = traces.startRun(com.prism.trace.TraceOperationType.ADMIN,
                triple.getCorpus(), triple.getSourceChunk().getDocument(), verifier,
                "approve:triple:" + tripleId, null);
        TraceStep root = traces.simple(traceRun.getId(), null, ActorType.HUMAN,
                TraceEventType.TRIPLE_APPROVED, "Approval decision requested");

        if (triple.getStatus() != ProposalStatus.PENDING) {
            traces.failure(traceRun.getId(), root, ActorType.HUMAN, TraceEventType.TRIPLE_APPROVED,
                    "Approval rejected: already decided", "current status is " + triple.getStatus());
            traces.finishRun(traceRun.getId(), com.prism.trace.TraceRunStatus.FAILED, "already decided");
            throw ApiException.stateConflict("triple " + tripleId + " was already " + triple.getStatus());
        }

        boolean applied;
        try {
            applied = triple.approve(verifier, note, Instant.now());
            triples.saveAndFlush(triple);
        } catch (OptimisticLockingFailureException ex) {
            // A concurrent decision won the race. Report it as a conflict so the
            // caller reloads rather than assuming success.
            traces.failure(traceRun.getId(), root, ActorType.HUMAN, TraceEventType.TRIPLE_APPROVED,
                    "Approval lost an optimistic-lock race", ex.getMessage());
            traces.finishRun(traceRun.getId(), com.prism.trace.TraceRunStatus.FAILED, "concurrent decision");
            throw ApiException.stateConflict("triple " + tripleId + " was decided concurrently");
        }

        if (!applied) {
            traces.failure(traceRun.getId(), root, ActorType.HUMAN, TraceEventType.TRIPLE_APPROVED,
                    "Approval was a no-op", "status changed to " + triple.getStatus());
            traces.finishRun(traceRun.getId(), com.prism.trace.TraceRunStatus.FAILED, "already decided");
            throw ApiException.stateConflict("triple " + tripleId + " was already decided");
        }

        traces.record(traceRun.getId(), root, ActorType.HUMAN, TraceEventType.TRIPLE_APPROVED,
                "Triple approved by " + verifier.getUsername(),
                TraceRecorder.StepPayload.builder()
                        .inputSummary(triple.getSubject() + " " + triple.getPredicate() + " " + triple.getObject())
                        .inputRefs(java.util.List.of(triple.getId()))
                        .outputSummary("status=APPROVED decidedBy=" + verifier.getId()
                                + (note == null || note.isBlank() ? "" : " note=\"" + note + "\""))
                        .build());
        traces.finishRun(traceRun.getId(), com.prism.trace.TraceRunStatus.SUCCEEDED, null);

        // Approval changes trusted knowledge: drop cached graph metrics and look
        // for contradictions the new edge may have created.
        //
        // The scan runs inside this transaction on purpose. If contradiction
        // detection cannot complete, rolling the approval back is the correct
        // outcome — an approved fact that never got a contradiction check is
        // exactly the inconsistent state this system exists to prevent.
        graphService.invalidateAll();
        contradictionScan.scanAuthorized(corpus, null);
        return true;
    }

    @Transactional
    public boolean rejectTriple(Long userId, Long tripleId, String reason) {
        access.requireVerifier(userId);
        Triple triple = triples.findById(tripleId)
                .orElseThrow(() -> ApiException.notFound("Triple", tripleId));
        access.requireAccessible(triple.getCorpus().getId(), userId);
        User verifier = users.getById(userId);

        if (triple.getStatus() != ProposalStatus.PENDING) {
            throw ApiException.stateConflict("triple " + tripleId + " was already " + triple.getStatus());
        }
        String note = (reason == null || reason.isBlank()) ? "rejected without a stated reason" : reason;
        if (!triple.reject(verifier, note, Instant.now())) {
            throw ApiException.stateConflict("triple " + tripleId + " was already decided");
        }
        triples.saveAndFlush(triple);

        TraceRun traceRun = traces.startRun(com.prism.trace.TraceOperationType.ADMIN,
                triple.getCorpus(), triple.getSourceChunk().getDocument(), verifier,
                "reject:triple:" + tripleId, null);
        traces.record(traceRun.getId(), null, ActorType.HUMAN, TraceEventType.TRIPLE_REJECTED,
                "Triple rejected by " + verifier.getUsername(),
                TraceRecorder.StepPayload.builder()
                        .inputSummary(triple.getSubject() + " " + triple.getPredicate() + " " + triple.getObject())
                        .inputRefs(java.util.List.of(triple.getId()))
                        .outputSummary("status=REJECTED reason=\"" + note + "\"")
                        .build());
        traces.finishRun(traceRun.getId(), com.prism.trace.TraceRunStatus.SUCCEEDED, null);

        // A rejection cannot create a new conflict, but it can remove one, so a
        // rescan keeps the contradiction list honest.
        graphService.invalidateAll();
        return true;
    }

    @Transactional
    public boolean approveClaim(Long userId, Long claimId) {
        access.requireVerifier(userId);
        com.prism.claims.Claim claim = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("Claim", claimId));
        Corpus corpus = access.requireAccessible(claim.getCorpus().getId(), userId);
        User verifier = users.getById(userId);

        if (claim.getStatus() != com.prism.claims.ClaimStatus.PROPOSED) {
            throw ApiException.stateConflict("claim " + claimId + " was already " + claim.getStatus());
        }
        if (!claim.approve(verifier, Instant.now())) {
            throw ApiException.stateConflict("claim " + claimId + " was already decided");
        }
        claims.saveAndFlush(claim);
        traceClaimDecision(claim, verifier, true);
        // An approved claim becomes eligible for verification, so a contradiction
        // the new claim introduces must be caught now rather than later.
        contradictionScan.scanAuthorized(corpus, null);
        return true;
    }

    @Transactional
    public boolean rejectClaim(Long userId, Long claimId, String reason) {
        access.requireVerifier(userId);
        com.prism.claims.Claim claim = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("Claim", claimId));
        access.requireAccessible(claim.getCorpus().getId(), userId);
        User verifier = users.getById(userId);

        if (claim.getStatus() != com.prism.claims.ClaimStatus.PROPOSED) {
            throw ApiException.stateConflict("claim " + claimId + " was already " + claim.getStatus());
        }
        if (!claim.reject(verifier, Instant.now())) {
            throw ApiException.stateConflict("claim " + claimId + " was already decided");
        }
        claims.saveAndFlush(claim);
        traceClaimDecision(claim, verifier, false);
        return true;
    }

    private void traceClaimDecision(com.prism.claims.Claim claim, User verifier, boolean approved) {
        TraceRun traceRun = traces.startRun(com.prism.trace.TraceOperationType.ADMIN,
                claim.getCorpus(), claim.getSourceChunk().getDocument(), verifier,
                (approved ? "approve:claim:" : "reject:claim:") + claim.getId(), null);
        traces.record(traceRun.getId(), null, ActorType.HUMAN,
                approved ? TraceEventType.CLAIM_APPROVED : TraceEventType.CLAIM_REJECTED,
                "Claim " + (approved ? "approved" : "rejected") + " by " + verifier.getUsername(),
                TraceRecorder.StepPayload.builder()
                        .inputSummary(claim.getSubject() + " — " + abbreviate(claim.getClaimText()))
                        .inputRefs(java.util.List.of(claim.getId()))
                        .outputSummary("status=" + claim.getStatus())
                        .build());
        traces.finishRun(traceRun.getId(), com.prism.trace.TraceRunStatus.SUCCEEDED, null);
    }

    /** Bulk approval used by the queue's "select all matching" action. */
    @Transactional
    public int approveTriples(Long userId, java.util.List<Long> tripleIds) {
        int approved = 0;
        for (Long id : tripleIds) {
            try {
                if (approveTriple(userId, id, "bulk approval")) {
                    approved++;
                }
            } catch (ApiException ex) {
                log.info("Skipping triple {} during bulk approval: {}", id, ex.getMessage());
            }
        }
        return approved;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }
}
