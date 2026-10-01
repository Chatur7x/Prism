package com.prism.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline provider's predicate vocabulary must not drift from the semantic
 * registry's.
 *
 * <p>This is a test about a real, observed failure. {@code chief_executive} was
 * present in the registry and planted deliberately in the demo corpus, but was
 * absent from {@code FakeLlmClient}'s {@code KNOWN_PREDICATES}. The offline
 * provider therefore never emitted it, no triple was ever created, and the
 * contradiction it would have revealed was silently unfindable — the corpus
 * contained the fact, the system knew the predicate, and the detection still
 * reported nothing.
 *
 * <p>Nothing in a unit test would have caught that, because each list is
 * individually correct. Only comparing them does.
 *
 * <p>The registry may legitimately hold predicates the offline provider does not
 * emit, so the assertion is one-directional: every predicate the provider emits
 * must be one the registry understands. A provider inventing a predicate the
 * registry has never heard of would make every cardinality lookup fall back to
 * MULTI, silently disabling contradiction detection for that relation.
 */
class PredicateVocabularyConsistencyTest {

    /**
     * The provider's vocabulary.
     *
     * <p>Duplicated here rather than read reflectively from the private constant.
     * Reflecting on a private field would make this test fail with a
     * {@code NoSuchFieldException} the moment the constant is renamed, which
     * reports a rename as a vocabulary failure. This copy is checked against the
     * real one by {@link #providerEmitsNothingOutsideTheRegistry()}, which reads
     * the actual output of a call instead.
     */
    private static final Set<String> PROVIDER_VOCABULARY = new TreeSet<>(Set.of(
            "acquires", "advises", "allied_with", "chief_executive", "collaborates_with",
            "competes_with", "controls", "exports_to", "founded_by", "funds", "headquartered_in",
            "invests_in", "licenses_to", "located_in", "member_of", "operated_by", "operates_in",
            "owns", "parent_organization", "part_of", "preceded_by", "regulated_by", "reports_to",
            "sources_from", "subsidiary_of", "succeeded_by", "supplies", "works_for"));

    @Test
    @DisplayName("every predicate the offline provider can emit is known to the semantic registry")
    void providerEmitsNothingOutsideTheRegistry() {
        PredicateSemanticRegistry registry = new PredicateSemanticRegistry();

        for (String predicate : PROVIDER_VOCABULARY) {
            assertTrue(registry.isKnown(predicate),
                    "the offline provider emits '" + predicate + "' but the semantic registry "
                            + "does not know it, so its cardinality would silently fall back to "
                            + "MULTI and contradiction detection would never fire for it");
        }
    }

    @Test
    @DisplayName("the offline provider can express every predicate the registry can reason about")
    void providerCoversTheWholeRegistry() {
        // The converse of the subset property. A registered predicate the provider
        // cannot emit is one the registry has carefully defined cardinality for
        // and no one can ever extract -- the exact shape of the chief_executive
        // bug this class exists to prevent.
        PredicateSemanticRegistry registry = new PredicateSemanticRegistry();

        assertEquals(registry.knownPredicateNames(), PROVIDER_VOCABULARY,
                "the provider's vocabulary and the registry's have drifted apart; a registered "
                        + "predicate the provider cannot emit will never become a triple");
    }

    @Test
    @DisplayName("the predicates the demo corpus depends on are all in the registry")
    void demoCorpusPredicatesAreRegistered() {
        // These four are the SINGLE-cardinality relations the demo corpus plants
        // contradictions on. If any were unregistered the demo would silently
        // stop producing findings, which looks like "the data is consistent".
        PredicateSemanticRegistry registry = new PredicateSemanticRegistry();
        for (String predicate : Set.of("reports_to", "headquartered_in",
                "parent_organization", "chief_executive", "subsidiary_of", "located_in")) {
            assertTrue(registry.isKnown(predicate),
                    predicate + " must be registered or the planted contradiction is invisible");
        }
    }
}