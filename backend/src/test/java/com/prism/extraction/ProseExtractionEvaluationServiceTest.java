package com.prism.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.LlmProperties;
import com.prism.corpus.Corpus;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentChunkRepository;
import com.prism.knowledge.PredicateSemanticRegistry;
import com.prism.llm.LlmClient;
import com.prism.llm.RetryingLlmService;
import com.prism.trace.TraceRecorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the boundary between "we measured the pipeline" and "we measured a model".
 *
 * <p>Those are different claims and the first is much easier to make by accident.
 * A report generated against the offline fixture is arithmetically valid, carries
 * the configured model name in its metadata, and looks exactly like a model
 * result. Someone comparing two runs would see a model string and a number and
 * draw a conclusion.
 *
 * <p>So the harness refuses to produce a report at all when a real model is
 * required and the fixture is active. Not a warning in the body -- a 412 with the
 * token {@code REAL_MODEL_EXECUTION_REQUIRED}, so a CI job can fail on it and a
 * human cannot miss it.
 */
class ProseExtractionEvaluationServiceTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path GOLD = REPO.resolve("eval").resolve("prose-gold-v1.json");

    private static ObjectMapper mapper;
    private static ProseExtractionEvaluationService service;
    private static String goldJson;

    private static LlmProperties props(String provider) {
        return new LlmProperties(provider, "https://example.invalid/v1", "key",
                2, 5, 0.0, 1024, "m", "m", "m", "m", "m", 2, 1L);
    }

    @BeforeAll
    static void setUp() throws IOException {
        mapper = new ObjectMapper();
        goldJson = Files.readString(GOLD, StandardCharsets.UTF_8);
        LlmClient stub = mock(LlmClient.class);
        when(stub.providerName()).thenReturn("stub");
        service = new ProseExtractionEvaluationService(
                new RetryingLlmService(stub, props("fake"), mock(TraceRecorder.class)),
                stub,
                new ExtractionResultParser(mapper,
                        jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                        new com.prism.extraction.ExtractionValidator(new PredicateSemanticRegistry(),
                                tuningWithExtractionLimits())),
                props("fake"),
                mock(DocumentRepository.class),
                mock(DocumentChunkRepository.class),
                new PredicateSemanticRegistry(),
                mapper);
    }

    private static com.prism.config.PrismTuningProperties tuningWithExtractionLimits() {
        com.prism.config.PrismTuning.Extraction limits =
                mock(com.prism.config.PrismTuning.Extraction.class);
        when(limits.maxTriplesPerChunk()).thenReturn(40);
        when(limits.maxClaimsPerChunk()).thenReturn(40);
        com.prism.config.PrismTuningProperties tuning =
                mock(com.prism.config.PrismTuningProperties.class);
        when(tuning.extraction()).thenReturn(limits);
        return tuning;
    }

    // ---- the gold set parses and is substantial -----------------------------

    @Test
    @DisplayName("the prose gold set parses and is not trivially small")
    void goldSetParses() {
        var gold = service.parseGold(goldJson);

        assertThat(gold.datasetVersion()).isEqualTo("prose-gold-v1");
        assertThat(gold.documents()).isEqualTo(10);
        assertThat(gold.labelledSentences()).isGreaterThanOrEqualTo(45);
        assertThat(gold.expectedTriples()).isGreaterThanOrEqualTo(40);
        assertThat(gold.expectedClaims()).isGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("the negatives are what make precision measurable")
    void negativesArePresent() {
        var gold = service.parseGold(goldJson);

        // A prose set without negatives would make precision meaningless: nothing
        // would ever be a false positive. This asserts the property exists rather
        // than just the count, because the count is what someone would trim to
        // make a run faster.
        assertThat(gold.negativeSentences())
                .as("sentences labelled expectNothing")
                .isGreaterThanOrEqualTo(25);
        assertThat(gold.negativeSentences() * 100 / gold.labelledSentences())
                .as("negatives as a percentage of labelled sentences")
                .isGreaterThan(40);
    }

    @Test
    @DisplayName("a gold set with an unregistered predicate is refused, not scored")
    void unregisteredPredicateIsRefused() {
        String bad = """
                {"datasetVersion":"prose-gold-v1","documents":[
                  {"id":"X","fileName":"x.md","sentences":[
                    {"sentence":"A teleports_to B.","triples":[
                      {"subject":"A","predicate":"teleports_to","object":"B"}]}]}]}
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.parseGold(bad));
        // PRISM could never emit this triple, so scoring against it would report a
        // correct extractor as imprecise.
        assertThat(ex.getMessage()).contains("teleports_to").contains("unregistered");
    }

    @Test
    @DisplayName("an unknown claim polarity is refused")
    void unknownPolarityIsRefused() {
        String bad = """
                {"datasetVersion":"prose-gold-v1","documents":[
                  {"id":"X","fileName":"x.md","sentences":[
                    {"sentence":"A is B.","claims":[
                      {"subject":"A","claim":"A is B","polarity":"PROBABLY"}]}]}]}
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.parseGold(bad));
        assertThat(ex.getMessage()).contains("PROBABLY");
    }

    @Test
    @DisplayName("an empty or malformed gold set is refused rather than scored as perfect")
    void emptyOrMalformedGoldIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.parseGold("{\"datasetVersion\":\"v1\",\"documents\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> service.parseGold("not json at all"));
        assertThrows(IllegalArgumentException.class,
                () -> service.parseGold("{\"documents\":[]}"),
                "a gold set with no version is not a gold set this harness knows how to label");
    }

    // ---- the guard that matters ---------------------------------------------

    @Test
    @DisplayName("demanding a real model while the fixture is active fails with the token")
    void fixtureCannotSatisfyARealModelRequirement() {
        Corpus corpus = mock(Corpus.class);
        when(corpus.getId()).thenReturn(99L);
        when(corpus.getName()).thenReturn("Prose evaluation corpus");

        assertThatThrownBy(() -> service.evaluate(corpus, 1L, goldJson, true))
                .isInstanceOf(ProseExtractionEvaluationService.RealModelExecutionRequired.class)
                .hasMessageContaining(
                        ProseExtractionEvaluationService.RealModelExecutionRequired.TOKEN)
                .hasMessageContaining("LLM_PROVIDER=openai")
                .hasMessageContaining("LLM_API_KEY");
    }

    @Test
    @DisplayName("the token is stable, because CI matches on it")
    void tokenIsStable() {
        // A CI job that greps for this string is relying on it. Renaming it for
        // tidiness would silently turn a failing gate into a passing one.
        assertThat(ProseExtractionEvaluationService.RealModelExecutionRequired.TOKEN)
                .isEqualTo("REAL_MODEL_EXECUTION_REQUIRED");
    }

    @Test
    @DisplayName("the guard is scoped: without the requirement, fixture runs are allowed")
    void fixtureRunsAreAllowedWhenNoRealModelIsDemanded() {
        // The guard must not block pipeline validation. The offline provider is the
        // right tool for proving the harness works, and refusing it would leave the
        // harness untested until someone had credentials.
        //
        // The repository is mocked and returns no documents, so the run evaluates
        // nothing. That is sufficient: what is asserted is that no
        // RealModelExecutionRequired is raised, and that the report is explicitly
        // marked as test mode so the caller cannot mistake it for a model result.
        Corpus corpus = mock(Corpus.class);
        when(corpus.getId()).thenReturn(99L);
        when(corpus.getName()).thenReturn("Prose evaluation corpus");

        var report = service.evaluate(corpus, 1L, goldJson, false);

        assertThat(report).isNotNull();
        assertThat(report.testMode())
                .as("a fixture run must say so in the report, not only in the log")
                .isTrue();
        assertThat(report.provider()).isEqualTo("stub");
        assertThat(report.datasetLimitation()).isNotBlank();
        assertThat(report.chunksEvaluated())
                .as("the mocked repository holds no documents, so nothing was measured")
                .isZero();
    }

    // ---- the caveat travels --------------------------------------------------

    @Test
    @DisplayName("the dataset limitation is stated where it cannot be skipped")
    void limitationIsCarriedByTheHarness() {
        // The risk with this harness is that its figures are quoted as "extraction
        // accuracy on real documents". So the caveat is a constant the report
        // embeds, not prose in a document someone may not read.
        String limitation = ProseExtractionEvaluationService.DATASET_LIMITATION;
        assertThat(limitation).contains("NOT a statistically representative sample");
        assertThat(limitation).contains("upper bound");
    }

    @Test
    @DisplayName("totals are derived from the counts, and agree with them")
    void totalsAgreeWithCounts() {
        var counts = new ProseExtractionEvaluationService.Counts(7, 3, 4);
        var totals = ProseExtractionEvaluationService.Totals.of(counts);

        assertThat(totals.expected()).isEqualTo(11);   // 7 correct + 4 missed
        assertThat(totals.predicted()).isEqualTo(10);  // 7 correct + 3 incorrect
        assertThat(totals.correct()).isEqualTo(7);
        assertThat(totals.missed()).isEqualTo(4);
        assertThat(totals.incorrect()).isEqualTo(3);
    }

    @Test
    @DisplayName("an ungrounded refusal is reported separately from malformed output")
    void ungroundedIsDistinctFromMalformed() {
        // Two different problems for an operator: one is a transport or prompt
        // fault, the other is the model citing text it was not shown. Reporting
        // only the combined rate would hide a hallucination problem behind a
        // formatting one.
        var malformed = new ProseExtractionEvaluationService.ChunkOutcome(1L, "d", false,
                "MALFORMED_JSON", false, 2, 0, null, null, 1L, 1, "bad json");
        var ungrounded = new ProseExtractionEvaluationService.ChunkOutcome(2L, "d", false,
                "SEMANTIC_VALIDATION_FAILED", true, 2, 0, null, null, 1L, 1,
                "triple source sentence is not present in the chunk");

        assertThat(malformed.ungrounded()).isFalse();
        assertThat(ungrounded.ungrounded()).isTrue();
    }
}