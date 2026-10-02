package com.prism.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.PrismTuning;
import com.prism.config.PrismTuningProperties;
import com.prism.knowledge.PredicateSemanticRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What happens to a response the parser cannot trust.
 *
 * <p>The whole premise of the extraction path is that a language model's output
 * is treated as untrusted input. A model that returns prose, truncated JSON, an
 * unknown key, or a triple it invented must not reach the graph. These tests
 * cover the gate, because a gate that has never been tested is an assumption.
 *
 * <p>The property that matters most is that every rejection is a
 * <b>rejection</b>. A parser that returns an empty-but-successful result on
 * malformed input would look identical to a model that legitimately found
 * nothing, and would quietly turn a provider outage into silent data loss. So
 * each case asserts {@code success()} is false <i>and</i> that a reason is
 * recorded, not merely that nothing came out.
 */
class MalformedResponseQuarantineTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static ExtractionResultParser parser;
    private static ExtractionValidator semanticValidator;

    /** Source text the parser grounds answers against. */
    private static final String SOURCE = "Meridian Group reports_to Northstar Holdings. "
            + "Orion Systems headquartered_in Corvale.";

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();

        // ExtractionValidator reads exactly two limits from tuning. Mocking the
        // record keeps this test about the parser gate rather than about the
        // shape of an eight-component configuration record, which has its own
        // binding test in the context tests.
        PrismTuning.Extraction limits = mock(PrismTuning.Extraction.class);
        when(limits.maxTriplesPerChunk()).thenReturn(40);
        when(limits.maxClaimsPerChunk()).thenReturn(40);
        PrismTuningProperties tuning = mock(PrismTuningProperties.class);
        when(tuning.extraction()).thenReturn(limits);

        semanticValidator = new ExtractionValidator(new PredicateSemanticRegistry(), tuning);
        parser = new ExtractionResultParser(new ObjectMapper(), validator, semanticValidator);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private static String validBody() {
        // ClaimDto is an object with subject/claim/polarity/sentence, not a bare
        // string, and polarity is POSITIVE/NEGATIVE/NEUTRAL. Notably it has no
        // verdict, confidence or status field, which is
        // what makes a verdict structurally unrepresentable from extraction.
        return """
                {"triples":[{"subject":"Meridian Group","predicate":"reports_to",
                  "object":"Northstar Holdings",
                  "sentence":"Meridian Group reports_to Northstar Holdings."}],
                 "claims":[{"subject":"Meridian Group",
                  "claim":"Meridian Group reports to Northstar Holdings.",
                  "polarity":"POSITIVE",
                  "sentence":"Meridian Group reports_to Northstar Holdings."}]}
                """;
    }

    private ExtractionResultParser.ParseOutcome parse(String raw) {
        return parser.parse(raw, SOURCE);
    }

    // ---- the gate holds -----------------------------------------------------

    @Test
    @DisplayName("a well-formed, grounded response is accepted")
    void validResponseIsAccepted() {
        ExtractionResultParser.ParseOutcome outcome = parse(validBody());

        assertThat(outcome.success())
                .as("valid grounded body was rejected: reason=%s message=%s",
                        outcome.reason(), outcome.message())
                .isTrue();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.message()).isNull();
        assertThat(outcome.result().triples()).hasSize(1);
    }

    @Test
    @DisplayName("an empty response is quarantined, not read as 'nothing found'")
    void emptyResponseIsQuarantined() {
        // The critical distinction. An empty string and a model that found no
        // facts both yield zero triples; only one of them is honest about it.
        ExtractionResultParser.ParseOutcome outcome = parse("");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(QuarantineReason.MALFORMED_JSON);
        assertThat(outcome.message()).isNotBlank();
        assertThat(outcome.result()).as("a rejected response must carry no result")
                .isNull();
    }

    @Test
    @DisplayName("a null response is quarantined rather than dereferenced")
    void nullResponseIsQuarantined() {
        ExtractionResultParser.ParseOutcome outcome = parse(null);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(QuarantineReason.MALFORMED_JSON);
    }

    @Test
    @DisplayName("prose instead of JSON is quarantined")
    void proseIsQuarantined() {
        ExtractionResultParser.ParseOutcome outcome = parse(
                "I could not find any relationships in that document, sorry for the delay.");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(QuarantineReason.MALFORMED_JSON);
    }

    @Test
    @DisplayName("truncated JSON is quarantined")
    void truncatedJsonIsQuarantined() {
        // What a token-limit cut looks like in practice: valid opening, no close.
        ExtractionResultParser.ParseOutcome outcome = parse(
                "{\"triples\":[{\"subject\":\"Meridian Group\",\"predicate\":\"reports_to\"");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(QuarantineReason.MALFORMED_JSON);
    }

    @Test
    @DisplayName("a JSON envelope carrying an unexpected key is refused")
    void unexpectedTopLevelKeyIsRefused() {
        // A closed schema is what stops a model smuggling fields past validation,
        // for instance a "verdict" or "confidence" the pipeline would then store
        // as if it were trustworthy.
        ExtractionResultParser.ParseOutcome outcome = parse("""
                {"triples":[],"claims":[],"verdict":"SUPPORTED","confidence":0.99}
                """);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(QuarantineReason.SCHEMA_VALIDATION_FAILED);
        assertThat(outcome.message()).contains("verdict");
    }

    @Test
    @DisplayName("a missing required field is refused by schema validation")
    void missingRequiredFieldIsRefused() {
        ExtractionResultParser.ParseOutcome outcome = parse("""
                {"triples":[{"subject":"Meridian Group","predicate":"reports_to"}]}
                """);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo(QuarantineReason.SCHEMA_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a triple whose sentence is absent from the source is refused")
    void ungroundedTripleIsRefused() {
        // The anti-hallucination gate. The model asserts a relationship and cites
        // a sentence; if that sentence is not in the text the model was shown, the
        // triple is fabricated and must not be stored.
        ExtractionResultParser.ParseOutcome outcome = parse("""
                {"triples":[{"subject":"Meridian Group","predicate":"acquires",
                  "object":"Northstar Holdings",
                  "sentence":"Meridian Group acquires Northstar Holdings in a hostile takeover."}],
                 "claims":[]}
                """);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason())
                .as("an ungrounded sentence is a semantic failure, not a syntax one")
                .isEqualTo(QuarantineReason.SEMANTIC_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("an unregistered predicate is refused")
    void unknownPredicateIsRefused() {
        // A relation outside the vocabulary cannot be adjudicated by the
        // contradiction detector, which only reasons over declared cardinalities.
        ExtractionResultParser.ParseOutcome outcome = parse("""
                {"triples":[{"subject":"Meridian Group","predicate":"teleports_to",
                  "object":"Northstar Holdings",
                  "sentence":"Meridian Group reports_to Northstar Holdings."}],
                 "claims":[]}
                """);

        assertThat(outcome.success()).isFalse();
    }

    @Test
    @DisplayName("every rejection names a reason an operator can act on")
    void everyRejectionCarriesAReason() {
        // A quarantine with no reason is a shrug. The reason is what tells an
        // operator whether to fix the endpoint, loosen the prompt, or accept it.
        String[] bad = {
                "",
                "   ",
                "not json at all",
                "{\"triples\":[]",
                "{\"triples\":[],\"claims\":[],\"unexpected\":1}",
                "{\"triples\":[{}]}",
        };
        for (String raw : bad) {
            ExtractionResultParser.ParseOutcome outcome = parse(raw);
            assertThat(outcome.success())
                    .as("must reject: <%s>", raw)
                    .isFalse();
            assertThat(outcome.reason())
                    .as("must name a reason for: <%s>", raw)
                    .isNotNull();
            assertThat(outcome.message())
                    .as("must explain itself for: <%s>", raw)
                    .isNotBlank();
            assertThat(outcome.tripleCount())
                    .as("a rejected response must not report triples for: <%s>", raw)
                    .isZero();
            assertThat(outcome.claimCount())
                    .as("a rejected response must not report claims for: <%s>", raw)
                    .isZero();
        }
    }

    @Test
    @DisplayName("a JSON array is rejected; the schema wants an object")
    void arrayEnvelopeIsRejected() {
        // A model that answers with a bare array is not a schema variant to be
        // forgiven; it is a different answer, and the pipeline has no meaning for
        // it.
        ExtractionResultParser.ParseOutcome outcome = parse("""
                [{"subject":"Meridian Group","predicate":"reports_to","object":"Northstar Holdings"}]
                """);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isNotNull();
    }
}