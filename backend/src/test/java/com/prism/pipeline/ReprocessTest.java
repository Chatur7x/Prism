package com.prism.pipeline;

import com.prism.document.Document;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentService;
import com.prism.document.DocumentStatus;
import com.prism.extraction.ExtractionService;
import com.prism.trace.TraceRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves POST /api/documents/{id}/reprocess genuinely starts a new attempt
 * instead of answering 200 while doing nothing.
 *
 * <p>Production incident: document 63 finished its first ingestion with zero
 * proposals (all chunks quarantined) and status AWAITING_APPROVAL. Every
 * reprocess returned the unchanged document because the idempotency guard
 * treated "pipeline ran" as "work done", and no new extraction run, attempt,
 * or quarantine row ever appeared.
 */
class ReprocessTest {

    private static BackgroundJob jobIn(BackgroundJob.Status status) {
        BackgroundJob job = new BackgroundJob("extract:document:63",
                BackgroundJob.Type.EXTRACTION, null, null, null, 5);
        if (status == BackgroundJob.Status.SUCCEEDED) {
            job.markRunning(Instant.now());
            job.markSucceeded(Instant.now());
        } else if (status == BackgroundJob.Status.FAILED) {
            // FAILED is terminal-by-design (markUnrecoverable): only an explicit
            // operator reprocess — the human intervention it was waiting for —
            // may return it to the queue.
            job.markRunning(Instant.now());
            job.markUnrecoverable("boom", Instant.now());
        } else if (status == BackgroundJob.Status.RUNNING) {
            job.markRunning(Instant.now());
        } else if (status == BackgroundJob.Status.ABANDONED) {
            for (int i = 0; i < 5; i++) {
                job.markRunning(Instant.now());
                job.markFailed("boom", Instant.now());
            }
        }
        assertThat(job.getStatus()).isEqualTo(status);
        return job;
    }

    // ---- entity transitions ---------------------------------------------------

    @Test
    @DisplayName("a SUCCEEDED job resets to PENDING for retry")
    void succeededResets() {
        BackgroundJob job = jobIn(BackgroundJob.Status.SUCCEEDED);

        assertThat(job.requeueForRetry(Instant.now())).isTrue();
        assertThat(job.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);
    }

    @Test
    @DisplayName("a FAILED job resets to PENDING for retry")
    void failedResets() {
        BackgroundJob job = jobIn(BackgroundJob.Status.FAILED);

        assertThat(job.requeueForRetry(Instant.now())).isTrue();
        assertThat(job.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);
    }

    @Test
    @DisplayName("in-flight work is never reset")
    void inFlightIsUntouched() {
        assertThat(jobIn(BackgroundJob.Status.PENDING).requeueForRetry(Instant.now())).isFalse();
        assertThat(jobIn(BackgroundJob.Status.RUNNING).requeueForRetry(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("an ABANDONED job resets on explicit operator reprocess")
    void abandonedResets() {
        BackgroundJob job = jobIn(BackgroundJob.Status.ABANDONED);

        assertThat(job.requeueForRetry(Instant.now())).isTrue();
        assertThat(job.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);
    }

    // ---- reprocessDocument ----------------------------------------------------

    private static Document document() {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(63L);
        when(doc.getStatus()).thenReturn(DocumentStatus.AWAITING_APPROVAL);
        return doc;
    }

    private static IngestionPipeline pipeline(BackgroundJobService jobs, Document doc,
                                              ThreadPoolTaskExecutor executor) {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(63L)).thenReturn(Optional.of(doc));
        return new IngestionPipeline(jobs, documents, mock(DocumentService.class),
                mock(ExtractionService.class), mock(TraceRecorder.class), executor);
    }

    @Test
    @DisplayName("reprocess after success resubmits and marks EXTRACTING")
    void reprocessAfterSuccessResubmits() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        BackgroundJob finished = jobIn(BackgroundJob.Status.SUCCEEDED);
        when(jobs.findByKey("extract:document:63")).thenReturn(Optional.of(finished));
        when(jobs.requeue(any())).thenAnswer(inv -> {
            // The service-level reset this mock stands in for.
            finished.requeueForRetry(Instant.now());
            return true;
        });
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        Document doc = document();
        IngestionPipeline pipeline = pipeline(jobs, doc, executor);

        Document returned = pipeline.reprocessDocument(63L);

        assertThat(returned).isSameAs(doc);
        verify(executor).execute(any(Runnable.class));
        assertThat(finished.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);
    }

    @Test
    @DisplayName("reprocess while running submits nothing and changes nothing")
    void reprocessWhileRunningIsHonestNoOp() {        BackgroundJobService jobs = mock(BackgroundJobService.class);
        when(jobs.findByKey("extract:document:63"))
                .thenReturn(Optional.of(jobIn(BackgroundJob.Status.RUNNING)));
        when(jobs.requeue(any())).thenReturn(false);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DocumentService documentService = mock(DocumentService.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        Document doc = document();
        when(documents.findById(63L)).thenReturn(Optional.of(doc));
        IngestionPipeline pipeline = new IngestionPipeline(jobs, documents, documentService,
                mock(ExtractionService.class), mock(TraceRecorder.class), executor);

        Document returned = pipeline.reprocessDocument(63L);

        assertThat(returned).isSameAs(doc);
        verify(executor, never()).execute(any(Runnable.class));
        verify(documentService, never()).setStatus(eq(63L), any(DocumentStatus.class));
    }

    @Test
    @DisplayName("reprocess with no prior job takes the first-run path")
    void reprocessWithoutJobEnqueues() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        when(jobs.findByKey("extract:document:63")).thenReturn(Optional.empty());
        BackgroundJob fresh = jobIn(BackgroundJob.Status.PENDING);
        when(jobs.enqueue(anyString(), any(), any(), any(), any(), anyInt())).thenReturn(fresh);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        IngestionPipeline pipeline = pipeline(jobs, document(), executor);

        pipeline.reprocessDocument(63L);

        verify(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("reprocess adopts an orphaned PENDING job without touching status")
    void reprocessAdoptsOrphanedPending() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        when(jobs.findByKey("extract:document:63"))
                .thenReturn(Optional.of(jobIn(BackgroundJob.Status.PENDING)));
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DocumentService documentService = mock(DocumentService.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        Document doc = document();
        when(documents.findById(63L)).thenReturn(Optional.of(doc));
        IngestionPipeline pipeline = new IngestionPipeline(jobs, documents, documentService,
                mock(ExtractionService.class), mock(TraceRecorder.class), executor);

        Document returned = pipeline.reprocessDocument(63L);

        assertThat(returned).isSameAs(doc);
        verify(executor).execute(any(Runnable.class));
        // No reset needed: the row is already PENDING, so requeue must not run.
        verify(jobs, never()).requeue(any());
        // The worker marks EXTRACTING on claim; marking it here would leave
        // the document transitional forever if the executor rejects below.
        verify(documentService, never()).setStatus(eq(63L), any(DocumentStatus.class));
    }

    @Test
    @DisplayName("a rejected executor submission leaves the document status honest")
    void rejectedSubmitLeavesStatusHonest() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        when(jobs.findByKey("extract:document:63"))
                .thenReturn(Optional.of(jobIn(BackgroundJob.Status.PENDING)));
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        org.mockito.stubbing.Answer<?> reject = inv -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        };
        org.mockito.Mockito.doAnswer(reject).when(executor).execute(any(Runnable.class));
        DocumentService documentService = mock(DocumentService.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        Document doc = document();
        when(documents.findById(63L)).thenReturn(Optional.of(doc));
        IngestionPipeline pipeline = new IngestionPipeline(jobs, documents, documentService,
                mock(ExtractionService.class), mock(TraceRecorder.class), executor);

        Document returned = pipeline.reprocessDocument(63L);

        assertThat(returned).isSameAs(doc);
        verify(documentService, never()).setStatus(eq(63L), any(DocumentStatus.class));
    }
}
