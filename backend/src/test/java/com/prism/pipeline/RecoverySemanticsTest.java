package com.prism.pipeline;

import com.prism.claims.Claim;
import com.prism.claims.ClaimPolarity;
import com.prism.claims.EvidenceStatus;
import com.prism.claims.Verdict;
import com.prism.claims.VerdictType;
import com.prism.common.error.ApiException;
import com.prism.debate.DebateEngine;
import com.prism.debate.DebateState;
import com.prism.knowledge.ProposalStatus;
import com.prism.knowledge.Triple;
import com.prism.user.Role;
import com.prism.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The failure-recovery guarantees that live in entity state transitions and the
 * debate FSM, tested without a database.
 *
 * <p>Each test names the production incident it prevents. A crash loop that never
 * reaches ABANDONED, a second approval that silently rewrites the first
 * verifier's decision, a debate started twice running two round-ones against
 * each other — all of these are one missing guard away, and the guard is what is
 * asserted here.
 *
 * <p>What this does not cover, deliberately: the restart path that touches the
 * database ({@code RecoveryService}) and the service-level conflict mapping are
 * exercised in {@code RestartRecoveryIntegrationTest}, where a real MySQL
 * container exists to make the assertions mean something.
 */
class RecoverySemanticsTest {

    private static User verifier(String name) {
        return new User(name, name + "@example.com", "hash", Role.VERIFIER);
    }

    private static Triple pendingTriple() {
        return new Triple(null, null, "Meridian Group", "reports_to", null,
                "Northstar Holdings", "Meridian Group reports_to Northstar Holdings.",
                null, null, "EXTRACT_V1", null, "fact-key", "fact-hash");
    }

    // ---- duplicate approval -------------------------------------------------

    @Test
    @DisplayName("a second approval is a no-op: the first decision stands")
    void duplicateApprovalIsANoOp() {
        Triple triple = pendingTriple();
        User first = verifier("first-verifier");
        User second = verifier("second-verifier");
        Instant decidedAt = Instant.now();

        assertThat(triple.approve(first, "looks right", decidedAt)).isTrue();
        assertThat(triple.approve(second, "override attempt", decidedAt.plusSeconds(5))).isFalse();

        assertThat(triple.getStatus()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(triple.getDecidedBy()).isSameAs(first);
        assertThat(triple.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(triple.getDecisionNote()).isEqualTo("looks right");
    }

    @Test
    @DisplayName("a rejection after an approval is refused, not recorded")
    void rejectAfterApproveIsRefused() {
        Triple triple = pendingTriple();
        triple.approve(verifier("v"), null, Instant.now());

        assertThat(triple.reject(verifier("other"), "changed mind", Instant.now())).isFalse();
        assertThat(triple.getStatus()).isEqualTo(ProposalStatus.APPROVED);
    }

    // ---- job claim and crash-loop bound -------------------------------------

    @Test
    @DisplayName("a job can be claimed exactly once")
    void jobClaimedExactlyOnce() {
        BackgroundJob job = new BackgroundJob("key-1", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 3);

        assertThat(job.markRunning(Instant.now())).isTrue();
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.markRunning(Instant.now())).isFalse();
        assertThat(job.getAttemptCount())
                .as("a rejected claim must not consume an attempt")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a crash loop is bounded: repeated failure reaches ABANDONED")
    void crashLoopReachesAbandoned() {
        BackgroundJob job = new BackgroundJob("key-2", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 2);
        Instant now = Instant.now();

        job.markRunning(now);
        job.markFailed("boom", now);
        assertThat(job.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);

        job.markRunning(now);
        job.markFailed("boom again", now);
        assertThat(job.getStatus())
                .as("attempt 2 of max 2 must abandon rather than retry forever")
                .isEqualTo(BackgroundJob.Status.ABANDONED);

        assertThat(job.markRunning(now))
                .as("an abandoned job must not be claimable")
                .isFalse();
    }

    @Test
    @DisplayName("a restart requeue preserves the attempt count")
    void restartRequeuePreservesAttempts() {        BackgroundJob job = new BackgroundJob("key-3", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 5);
        Instant now = Instant.now();
        job.markRunning(now);
        job.markRunning(now); // rejected; attempt stays 1

        job.requeueAfterRestart();

        assertThat(job.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);
        assertThat(job.getAttemptCount())
                .as("resetting attempts on restart would let a crash loop retry forever")
                .isEqualTo(1);
        assertThat(job.getLastError()).contains("restart");
    }

    @Test
    @DisplayName("staleness: only a RUNNING job with an old heartbeat qualifies")
    void stalenessRules() {
        Instant old = Instant.now().minusSeconds(3600);
        Instant fresh = Instant.now();

        BackgroundJob pending = new BackgroundJob("k-p", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 3);
        assertThat(pending.isStale(old)).isFalse();

        BackgroundJob runningFresh = new BackgroundJob("k-f", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 3);
        runningFresh.markRunning(fresh);
        assertThat(runningFresh.isStale(fresh.minusSeconds(900))).isFalse();

        BackgroundJob runningStale = new BackgroundJob("k-s", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 3);
        runningStale.markRunning(old);
        runningStale.heartbeat(old);
        assertThat(runningStale.isStale(old.plusSeconds(3600))).isTrue();

        BackgroundJob noHeartbeat = new BackgroundJob("k-n", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 3);
        noHeartbeat.markRunning(old);
        noHeartbeat.heartbeat(null);
        assertThat(noHeartbeat.isStale(fresh))
                .as("a RUNNING job that never heartbeated died before its first beat")
                .isTrue();
    }

    // ---- duplicate enqueue ---------------------------------------------------

    @Test
    @DisplayName("a concurrent duplicate enqueue resolves to the winner's row")
    void concurrentEnqueueResolvesToWinner() {
        BackgroundJobRepository jobs = mock(BackgroundJobRepository.class);
        BackgroundJobService service = new BackgroundJobService(jobs);
        BackgroundJob winner = new BackgroundJob("dup-key", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 3);

        when(jobs.findByJobKey("dup-key"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(jobs.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry 'dup-key'"));

        assertThat(service.enqueue("dup-key", BackgroundJob.Type.EXTRACTION, null, null, "{}", 3))
                .isSameAs(winner);
    }

    // ---- machine verdict preservation ----------------------------------------

    @Test
    @DisplayName("adjudication overrides the outcome but preserves the machine verdict")
    void adjudicationPreservesMachineVerdict() {
        Claim claim = new Claim(null, "Meridian Group", "Meridian Group funds Aster Labs",
                ClaimPolarity.POSITIVE, "funds", "Aster Labs",
                "Meridian Group funds Aster Labs.", null, null, "EXTRACT_V1", null,
                "claim-key", "claim-hash");
        Verdict verdict = new Verdict(claim, VerdictType.CONTRADICTED, EvidenceStatus.EVIDENCE_FOUND,
                0.8, 0.1, 0.7, "two sources disagree", "machine reasoning", "model-x",
                "VERIFY_V1", "RULES_V1", "funds Aster", null);

        verdict.adjudicate(verifier("human"), VerdictType.SUPPORTED, "source re-read", Instant.now());

        assertThat(verdict.getMachineVerdictType()).isEqualTo(VerdictType.CONTRADICTED);
        assertThat(verdict.getVerdictType()).isEqualTo(VerdictType.SUPPORTED);
        assertThat(verdict.getAdjudicator()).isNotNull();
        assertThat(verdict.wasOverridden()).isTrue();
    }

    @Test
    @DisplayName("a second adjudication cannot rewrite the machine verdict either")
    void secondAdjudicationKeepsMachineVerdict() {
        Claim claim = new Claim(null, "S", "c", ClaimPolarity.NEUTRAL, "owns", "O",
                "S owns O.", null, null, "EXTRACT_V1", null, "k2", "h2");
        Verdict verdict = new Verdict(claim, VerdictType.INSUFFICIENT_EVIDENCE,
                EvidenceStatus.NO_EVIDENCE, null, 0.0, 0.0, "nothing found", "r",
                "model-x", "VERIFY_V1", "RULES_V1", "q", null);

        verdict.adjudicate(verifier("h1"), VerdictType.SUPPORTED, "first look", Instant.now());
        verdict.adjudicate(verifier("h2"), VerdictType.CONTRADICTED, "second look", Instant.now());

        assertThat(verdict.getMachineVerdictType()).isEqualTo(VerdictType.INSUFFICIENT_EVIDENCE);
        assertThat(verdict.getVerdictType()).isEqualTo(VerdictType.CONTRADICTED);
    }

    // ---- debate FSM rejects duplicate starts ----------------------------------

    @Test
    @DisplayName("START is legal exactly once: from any other state it is a conflict")
    void duplicateStartIsAFsmConflict() {
        for (DebateState state : DebateState.values()) {
            if (state == DebateState.CREATED) {
                continue;
            }
            assertThatThrownBy(() -> DebateEngine.decide(state, DebateState.Event.START, 1, 3))
                    .as("START from " + state + " must be refused")
                    .isInstanceOf(ApiException.class)
                    .matches(e -> ((ApiException) e).code()
                            == com.prism.common.error.ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
    }

    @Test
    @DisplayName("a terminal debate accepts no further events")
    void terminalStatesAcceptNothing() {
        for (DebateState terminal : new DebateState[]{DebateState.COMPLETED, DebateState.ABORTED}) {
            for (DebateState.Event event : DebateState.Event.values()) {
                assertThatThrownBy(() -> DebateEngine.decide(terminal, event, 1, 3))
                        .as(event + " from " + terminal + " must be refused")
                        .isInstanceOf(ApiException.class);
            }
        }
    }

    @Test
    @DisplayName("an unrecoverable job fails terminally without spending attempts")
    void unrecoverableJobFailsTerminally() {
        BackgroundJob job = new BackgroundJob("key-4", BackgroundJob.Type.EXTRACTION,
                null, null, "{}", 5);
        Instant now = Instant.now();
        job.markRunning(now);

        job.markUnrecoverable("orphaned with no document", now);

        assertThat(job.getStatus()).isEqualTo(BackgroundJob.Status.FAILED);
        assertThat(job.getAttemptCount())
                .as("the attempt was spent trying, not failing")
                .isEqualTo(1);
        assertThat(job.getLastError()).contains("orphaned");
        assertThat(job.markRunning(now))
                .as("a FAILED job must not be claimable")
                .isFalse();
    }
}
