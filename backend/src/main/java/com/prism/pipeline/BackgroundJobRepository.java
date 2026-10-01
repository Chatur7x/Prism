package com.prism.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BackgroundJobRepository extends JpaRepository<BackgroundJob, Long> {

    Optional<BackgroundJob> findByJobKey(String jobKey);

    /**
     * Jobs the process believes are running but whose heartbeat is older than the
     * cutoff. These are the orphans a restart must requeue.
     */
    @Query("select j from BackgroundJob j where j.status = com.prism.pipeline.BackgroundJob.Status.RUNNING "
            + "and (j.heartbeatAt is null or j.heartbeatAt < :cutoff) order by j.id")
    List<BackgroundJob> findStaleRunning(@Param("cutoff") Instant cutoff);

    List<BackgroundJob> findByStatus(BackgroundJob.Status status);

    List<BackgroundJob> findByJobTypeAndStatus(BackgroundJob.Type type, BackgroundJob.Status status);

    long countByStatus(BackgroundJob.Status status);
}
