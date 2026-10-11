package com.prism.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.PrismTuning;
import com.prism.config.PrismTuningProperties;
import com.prism.knowledge.PredicateSemanticRegistry;
import com.prism.llm.Prompts;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the V2 extraction-prompt repair for the production incident where
 * document 63 ("Introduction to Artificial Intelligence") produced four
 * quarantines: three for invented predicates (is_a, researches, develops)
 * and one empty reply.
 *
 * <p>V1 illustrated the predicate with eight examples ("such as reports_to,
 * controls, ..."), which reads as permission to generalize. V2 teaches the
 * closed list from the authoritative registry. These tests pin that the
 * prompt carries the registry (no drift between teacher and validator) and
 * that the validator still refuses anything outside it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtractionPromptTest {

    private static final String SOURCE =
            "OpenAI develops artificial intelligence models. Meridian Group funds Aster Labs.";

    private PredicateSemanticRegistry registry;
    private ExtractionResultParser parser;
    private ValidatorFactory factory;

    @BeforeAll
    void setUp() {
        registry = new PredicateSemanticRegistry();
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        PrismTuning.Extraction limits = mock(PrismTuning.Extraction.class);
        when(limits.maxTriplesPerChunk()).thenReturn(40);
        when(limits.maxClaimsPerChunk()).thenReturn(40);
        PrismTuningProperties tuning = mock(PrismTuningProperties.class);
        when(tuning.extraction()).thenReturn(limits);
        ExtractionValidator semanticValidator = new ExtractionValidator(registry, tuning);
        parser = new ExtractionResultParser(new ObjectMapper(), validator, semanticValidator);
    }

    @AfterAll
    void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("V2 teaches every registered predicate by name")
    void v2TeachesTheWholeRegistry() {
        Set<String> names = registry.knownPredicateNames();
        assertThat(names).as("registry must not be empty").isNotEmpty();

        String prompt = Prompts.extractSystem(names);
        for (String name : names) {
            assertThat(prompt)
                    .as("V2 prompt must teach registered predicate '%s'", name)
                    .contains(name);
        }
    }

    @Test
    @DisplayName("V2 forbids invention and differs from V1 exactly where intended")
    void v2ForbidsInvention() {
        Set<String> names = registry.knownPredicateNames();
        String v2 = Prompts.extractSystem(names);

        assertThat(v2).contains("do NOT emit a triple");
        assertThat(v2).contains("Allowed predicates:");
        assertThat(Prompts.extractSystem())
                .as("V1 must stay untouched so historical runs keep reading correctly")
                .doesNotContain("Allowed predicates:");
    }

    @Test
    @DisplayName("the doc-63 predicate 'develops' is still refused by validation")
    void inventedPredicateIsRefused() {
        String body = """
                {"triples":[{"subject":"OpenAI","predicate":"develops",
                  "object":"artificial intelligence models",
                  "sentence":"OpenAI develops artificial intelligence models."}],
                 "claims":[]}""";

        ExtractionResultParser.ParseOutcome outcome = parser.parse(body, SOURCE);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("unknown predicate 'develops'");
    }

    @Test
    @DisplayName("a registered predicate from the same sentence is accepted")
    void registeredPredicateIsAccepted() {
        String body = """
                {"triples":[{"subject":"Meridian Group","predicate":"funds",
                  "object":"Aster Labs",
                  "sentence":"Meridian Group funds Aster Labs."}],
                 "claims":[]}""";

        ExtractionResultParser.ParseOutcome outcome = parser.parse(body, SOURCE);

        assertThat(outcome.success())
                .as("registered predicate was rejected: %s", outcome.message())
                .isTrue();
    }
}
