package com.prism.extraction;

import com.prism.claims.Claim;
import com.prism.claims.ClaimPolarity;
import com.prism.claims.ClaimStatus;
import com.prism.common.error.ApiException;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusRepository;
import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import com.prism.document.DocumentRepository;
import com.prism.knowledge.Entity;
import com.prism.knowledge.EntityRepository;
import com.prism.knowledge.ProposalStatus;
import com.prism.knowledge.Triple;
import com.prism.user.Role;
import com.prism.user.User;
import com.prism.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the extraction-run lifecycle survives repeated reprocessing.
 *
 * <p>Production incident (document 63): the first run finished SUCCEEDED with
 * zero proposals, and the V2 reprocess died completing with
 * {@code Duplicate entry '63-SUCCEEDED' for key
 * 'extraction_runs.uk_extraction_document_live'} — because the unique key
 * constrained (document_id, status) for <em>every</em> status, a second
 * finished run could never exist. V8 keeps the lookup index but drops the
 * uniqueness; single-flight for active runs is enforced by
 * {@link ExtractionPersistenceService#startRun} plus the background-job
 * claim, and crash recovery marks stale RUNNING rows FAILED first.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ExtractionRunHistoryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("prism_test")
            .withUsername("prism")
            .withPassword("prism")
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--default-authentication-plugin=mysql_native_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("prism.llm.provider", () -> "fake");
    }

    @Autowired
    ExtractionPersistenceService persistence;
    @Autowired
    com.prism.pipeline.BackgroundJobService jobService;
    @Autowired
    com.prism.pipeline.BackgroundJobRepository backgroundJobs;
    @Autowired
    com.prism.pipeline.RecoveryService recovery;
    @Autowired
    ExtractionRunRepository runs;
    @Autowired
    ExtractionQuarantineRepository quarantines;
    @Autowired
    UserRepository users;
    @Autowired
    CorpusRepository corpora;
    @Autowired
    DocumentRepository documents;
    @Autowired
    DocumentChunkRepository chunks;
    @Autowired
    EntityRepository entities;

    private User verifier;
    private Corpus corpus;
    private Document document;
    private DocumentChunk chunk;
    private String tag;

    @BeforeEach
    void fixtures() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        verifier = users.save(new User("hist-" + tag, "hist-" + tag + "@example.com",
                "hash", Role.VERIFIER));
        corpus = corpora.save(new Corpus("hist-corpus-" + tag, "run history fixtures", verifier));
        document = documents.save(new Document(corpus, verifier, "hist doc " + tag,
                "Meridian Group funds Aster Labs.", "hist-" + tag + ".md",
                "text/markdown", "hash-" + tag));
        chunk = chunks.save(new DocumentChunk(document, 0,
                "Meridian Group funds Aster Labs.", 0, 38, 10));
    }

    private ExtractionRun startRun(String promptVersion) {
        return persistence.startRun(document, 1, "fake-model", promptVersion);
    }

    private void succeed(ExtractionRun run) {
        // startRun commits in its own transaction, so the returned instance is
        // detached: transitions must be saved back or the guard queries below
        // would still see the old row state.
        run.markRunning(Instant.now());
        run.markSucceeded(Instant.now());
        runs.saveAndFlush(run);
    }

    // ---- history accumulates ----------------------------------------------------

    @Test
    @DisplayName("two SUCCEEDED runs for one document coexist")
    void twoSucceededRunsCoexist() {
        ExtractionRun v1 = startRun("EXTRACT_V1");
        succeed(v1);

        ExtractionRun v2 = startRun("EXTRACT_V2");
        succeed(v2);

        List<ExtractionRun> rows = runs.findAll().stream()
                .filter(r -> r.getDocument().getId().equals(document.getId()))
                .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(ExtractionRun::getPromptVersion))
                .containsExactlyInAnyOrder("EXTRACT_V1", "EXTRACT_V2");
    }

    @Test
    @DisplayName("three sequential reprocesses accumulate, latest wins nothing automatically")
    void sequentialReprocessesAccumulate() {
        for (String version : List.of("EXTRACT_V1", "EXTRACT_V2", "EXTRACT_V2")) {
            ExtractionRun run = startRun(version);
            succeed(run);
        }

        long count = runs.findAll().stream()
                .filter(r -> r.getDocument().getId().equals(document.getId()))
                .count();
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed run followed by a success keeps both rows")
    void failedThenSucceededKeepsBoth() {
        ExtractionRun bad = startRun("EXTRACT_V2");
        bad.markRunning(Instant.now());
        bad.markFailed("boom", Instant.now());
        runs.saveAndFlush(bad);

        ExtractionRun good = startRun("EXTRACT_V2");
        succeed(good);

        List<ExtractionRun> rows = runs.findAll().stream()
                .filter(r -> r.getDocument().getId().equals(document.getId()))
                .toList();
        assertThat(rows).hasSize(2);
    }

    // ---- single-flight ----------------------------------------------------------

    @Test
    @DisplayName("a second run while one is active is refused, not constraint-crashed")
    void activeRunBlocksSecondStart() {        startRun("EXTRACT_V2");

        assertThatThrownBy(() -> startRun("EXTRACT_V2"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already has an active extraction run");

        long pending = runs.findAll().stream()
                .filter(r -> r.getDocument().getId().equals(document.getId()))
                .count();
        assertThat(pending)
                .as("the refused start must not leave a second run row behind")
                .isEqualTo(1);
    }

    // ---- audit preservation -----------------------------------------------------

    @Test
    @DisplayName("two simultaneous claims elect exactly one worker")
    void concurrentClaimsElectOneWorker() throws Exception {
        com.prism.pipeline.BackgroundJob job = new com.prism.pipeline.BackgroundJob(
                "race-" + tag, com.prism.pipeline.BackgroundJob.Type.EXTRACTION,
                corpus, document, null, 5);
        final Long jobId = backgroundJobs.saveAndFlush(job).getId();

        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (jobService.claim(jobId).claimed()) {
                        wins.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Lost update / lock timeout counts as losing, never as winning.
                }
            });
            threads.add(t);
            t.start();
        }
        ready.await(10, java.util.concurrent.TimeUnit.SECONDS);
        go.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        assertThat(wins.get())
                .as("two simultaneous reprocesses must not create two active workers")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("automatic recovery never touches ABANDONED jobs")
    void recoveryLeavesAbandonedAlone() {
        com.prism.pipeline.BackgroundJob job = new com.prism.pipeline.BackgroundJob(
                "abandoned-" + tag, com.prism.pipeline.BackgroundJob.Type.EXTRACTION,
                corpus, document, null, 1);
        job.markRunning(java.time.Instant.now());
        job.markFailed("boom", java.time.Instant.now());
        backgroundJobs.saveAndFlush(job);
        assertThat(job.getStatus()).isEqualTo(com.prism.pipeline.BackgroundJob.Status.ABANDONED);

        recovery.requeueStaleJobs();

        com.prism.pipeline.BackgroundJob reloaded =
                backgroundJobs.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("ABANDONED waits for an explicit operator reprocess, not automation")
                .isEqualTo(com.prism.pipeline.BackgroundJob.Status.ABANDONED);
    }

    @Test
    @DisplayName("V1 and V2 quarantine rows stay attached to their own runs")
    void quarantineHistorySurvivesAcrossVersions() {
        ExtractionRun v1 = startRun("EXTRACT_V1");
        succeed(v1);
        ExtractionRun v2 = startRun("EXTRACT_V2");
        succeed(v2);

        quarantines.save(new ExtractionQuarantine(v1, document, chunk,
                "EXTRACT_V1", "m", "{\"triples\":[]}", QuarantineReason.MALFORMED_JSON,
                "bad json", 1));
        quarantines.save(new ExtractionQuarantine(v2, document, chunk,
                "EXTRACT_V2", "m", "{\"triples\":[]}", QuarantineReason.SEMANTIC_VALIDATION_FAILED,
                "unknown predicate 'develops'", 1));

        List<ExtractionQuarantine> rows = quarantines.findAll().stream()
                .filter(q -> q.getDocument().getId().equals(document.getId()))
                .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(q -> q.getRun().getId()))
                .containsExactlyInAnyOrder(v1.getId(), v2.getId());
    }

    // ---- proposals never self-approve -------------------------------------------

    @Test
    @DisplayName("fresh proposals are born unapproved")
    void newProposalsAreUnapproved() {
        Entity subject = entities.save(new Entity(corpus, "Meridian Group " + tag,
                "meridian group " + tag, "ORG", chunk.getId()));
        Entity object = entities.save(new Entity(corpus, "Aster Labs " + tag,
                "aster labs " + tag, "ORG", chunk.getId()));

        Triple triple = new Triple(corpus, subject, "Meridian Group", "funds",
                object, "Aster Labs", "Meridian Group funds Aster Labs.",
                chunk, verifier, "EXTRACT_V2", null, "k-" + tag, "h-" + tag);
        Claim claim = new Claim(corpus, "Meridian Group", "Meridian Group funds Aster Labs.",
                ClaimPolarity.POSITIVE, null, null, "Meridian Group funds Aster Labs.",
                chunk, verifier, "EXTRACT_V2", null, "ck-" + tag, "ch-" + tag);

        assertThat(triple.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PROPOSED);
    }
}
