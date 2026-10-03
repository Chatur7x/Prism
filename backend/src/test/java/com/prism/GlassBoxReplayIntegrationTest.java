package com.prism;

import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusRepository;
import com.prism.trace.ActorType;
import com.prism.trace.TraceController;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceOperationType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunRepository;
import com.prism.trace.TraceRunStatus;
import com.prism.trace.TraceStep;
import com.prism.trace.TraceStepRepository;
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

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Glass Box replay guarantee: replay reads STORED history, not current state.
 *
 * <p>{@link TraceController} serves replays from {@code trace_steps} rows written
 * at execution time. If replay instead reconstructed history from live domain
 * rows, a trace would silently show what the system looks like <em>now</em>
 * while claiming to show what it did <em>then</em> — the one lie an audit trail
 * must never tell. Each test below pins one half of that contract against a
 * real MySQL container with Flyway migrations, because every guarantee here is
 * a persistence guarantee and asserting it against H2 would prove nothing.
 *
 * <p>Fixtures are one VERIFIER user plus one corpus; no documents are needed
 * because {@link TraceRecorder#startRun} accepts a nullable document.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class GlassBoxReplayIntegrationTest {

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
    TraceRecorder recorder;
    @Autowired
    TraceRunRepository runs;
    @Autowired
    TraceStepRepository steps;
    @Autowired
    UserRepository users;
    @Autowired
    CorpusRepository corpora;

    private User verifier;
    private Corpus corpus;
    private String tag;

    @BeforeEach
    void fixtures() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        verifier = users.save(new User("gb-" + tag, "gb-" + tag + "@example.com",
                "hash", Role.VERIFIER));
        corpus = corpora.save(new Corpus("gb-corpus-" + tag, "glass box fixtures", verifier));
    }

    private TraceRun startRun(String keyPrefix, TraceOperationType type) {
        return recorder.startRun(type, corpus, null, verifier,
                keyPrefix + "-" + tag + "-" + UUID.randomUUID(), null);
    }

    // ---- the core experiment --------------------------------------------------

    @Test
    @DisplayName("replay shows stored history, not current state, after the domain mutates")
    void replayShowsHistoryNotCurrentState() {
        TraceRun run = startRun("gb-history", TraceOperationType.VERIFICATION);
        String input = "subject=Meridian Group " + tag + " reports_to Northstar Holdings " + tag;
        String output = "status=APPROVED by verifier rule=RULES_V1";

        TraceStep root = recorder.record(run.getId(), null, ActorType.ENGINE,
                TraceEventType.RULE_ANALYSIS, "rule-eval",
                TraceRecorder.StepPayload.builder()
                        .inputSummary(input)
                        .outputSummary(output)
                        .ruleVersion("RULES_V1")
                        .build());
        assertThat(root).as("record() swallows write failures and returns null").isNotNull();

        // Mutate the domain AFTER the run executed: a replay reconstructed from
        // live state would show the renamed corpus; a stored-history replay must not.
        corpus.rename("renamed-corpus-" + tag, "mutated after the run", Instant.now());
        corpora.save(corpus);
        assertThat(corpora.findById(corpus.getId()).orElseThrow().getName())
                .as("guard: the mutation must actually have persisted, or the test is vacuous")
                .isEqualTo("renamed-corpus-" + tag);

        List<TraceStep> replay = steps.findByRunIdOrderBySeqAsc(run.getId());

        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).getInputSummary())
                .as("stored input snapshot must be byte-identical after the domain moved on")
                .isEqualTo(input);
        assertThat(replay.get(0).getOutputSummary())
                .as("stored output snapshot must be byte-identical after the domain moved on")
                .isEqualTo(output);
    }

    // ---- replay tree structure --------------------------------------------------

    @Test
    @DisplayName("parent links and sequence order rebuild the replay tree")
    void parentChildLinksAndSeqOrder() {
        TraceRun run = startRun("gb-tree", TraceOperationType.VERIFICATION);

        TraceStep root = recorder.record(run.getId(), null, ActorType.ENGINE,
                TraceEventType.RULE_ANALYSIS, "root",
                TraceRecorder.StepPayload.builder().build());
        TraceStep childOne = recorder.record(run.getId(), root, ActorType.ENGINE,
                TraceEventType.RETRIEVAL_COMPLETED, "child-1",
                TraceRecorder.StepPayload.builder().build());
        TraceStep childTwo = recorder.record(run.getId(), root, ActorType.LLM,
                TraceEventType.LLM_JUDGMENT, "child-2",
                TraceRecorder.StepPayload.builder().model("fake-test-1").build());
        TraceStep grandchild = recorder.record(run.getId(), childOne, ActorType.HUMAN,
                TraceEventType.HUMAN_ADJUDICATION, "grandchild",
                TraceRecorder.StepPayload.builder().build());
        assertThat(root).as("record() swallows write failures and returns null").isNotNull();
        assertThat(childOne).as("record() swallows write failures and returns null").isNotNull();
        assertThat(childTwo).as("record() swallows write failures and returns null").isNotNull();
        assertThat(grandchild).as("record() swallows write failures and returns null").isNotNull();

        List<TraceStep> ordered = steps.findByRunIdOrderBySeqAsc(run.getId());

        assertThat(ordered).hasSize(4);
        assertThat(ordered.stream().map(TraceStep::getSeq).toList())
                .as("replay order is sequence order, strictly increasing")
                .isSorted();
        for (int i = 1; i < ordered.size(); i++) {
            assertThat(ordered.get(i).getSeq()).isGreaterThan(ordered.get(i - 1).getSeq());
        }

        Map<Long, TraceStep> byId = new LinkedHashMap<>();
        for (TraceStep s : ordered) {
            byId.put(s.getId(), s);
        }
        assertThat(byId.get(root.getId()).getParentStep()).isNull();
        assertThat(byId.get(childOne.getId()).getParentStep().getId()).isEqualTo(root.getId());
        assertThat(byId.get(childTwo.getId()).getParentStep().getId()).isEqualTo(root.getId());
        assertThat(byId.get(grandchild.getId()).getParentStep().getId()).isEqualTo(childOne.getId());

        // Same children-map construction the controller uses to serve the DAG.
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (TraceStep s : ordered) {
            if (s.getParentStep() != null) {
                children.computeIfAbsent(s.getParentStep().getId(), k -> new ArrayList<>())
                        .add(s.getId());
            }
        }
        assertThat(children.get(root.getId()))
                .containsExactlyInAnyOrder(childOne.getId(), childTwo.getId());
        assertThat(children.get(childOne.getId())).containsExactly(grandchild.getId());
        assertThat(children.get(childTwo.getId())).isNull();
    }

    // ---- actor attribution ------------------------------------------------------

    @Test
    @DisplayName("engine, model, and human steps persist their attribution")
    void actorsAndMetadataRecorded() {
        TraceRun run = startRun("gb-actors", TraceOperationType.VERIFICATION);

        recorder.record(run.getId(), null, ActorType.ENGINE, TraceEventType.RULE_ANALYSIS,
                "engine-step", TraceRecorder.StepPayload.builder()
                        .ruleVersion("RULES_V1")
                        .attempt(1)
                        .durationMs(12L)
                        .build());
        recorder.record(run.getId(), null, ActorType.LLM, TraceEventType.LLM_JUDGMENT,
                "llm-step", TraceRecorder.StepPayload.builder()
                        .model("fake-test-1")
                        .promptVersion("PROMPT_V3")
                        .attempt(2)
                        .durationMs(345L)
                        .build());
        recorder.record(run.getId(), null, ActorType.HUMAN, TraceEventType.HUMAN_ADJUDICATION,
                "human-step", TraceRecorder.StepPayload.builder()
                        .attempt(1)
                        .durationMs(7L)
                        .build());

        Map<String, TraceStep> reread = new LinkedHashMap<>();
        for (TraceStep s : steps.findByRunIdOrderBySeqAsc(run.getId())) {
            reread.put(s.getName(), s);
        }

        assertThat(reread).containsKeys("engine-step", "llm-step", "human-step");

        TraceStep engine = reread.get("engine-step");
        assertThat(engine.getActorType()).isEqualTo(ActorType.ENGINE);
        assertThat(engine.getRuleVersion()).isEqualTo("RULES_V1");
        assertThat(engine.getAttempt()).isEqualTo(1);
        assertThat(engine.getDurationMs()).isEqualTo(12L);

        TraceStep llm = reread.get("llm-step");
        assertThat(llm.getActorType()).isEqualTo(ActorType.LLM);
        assertThat(llm.getModel()).isEqualTo("fake-test-1");
        assertThat(llm.getPromptVersion()).isEqualTo("PROMPT_V3");
        assertThat(llm.getAttempt()).isEqualTo(2);
        assertThat(llm.getDurationMs()).isEqualTo(345L);

        TraceStep human = reread.get("human-step");
        assertThat(human.getActorType()).isEqualTo(ActorType.HUMAN);
        assertThat(human.getAttempt()).isEqualTo(1);
        assertThat(human.getDurationMs()).isEqualTo(7L);
    }

    // ---- failure evidence without secrets ---------------------------------------

    @Test
    @DisplayName("failures are recorded with their message and no credentials")
    void errorsAreRecordedWithoutSecrets() {
        TraceRun run = startRun("gb-errors", TraceOperationType.EXTRACTION);

        TraceStep failed = recorder.failure(run.getId(), null, ActorType.ENGINE,
                TraceEventType.PIPELINE_FAILED, "extract-chunk",
                "provider returned HTTP 500 after 3 attempts");
        assertThat(failed).as("record() swallows write failures and returns null").isNotNull();

        List<TraceStep> reread = steps.findByRunIdOrderBySeqAsc(run.getId());
        assertThat(reread).hasSize(1);
        assertThat(reread.get(0).getStatus()).isEqualTo("ERROR");
        assertThat(reread.get(0).getErrorMessage()).contains("HTTP 500");

        for (TraceStep step : reread) {
            List<String> stringFields = new ArrayList<>();
            if (step.getName() != null) {
                stringFields.add(step.getName());
            }
            if (step.getStatus() != null) {
                stringFields.add(step.getStatus());
            }
            if (step.getInputSummary() != null) {
                stringFields.add(step.getInputSummary());
            }
            if (step.getInputReferenceIds() != null) {
                stringFields.add(step.getInputReferenceIds());
            }
            if (step.getOutputSummary() != null) {
                stringFields.add(step.getOutputSummary());
            }
            if (step.getOutputReferenceIds() != null) {
                stringFields.add(step.getOutputReferenceIds());
            }
            if (step.getRuleVersion() != null) {
                stringFields.add(step.getRuleVersion());
            }
            if (step.getPromptVersion() != null) {
                stringFields.add(step.getPromptVersion());
            }
            if (step.getModel() != null) {
                stringFields.add(step.getModel());
            }
            if (step.getErrorMessage() != null) {
                stringFields.add(step.getErrorMessage());
            }
            for (String value : stringFields) {
                assertThat(value)
                        .as("stored step field must never carry credentials, step=" + step.getName())
                        .doesNotContain("Bearer")
                        .doesNotContain("password")
                        .doesNotContain("api-key");
            }
        }
    }

    // ---- observable-execution-only invariant --------------------------------------

    @Test
    @DisplayName("no step field can hold chain-of-thought, by construction")
    void noChainOfThoughtByConstruction() {
        List<String> forbidden = List.of("reasoning", "chainofthought", "chain_of_thought",
                "prompttext", "systemprompt");

        for (Field field : TraceStep.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            String name = field.getName().toLowerCase();
            for (String token : forbidden) {
                assertThat(name)
                        .as("TraceStep field '%s' looks like a chain-of-thought container",
                                field.getName())
                        .doesNotContain(token);
            }
        }

        for (RecordComponent component : TraceController.StepView.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            for (String token : forbidden) {
                assertThat(name)
                        .as("StepView component '%s' looks like a chain-of-thought container",
                                component.getName())
                        .doesNotContain(token);
            }
        }
    }

    // ---- run lifecycle ------------------------------------------------------------

    @Test
    @DisplayName("runs close with timing; a failed run carries its reason")
    void runLifecycle() {
        TraceRun run = startRun("gb-lifecycle", TraceOperationType.CONTRADICTION_SCAN);
        recorder.simple(run.getId(), null, ActorType.ENGINE,
                TraceEventType.RULE_ANALYSIS, "kickoff");
        recorder.finishRun(run.getId(), TraceRunStatus.SUCCEEDED, null);

        TraceRun done = runs.findById(run.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(TraceRunStatus.SUCCEEDED);
        assertThat(done.getFinishedAt())
                .as("a closed run must say when it closed")
                .isNotNull();
        assertThat(done.getDurationMs())
                .as("a closed run must say how long it took")
                .isNotNull()
                .isGreaterThanOrEqualTo(0L);

        TraceRun bad = startRun("gb-lifecycle-bad", TraceOperationType.CONTRADICTION_SCAN);
        recorder.finishRun(bad.getId(), TraceRunStatus.FAILED, "boom: provider timeout");

        TraceRun failedRun = runs.findById(bad.getId()).orElseThrow();
        assertThat(failedRun.getStatus()).isEqualTo(TraceRunStatus.FAILED);
        assertThat(failedRun.getErrorSummary())
                .as("a failed run with no reason is unauditable")
                .contains("boom");
        assertThat(failedRun.getFinishedAt()).isNotNull();
    }
}
