package com.prism.pipeline;

import com.prism.document.Document;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentService;
import com.prism.document.DocumentStatus;
import com.prism.extraction.ExtractionService;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceOperationType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asynchronous document ingestion.
 *
 * <p><b>Durability.</b> The {@link BackgroundJob} row is written before the
 * executor is touched, so a crash between the two leaves a recoverable record
 * rather than a lost request.
 *
 * <p><b>Idempotency.</b> Chunking returns existing chunks, and extraction
 * collapses duplicate facts by hash, so running a job twice cannot create
 * duplicate proposals or double a graph out-degree. That is what makes the
 * restart-recovery path safe without any distributed lock.
 *
 * <p><b>No entity crosses a transaction boundary.</b> All job state changes go
 * through {@link BackgroundJobService}, which re-reads and writes in a single
 * short transaction. Carrying a detached, versioned entity across transactions
 * silently corrupts the version and strands the job in RUNNING.
 */
@Service
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private static final int MAX_ATTEMPTS = 5;

    private final BackgroundJobService jobService;
    private final DocumentRepository documents;
    private final DocumentService documentService;
    private final ExtractionService extraction;
    private final TraceRecorder traces;
    private final ThreadPoolTaskExecutor executor;

    public IngestionPipeline(BackgroundJobService jobService, DocumentRepository documents,
                             DocumentService documentService, ExtractionService extraction,
                             TraceRecorder traces,
                             @Qualifier(com.prism.config.AsyncAndCacheConfig.PIPELINE_EXECUTOR)
                             ThreadPoolTaskExecutor executor) {
        this.jobService = jobService;
        this.documents = documents;
        this.documentService = documentService;
        this.extraction = extraction;
        this.traces = traces;
        this.executor = executor;
    }

    /** The deterministic idempotency key for a document's ingestion. */
    public static String extractionJobKey(Long documentId) {
        return "extract:document:" + documentId;
    }

    /**
     * Enqueues chunking and extraction for a document.
     *
     * <p>Returns immediately; the caller gets the document back while the
     * pipeline proceeds in the background.
     */
    public Document enqueueExtraction(Long documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> com.prism.common.error.ApiException
                        .notFound("Document", documentId));

        BackgroundJob job = jobService.enqueue(extractionJobKey(documentId),
                BackgroundJob.Type.EXTRACTION, document.getCorpus(), document, null, MAX_ATTEMPTS);

        if (job.getStatus() == BackgroundJob.Status.SUCCEEDED
                && document.getStatus() == DocumentStatus.AWAITING_APPROVAL) {
            // Already done. Re-running would be wasted work.
            return document;
        }

        submit(job.getId(), document.getId());
        return document;
    }

    private void submit(Long jobId, Long documentId) {
        try {
            executor.execute(() -> runJob(jobId, documentId));
        } catch (RejectedExecutionException ex) {
            // The queue is saturated. The job row is already PENDING, so the
            // recovery scheduler will pick it up; the request is not lost.
            log.warn("Pipeline queue is saturated; job {} stays PENDING for recovery", jobId);
        }
    }

    /**
     * Executes one job.
     *
     * <p>Never throws: a failure here must be recorded on the job row, not
     * propagated, because an escaping exception would leave the job RUNNING with
     * nobody watching.
     */
    public void runJob(Long jobId, Long documentId) {
        BackgroundJobService.ClaimResult claim = jobService.claim(jobId);
        if (!claim.claimed()) {
            log.debug("Job {} was not claimed: {}", jobId, claim.lastError());
            return;
        }
        log.info("Ingestion job {} claimed for document {}", claim.jobKey(), documentId);

        TraceRun traceRun = null;
        try {
            Document document = documents.findById(documentId).orElse(null);
            traceRun = traces.startRun(TraceOperationType.DOCUMENT_INGESTION,
                    document == null ? null : document.getCorpus(), document, null,
                    claim.jobKey(), null);

            // ---- chunking: deterministic, idempotent, fast ----
            documentService.chunkDocument(documentId);
            jobService.heartbeat(jobId);

            // ---- extraction: the slow, model-backed step ----
            ExtractionService.ExtractionOutcome outcome = extraction.extractDocument(documentId);

            if (outcome.succeeded()) {
                jobService.markSucceeded(jobId);
                traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
                log.info("Ingestion complete for document {}: {} triples, {} claims, {} quarantined",
                        documentId, outcome.triplesProposed(), outcome.claimsProposed(),
                        outcome.quarantined());
            } else {
                jobService.markFailed(jobId, outcome.error());
                traces.failure(traceRun.getId(), null, ActorType.ENGINE,
                        TraceEventType.PIPELINE_FAILED, "Ingestion failed", outcome.error());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, outcome.error());
                log.warn("Ingestion failed for document {}: {}", documentId, outcome.error());
            }

        } catch (RuntimeException ex) {
            log.error("Ingestion job {} threw", jobId, ex);
            jobService.markFailed(jobId, ex.getMessage());
            // The document must not be left in a transitional state, or the UI
            // would show it as still working forever.
            markDocumentFailed(documentId);
            if (traceRun != null) {
                traces.failure(traceRun.getId(), null, ActorType.ENGINE,
                        TraceEventType.PIPELINE_FAILED, "Ingestion threw an exception",
                        ex.getMessage());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
            }
        }
    }

    private void markDocumentFailed(Long documentId) {
        try {
            documentService.markFailed(documentId);
        } catch (RuntimeException ex) {
            log.error("Could not mark document {} as FAILED: {}", documentId, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<BackgroundJob> findJob(Long documentId) {
        return jobService.findByKey(extractionJobKey(documentId));
    }
}
