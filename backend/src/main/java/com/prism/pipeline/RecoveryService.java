package com.prism.pipeline;

import com.prism.config.PrismTuningProperties;
import com.prism.document.Document;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentStatus;
import com.prism.extraction.ExtractionRun;
import com.prism.extraction.ExtractionRunRepository;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceOperationType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Startup recovery for work orphaned by a restart.
 *
 * <p>An in-flight queue dies with the process. Without this, a document
 * interrupted mid-extraction would sit in EXTRACTING forever with no owner and
 * no indication that anything is wrong. On boot this class finds those orphans
 * and requeues them.
 *
 * <p>Safety relies on idempotency rather than on coordination: chunking returns
 * existing chunks, and extraction collapses facts by hash, so re-running an
 * interrupted job cannot create duplicates.
 *
 * <p>Requeueing is deliberately <b>not</b> done by rewriting the stale job row
 * into a fresh row. A new row with a new id would leave the original as a
 * permanent orphan in RUNNING, and the operator's job list would grow on every
 * restart. Instead the existing row is reset to PENDING in place, preserving its
 * attempt count so a crash loop is still bounded and eventually reaches
 * ABANDONED instead of retrying forever.
 */
@Component
@Order(10)
public class RecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final BackgroundJobRepository jobs;
    private final ExtractionRunRepository extractionRuns;
    private final DocumentRepository documents;
    private final IngestionPipeline pipeline;
    private final TraceRecorder traces;
    private final boolean enabled;
    private final int staleAfterMinutes;

    public RecoveryService(BackgroundJobRepository jobs, ExtractionRunRepository extractionRuns,
                           DocumentRepository documents, IngestionPipeline pipeline,
                           TraceRecorder traces, PrismTuningProperties tuning) {
        this.jobs = jobs;
        this.extractionRuns = extractionRuns;
        this.documents = documents;
        this.pipeline = pipeline;
        this.traces = traces;
        this.enabled = tuning.recovery().enabled();
        this.staleAfterMinutes = tuning.recovery().staleAfterMinutes();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Startup recovery is disabled");
            return;
        }
        int marked = markStaleExtractionRuns();
        int requeued = requeueStaleJobs();
        if (requeued > 0 || marked > 0) {
            log.warn("Startup recovery requeued {} job(s) and marked {} stale extraction run(s). "
                    + "The application was restarted while work was in flight.", requeued, marked);
        }
    }

    /**
     * Requeues jobs that were RUNNING when the process died.
     *
     * @return how many were requeued
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int requeueStaleJobs() {
        Instant cutoff = Instant.now().minusSeconds(staleAfterMinutes * 60L);
        List<BackgroundJob> stale = jobs.findStaleRunning(cutoff);
        int count = 0;
        for (BackgroundJob job : stale) {
            // Reset in place, preserving attemptCount so a job that repeatedly
            // crashes still eventually reaches ABANDONED.
            job.requeueAfterRestart();
            jobs.saveAndFlush(job);
            recordRecovery(job);
            if (job.getDocument() != null) {
                count++;
                // Dispatch after the transaction commits, so the worker never
                // races this thread for the row.
                pipeline.runJob(job.getId(), job.getDocument().getId());
            } else {
                // No document to work on: nothing can be requeued, so fail it
                // rather than leaving it RUNNING forever.
                job.markFailed("orphaned by a restart with no associated document", Instant.now());
                jobs.save(job);
            }
        }
        return count;
    }

    /**
     * Marks extraction runs whose heartbeat is stale as FAILED, so the document
     * surfaces its true state instead of appearing to be in progress forever.
     *
     * <p>The job row is requeued separately; this only fixes the run row, whose
     * status is what the document detail page reads.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markStaleExtractionRuns() {
        Instant cutoff = Instant.now().minusSeconds(staleAfterMinutes * 60L);
        List<ExtractionRun> stale = extractionRuns.findStaleRunning(cutoff);
        for (ExtractionRun run : stale) {
            run.markFailed("interrupted by an application restart; the job has been requeued",
                    Instant.now());
            extractionRuns.save(run);
            Document document = run.getDocument();
            if (document == null) {
                continue;
            }
            // Only downgrade documents still in a transitional state. One that has
            // since advanced must not regress.
            if (document.getStatus() == DocumentStatus.EXTRACTING
                    || document.getStatus() == DocumentStatus.CHUNKING
                    || document.getStatus() == DocumentStatus.UPLOADED
                    || document.getStatus() == DocumentStatus.CHUNKED) {
                document.transitionTo(DocumentStatus.AWAITING_APPROVAL, Instant.now());
                documents.save(document);
            }
        }
        return stale.size();
    }

    private void recordRecovery(BackgroundJob job) {
        TraceRun traceRun = traces.startRun(TraceOperationType.ADMIN, job.getCorpus(),
                job.getDocument(), null, "recovery:" + job.getJobKey(), null);
        traces.record(traceRun.getId(), null, ActorType.ENGINE, TraceEventType.SYSTEM_RECOVERY,
                "Background job requeued after restart",
                TraceRecorder.StepPayload.builder()
                        .inputSummary("job " + job.getJobKey() + " attempt " + job.getAttemptCount()
                                + "/" + job.getMaxAttempts())
                        .outputSummary("status=PENDING")
                        .build());
        traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
    }
}
