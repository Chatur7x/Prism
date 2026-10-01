package com.prism.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Durable job state transitions.
 *
 * <p><b>Why this is a separate bean.</b> The naive version of this code loads a
 * {@link BackgroundJob}, mutates it, and saves it several times. That is broken:
 * {@code findById} returns a <em>detached</em> instance once its transaction
 * closes, and {@code save} on a detached entity with a {@code @Version} performs
 * a {@code merge} that returns a <em>different</em> managed instance. The local
 * variable keeps the original stale version, so the second save issues
 * {@code UPDATE ... WHERE version = <stale>} and throws
 * {@code StaleObjectStateException} — leaving the job stuck in RUNNING forever,
 * which is exactly the kind of silent breakage this system exists to prevent.
 *
 * <p>Each method here re-reads the row inside its own short transaction and
 * returns a result, so no entity instance is ever carried across a transaction
 * boundary. Concurrency safety comes from the conditional status check
 * ({@code markRunning} only succeeds from PENDING) plus the {@code @Version}
 * column, giving one winner when two workers race.
 *
 * <p>Separate bean also means {@code REQUIRES_NEW} is actually applied: Spring's
 * transaction advice is proxy-based, so a self-invoked {@code @Transactional}
 * method silently does nothing.
 */
@Service
public class BackgroundJobService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobService.class);

    private final BackgroundJobRepository jobs;

    public BackgroundJobService(BackgroundJobRepository jobs) {
        this.jobs = jobs;
    }

    /** Result of an attempt to claim a job. */
    public record ClaimResult(boolean claimed, String jobKey, String lastError) {

        public static ClaimResult notClaimed() {
            return new ClaimResult(false, null, null);
        }
    }

    /**
     * Finds the job for this key, creating it in PENDING if absent.
     *
     * <p>Idempotent by construction: the unique index on {@code job_key} means a
     * concurrent caller cannot create a duplicate, and re-enqueueing an existing
     * job returns the same row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BackgroundJob enqueue(String jobKey, BackgroundJob.Type type,
                                 com.prism.corpus.Corpus corpus,
                                 com.prism.document.Document document,
                                 String payloadJson, int maxAttempts) {
        return jobs.findByJobKey(jobKey).orElseGet(() -> {
            BackgroundJob created = new BackgroundJob(jobKey, type, corpus, document,
                    payloadJson, maxAttempts);
            try {
                return jobs.saveAndFlush(created);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                // Another thread inserted the same key between our find and save.
                // The unique index resolved it; re-read and use the winner's row.
                log.debug("Job {} was created concurrently; reusing the existing row", jobKey);
                return jobs.findByJobKey(jobKey).orElseThrow(() -> ex);
            }
        });
    }

    /**
     * Atomically transitions PENDING → RUNNING and increments the attempt count.
     *
     * @return {@code claimed=false} when another worker already holds the job, or
     *         when it is not in PENDING (already done, or permanently failed)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(Long jobId) {
        BackgroundJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return new ClaimResult(false, null, "job no longer exists");
        }
        if (!job.markRunning(Instant.now())) {
            return new ClaimResult(false, job.getJobKey(),
                    "job is " + job.getStatus() + ", not PENDING");
        }
        jobs.saveAndFlush(job);
        return new ClaimResult(true, job.getJobKey(), null);
    }

    /** Records liveness so a crash mid-run is detectable on restart. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(Long jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            job.heartbeat(Instant.now());
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(Long jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            job.markSucceeded(Instant.now());
            jobs.save(job);
        });
    }

    /**
     * Records a failure. The job returns to PENDING for another attempt until the
     * ceiling, then becomes ABANDONED and needs a human.
     *
     * <p>Never throws: this is called from the failure path, and a second
     * exception here would mask the original one and strand the job.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long jobId, String error) {
        try {
            jobs.findById(jobId).ifPresent(job -> {
                job.markFailed(error, Instant.now());
                jobs.save(job);
            });
        } catch (RuntimeException ex) {
            log.error("Could not record failure for job {}: {}", jobId, ex.getMessage(), ex);
        }
    }

    /** Current status, or null when the job does not exist. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public BackgroundJob.Status statusOf(Long jobId) {
        return jobs.findById(jobId).map(BackgroundJob::getStatus).orElse(null);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<BackgroundJob> findByKey(String jobKey) {
        return jobs.findByJobKey(jobKey);
    }

    @Transactional(readOnly = true)
    public java.util.List<BackgroundJob> findAll() {
        return jobs.findAll();
    }
}
