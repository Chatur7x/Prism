package com.prism.trace;

import com.prism.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The transactional write of one trace step.
 *
 * <p>This exists as its own bean for one reason, and it is the same reason
 * {@code EntitySupportCounter}, {@code DebateStateService},
 * {@code ProposalWriter}, {@code BackgroundJobService},
 * {@code DocumentStatusService}, and {@code ChatPersistenceService} each exist:
 * Spring's transaction advice is proxy-based, so a {@code @Transactional} method
 * invoked from inside its own class never passes through the proxy and the
 * annotation is silently ignored.
 *
 * <p><b>It was ignored here, and the result was a self-deadlock.</b> The step
 * write was originally a method on {@code TraceRecorder} called by
 * {@code TraceRecorder.record}. With {@code REQUIRES_NEW} not applied, the write
 * joined the caller's transaction — for an approval, the long-lived approval
 * transaction — and held X locks on both {@code trace_runs} and
 * {@code trace_steps} until that outer transaction committed. The very next
 * call, {@code finishRun}, <em>did</em> go through the proxy, so it opened a
 * second connection and waited for the first transaction to release the
 * {@code trace_runs} row the outer transaction was still holding. 50 seconds,
 * then a lock wait timeout, on every single approval.
 *
 * <p>The rule this reinforces: <b>a transactional method must be called from
 * outside its own class.</b> If it must live near its collaborators, the
 * collaborators move into a new bean instead.
 */
@Service
public class TraceStepWriter {

    private final TraceRunRepository runs;
    private final TraceStepRepository steps;

    public TraceStepWriter(TraceRunRepository runs, TraceStepRepository steps) {
        this.runs = runs;
        this.steps = steps;
    }

    /**
     * Claims the next sequence for the run and inserts the step, in its own
     * transaction.
     *
     * <p>Sequence assignment is a single atomic {@code UPDATE ... SET
     * step_seq = step_seq + 1} followed by a read-back inside the same
     * transaction, rather than {@code max(seq) + 1}. A debate round writes steps
     * from three threads at once; a read-then-increment would hand all three the
     * same number and the {@code (run_id, seq)} unique index would reject the
     * losers, losing audit steps from the system whose purpose is the audit
     * trail.
     *
     * <p>Short by construction. It must never be combined with the business work
     * being traced, or the trace row lock would be held for the whole operation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TraceStep write(Long runId, TraceStep parentStep, ActorType actor, TraceEventType event,
                           String name, TraceRecorder.StepPayload payload) {
        // The increment takes a row lock on the run, so a second concurrent
        // writer blocks here rather than computing the same number and colliding
        // with the unique index.
        runs.incrementStepSeq(runId);
        long seq = runs.readStepSeq(runId);

        TraceRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("TraceRun", runId));
        TraceStep step = new TraceStep(run, parentStep, actor, event, name,
                payload == null ? "OK" : payload.status());
        step.assignSeq(seq);
        if (payload != null) {
            step.setInputSummary(payload.inputSummary());
            step.setInputReferenceIds(payload.inputReferenceIds());
            step.setOutputSummary(payload.outputSummary());
            step.setOutputReferenceIds(payload.outputReferenceIds());
            step.setRuleVersion(payload.ruleVersion());
            step.setPromptVersion(payload.promptVersion());
            step.setModel(payload.model());
            step.setDurationMs(payload.durationMs());
            step.setErrorMessage(payload.errorMessage());
            step.setAttempt(payload.attempt());
        }
        return steps.save(step);
    }

    /** Steps of a run in sequence order. A plain read, so no transaction of its own. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<TraceStep> stepsOf(Long runId) {
        return steps.findByRunIdOrderBySeqAsc(runId);
    }
}