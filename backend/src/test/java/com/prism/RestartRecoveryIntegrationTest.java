package com.prism;

import com.prism.claims.Claim;
import com.prism.claims.ClaimPolarity;
import com.prism.claims.ClaimRepository;
import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import com.prism.contradiction.Contradiction;
import com.prism.contradiction.ContradictionFinding;
import com.prism.contradiction.ContradictionRepository;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusRepository;
import com.prism.debate.Argument;
import com.prism.debate.ArgumentRepository;
import com.prism.debate.ArgumentWeight;
import com.prism.debate.ArgumentWeightRepository;
import com.prism.debate.Debate;
import com.prism.debate.DebateRepository;
import com.prism.debate.DebateService;
import com.prism.debate.DebateState;
import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentStatus;
import com.prism.extraction.ExtractionRun;
import com.prism.extraction.ExtractionRunRepository;
import com.prism.extraction.ExtractionStatus;
import com.prism.knowledge.ApprovalService;
import com.prism.knowledge.Entity;
import com.prism.knowledge.EntityRepository;
import com.prism.knowledge.ProposalStatus;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import com.prism.pipeline.BackgroundJob;
import com.prism.pipeline.BackgroundJobRepository;
import com.prism.pipeline.BackgroundJobService;
import com.prism.pipeline.IngestionPipeline;
import com.prism.pipeline.RecoveryService;
import com.prism.user.Role;
import com.prism.user.User;
import com.prism.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What happens when the process dies mid-work, and what happens when a caller
 * repeats itself.
 *
 * <p>These are the failure-recovery scenarios that had tests for the provider
 * layer (timeouts, 429/5xx, retry bounds, quarantine) but nothing above it:
 * restart during extraction, duplicate submission, duplicate approval, duplicate
 * debate start, and concurrent debate advance. Each test below names the
 * incident it prevents.
 *
 * <p>Runs against a real MySQL container with Flyway migrations, because every
 * guarantee here is a database guarantee — a unique index, a conditional
 * {@code UPDATE ... WHERE state}, an in-place row reset — and asserting those
 * against H2 or a mock would prove nothing.
 *
 * <p>{@link IngestionPipeline} is mocked: the restart path dispatches requeued
 * jobs back into the pipeline, and this test asserts the dispatch without
 * running another extraction inside it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class RestartRecoveryIntegrationTest {

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
        // Personas run during debate start; the fake provider keeps them deterministic.
        registry.add("prism.llm.provider", () -> "fake");
    }

    @MockBean
    IngestionPipeline pipeline;

    @Autowired
    RecoveryService recovery;
    @Autowired
    BackgroundJobService jobService;
    @Autowired
    BackgroundJobRepository jobs;
    @Autowired
    ExtractionRunRepository extractionRuns;
    @Autowired
    UserRepository users;
    @Autowired
    CorpusRepository corpora;
    @Autowired
    DocumentRepository documents;
    @Autowired
    DocumentChunkRepository chunks;
    @Autowired
    TripleRepository triples;
    @Autowired
    EntityRepository entities;
    @Autowired
    ClaimRepository claims;
    @Autowired
    ContradictionRepository contradictions;
    @Autowired
    DebateService debateService;
    @Autowired
    DebateRepository debateRepo;
    @Autowired
    ArgumentRepository arguments;
    @Autowired
    ArgumentWeightRepository weights;
    @Autowired
    ApprovalService approvals;

    private User verifier;
    private Corpus corpus;
    private Document document;
    private DocumentChunk chunk;
    private Entity subjectEntity;
    private Entity objectEntity;
    private String tag;

    @BeforeEach
    void fixtures() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        verifier = users.save(new User("rec-" + tag, "rec-" + tag + "@example.com",
                "hash", Role.VERIFIER));
        corpus = corpora.save(new Corpus("rec-corpus-" + tag, "recovery fixtures", verifier));
        document = documents.save(new Document(corpus, verifier, "rec doc " + tag,
                "Meridian Group reports_to Northstar Holdings.", "rec-" + tag + ".md",
                "text/markdown", "hash-" + tag));
        chunk = chunks.save(new DocumentChunk(document, 0,
                "Meridian Group reports_to Northstar Holdings.", 0, 48, 10));
        subjectEntity = entities.save(new Entity(corpus, "Meridian Group " + tag,
                "meridian group " + tag, "ORG", chunk.getId()));
        objectEntity = entities.save(new Entity(corpus, "Northstar Holdings " + tag,
                "northstar holdings " + tag, "ORG", chunk.getId()));
    }

    private Triple pendingTriple() {
        return triples.save(new Triple(corpus, subjectEntity, "Meridian Group " + tag,
                "reports_to", objectEntity, "Northstar Holdings",
                "Meridian Group reports_to Northstar Holdings.",
                chunk, verifier, "EXTRACT_V1", null, "fact-" + tag + "-" + UUID.randomUUID(),
                "hash-" + UUID.randomUUID()));
    }

    private Contradiction openContradiction() {
        Triple left = pendingTriple();
        Triple right = triples.save(new Triple(corpus, subjectEntity,
                "Meridian Group " + tag, "reports_to", objectEntity, "Aster Labs",
                "Meridian Group reports_to Aster Labs.",
                chunk, verifier, "EXTRACT_V1", null, "fact-" + tag + "-" + UUID.randomUUID(),
                "hash-" + UUID.randomUUID()));
        String subject = "Meridian Group " + tag;
        ContradictionFinding finding = new ContradictionFinding(
                ContradictionFinding.TYPE_RELATION, subject, "reports_to",
                "left says Northstar", "right says Aster Labs",
                "RELATION_SINGLE_VALUE", "RULES_V1", "test fixture",
                left.getId(), right.getId(), null, null,
                ContradictionFinding.buildHash(ContradictionFinding.TYPE_RELATION,
                        subject, "reports_to", left.getId(), right.getId()));
        return contradictions.save(new Contradiction(corpus, finding, null));
    }

    // ---- duplicate extraction -------------------------------------------------

    @Test
    @DisplayName("submitting the same extraction twice creates one job, not two")
    void duplicateExtractionCreatesOneJob() {
        String key = "extract-" + tag;
        BackgroundJob first = jobService.enqueue(key, BackgroundJob.Type.EXTRACTION,
                corpus, document, "{}", 3);
        BackgroundJob second = jobService.enqueue(key, BackgroundJob.Type.EXTRACTION,
                corpus, document, "{}", 3);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jobs.findAll().stream().filter(j -> key.equals(j.getJobKey())).count())
                .isEqualTo(1);
    }

    // ---- restart during extraction --------------------------------------------

    @Test
    @DisplayName("a job orphaned RUNNING by a restart is requeued with attempts preserved")
    void staleRunningJobIsRequeued() {
        BackgroundJob job = jobService.enqueue("stale-" + tag, BackgroundJob.Type.EXTRACTION,
                corpus, document, "{}", 3);
        jobService.claim(job.getId());
        // Re-fetch: claim() committed its own transaction, so the reference above
        // carries a stale version. Mutating it would fail with an optimistic-lock
        // error that has nothing to do with the recovery path under test.
        BackgroundJob claimed = jobs.findById(job.getId()).orElseThrow();
        claimed.heartbeat(Instant.now().minusSeconds(3600));
        jobs.save(claimed);
        int attempts = jobs.findById(job.getId()).orElseThrow().getAttemptCount();

        int requeued = recovery.requeueStaleJobs();

        assertThat(requeued).isEqualTo(1);
        BackgroundJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BackgroundJob.Status.PENDING);
        assertThat(after.getAttemptCount())
                .as("a restart must not forgive attempts, or a crash loop retries forever")
                .isEqualTo(attempts);
        verify(pipeline).runJob(job.getId(), document.getId());
    }

    @Test
    @DisplayName("a freshly heartbeated job is not mistaken for an orphan")
    void freshRunningJobIsUntouched() {
        BackgroundJob job = jobService.enqueue("fresh-" + tag, BackgroundJob.Type.EXTRACTION,
                corpus, document, "{}", 3);
        jobService.claim(job.getId());
        clearInvocations(pipeline);

        assertThat(recovery.requeueStaleJobs()).isEqualTo(0);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(BackgroundJob.Status.RUNNING);
        verify(pipeline, never()).runJob(anyLong(), anyLong());
    }

    @Test
    @DisplayName("an orphaned job with no document fails instead of running forever")
    void orphanJobWithoutDocumentFails() {
        BackgroundJob orphan = jobs.save(new BackgroundJob("orphan-" + tag,
                BackgroundJob.Type.EXTRACTION, corpus, null, "{}", 3));
        jobService.claim(orphan.getId());
        // Re-fetch for the same reason as above: claim() committed separately.
        BackgroundJob claimedOrphan = jobs.findById(orphan.getId()).orElseThrow();
        claimedOrphan.heartbeat(Instant.now().minusSeconds(3600));
        jobs.save(claimedOrphan);
        clearInvocations(pipeline);

        recovery.requeueStaleJobs();

        assertThat(jobs.findById(orphan.getId()).orElseThrow().getStatus())
                .as("no document means nothing to requeue; RUNNING forever is the bug")
                .isEqualTo(BackgroundJob.Status.FAILED);
        verify(pipeline, never()).runJob(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a stale extraction run fails and the document surfaces its true state")
    void staleExtractionRunMarkedAndDocumentSurfaced() {
        document.transitionTo(DocumentStatus.EXTRACTING, Instant.now());
        documents.save(document);
        ExtractionRun run = extractionRuns.save(new ExtractionRun(document, 1,
                "EXTRACT_V1", "fake"));
        run.markRunning(Instant.now().minusSeconds(3600));
        run.heartbeat(Instant.now().minusSeconds(3600));
        extractionRuns.save(run);

        // A document that has since advanced must not regress.
        Document advanced = documents.save(new Document(corpus, verifier, "advanced " + tag,
                "text", "adv-" + tag + ".md", "text/markdown", "ahash-" + tag));
        advanced.transitionTo(DocumentStatus.READY, Instant.now());
        documents.save(advanced);
        ExtractionRun advancedRun = extractionRuns.save(new ExtractionRun(advanced, 1,
                "EXTRACT_V1", "fake"));
        advancedRun.markRunning(Instant.now().minusSeconds(3600));
        advancedRun.heartbeat(Instant.now().minusSeconds(3600));
        extractionRuns.save(advancedRun);

        int marked = recovery.markStaleExtractionRuns();

        assertThat(marked).isEqualTo(2);
        assertThat(extractionRuns.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(ExtractionStatus.FAILED);
        assertThat(documents.findById(document.getId()).orElseThrow().getStatus())
                .as("EXTRACTING with no live worker is a lie the UI would show forever")
                .isEqualTo(DocumentStatus.AWAITING_APPROVAL);
        assertThat(documents.findById(advanced.getId()).orElseThrow().getStatus())
                .as("a document that moved on must not be dragged back")
                .isEqualTo(DocumentStatus.READY);
    }

    // ---- duplicate approval ----------------------------------------------------

    @Test
    @DisplayName("a second approval is rejected as a conflict; the first decision stands")
    void duplicateApprovalIsRejected() {
        Triple triple = pendingTriple();

        assertThat(approvals.approveTriple(verifier.getId(), triple.getId(), "first")).isTrue();
        assertThatThrownBy(() -> approvals.approveTriple(verifier.getId(), triple.getId(), "second"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).code() == ErrorCode.STATE_CONFLICT,
                        "expected a 409 conflict");

        Triple after = triples.findById(triple.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(after.getDecidedBy().getId()).isEqualTo(verifier.getId());
        assertThat(after.getDecisionNote()).isEqualTo("first");
    }

    // ---- duplicate debate start -------------------------------------------------

    @Test
    @DisplayName("convening twice on one contradiction is a conflict, not a second council")
    void duplicateConveneIsRejected() {
        Contradiction contradiction = openContradiction();

        Debate first = debateService.convene(verifier.getId(), contradiction.getId(), "topic");
        // Either refusal is a 409: the contradiction is no longer OPEN (first
        // convene marked it IN_DEBATE), or the duplicate row is refused. Both
        // codes map to HTTP 409; what matters is one debate exists, not two.
        assertThatThrownBy(() -> debateService.convene(verifier.getId(), contradiction.getId(), "topic"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).code() == ErrorCode.CONFLICT
                        || ((ApiException) e).code() == ErrorCode.STATE_CONFLICT,
                        "expected a 409 conflict");

        assertThat(debateRepo.findAll().stream()
                .filter(d -> d.getContradiction().getId().equals(contradiction.getId()))
                .count()).isEqualTo(1);
        assertThat(first.getState()).isEqualTo(DebateState.CREATED);
    }

    @Test
    @DisplayName("starting twice is a conflict: round one runs exactly once")
    void duplicateStartIsRejected() {
        Debate debate = debateService.convene(verifier.getId(),
                openContradiction().getId(), "topic");

        Debate started = debateService.start(verifier.getId(), debate.getId());
        assertThat(started.getState()).isEqualTo(DebateState.AWAITING_CHAIR);
        // Refused by the FSM before any persona runs (ILLEGAL_STATE_TRANSITION),
        // which is also a 409. The duplicate never reaches the transition.
        assertThatThrownBy(() -> debateService.start(verifier.getId(), debate.getId()))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).code() == ErrorCode.STATE_CONFLICT
                        || ((ApiException) e).code() == ErrorCode.ILLEGAL_STATE_TRANSITION,
                        "expected a 409 conflict");

        // Strict: round one ran exactly once. A count merely greater than zero
        // would still pass if the duplicate start had executed a second round one.
        assertThat(debateService.roundsOf(debate.getId())).hasSize(1);
        long distinctRounds = arguments.findByDebateIdOrderByDebateRoundIdAscIdAsc(debate.getId())
                .stream().map(a -> a.getDebateRound().getId()).distinct().count();
        assertThat(distinctRounds)
                .as("every argument must belong to the single round-one execution")
                .isEqualTo(1);
    }

    // ---- concurrent debate advance -----------------------------------------------

    @Test
    @DisplayName("two simultaneous advances produce one transition and one conflict")
    void concurrentAdvanceHasSingleWinner() throws Exception {
        Debate debate = debateService.convene(verifier.getId(),
                openContradiction().getId(), "topic");
        debateService.start(verifier.getId(), debate.getId());

        List<Argument> roundArgs = arguments
                .findByDebateIdOrderByDebateRoundIdAscIdAsc(debate.getId()).stream()
                .filter(a -> !a.isFailed())
                .toList();
        assertThat(roundArgs).isNotEmpty();
        Debate loaded = debateRepo.findById(debate.getId()).orElseThrow();
        for (Argument argument : roundArgs) {
            weights.save(new ArgumentWeight(argument, loaded, 3, verifier, "probe weight"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        debateService.advance(verifier.getId(), debate.getId());
                        return "advanced";
                    } catch (ApiException e) {
                        return "conflict:" + e.code();
                    }
                }));
            }
            gate.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }

            long advanced = outcomes.stream().filter("advanced"::equals).count();
            long conflicted = outcomes.stream().filter(o -> o.startsWith("conflict:")).count();
            assertThat(advanced)
                    .as("exactly one advance must win, outcomes=" + outcomes)
                    .isEqualTo(1);
            assertThat(conflicted)
                    .as("exactly one advance must lose as a conflict, outcomes=" + outcomes)
                    .isEqualTo(1);
            assertThat(outcomes.stream().filter(o -> o.startsWith("conflict:"))
                    .allMatch(o -> o.contains("STATE_CONFLICT"))).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(debateRepo.findById(debate.getId()).orElseThrow().getCurrentRound())
                .as("one transition means exactly one new round")
                .isEqualTo(2);
    }

    private long argumentRoundCount(Long debateId, int round) {
        return arguments.findByDebateIdOrderByDebateRoundIdAscIdAsc(debateId).stream()
                .filter(a -> a.getDebateRound() != null
                        && a.getDebateRound().getRoundNumber() == round)
                .count();
    }

    // ---- machine verdict preservation (provenance invariant) -----------------------

    @Test
    @DisplayName("adjudication never rewrites the recorded machine verdict")
    void adjudicationPreservesMachineVerdict() {
        Claim claim = claims.save(new Claim(corpus, "Meridian Group " + tag,
                "Meridian Group funds Aster Labs.", ClaimPolarity.POSITIVE, "funds",
                "Aster Labs", "Meridian Group funds Aster Labs.", chunk, verifier,
                "EXTRACT_V1", null, "claim-" + tag, "chash-" + tag));
        com.prism.claims.Verdict verdict = new com.prism.claims.Verdict(claim,
                com.prism.claims.VerdictType.CONTRADICTED,
                com.prism.claims.EvidenceStatus.EVIDENCE_FOUND,
                0.8, 0.1, 0.7, "two sources disagree", "machine reasoning",
                "fake", "VERIFY_V1", "RULES_V1", "funds Aster", null);
        verdict.adjudicate(verifier, com.prism.claims.VerdictType.SUPPORTED,
                "re-read the source", Instant.now());

        assertThat(verdict.getMachineVerdictType())
                .isEqualTo(com.prism.claims.VerdictType.CONTRADICTED);
        assertThat(verdict.getVerdictType()).isEqualTo(com.prism.claims.VerdictType.SUPPORTED);
        assertThat(verdict.wasOverridden()).isTrue();
    }
}
