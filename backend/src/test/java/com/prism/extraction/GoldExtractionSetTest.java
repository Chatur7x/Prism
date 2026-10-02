package com.prism.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gold set is only worth measuring against if its labels are true.
 *
 * <p>These tests make the labels checkable rather than a matter of opinion. Every
 * row records the document it came from and the sentence it was read from, and
 * each is asserted to exist verbatim in that document. A fabricated label, a
 * drifted corpus, or a transcription typo therefore fails the build instead of
 * quietly lowering a model's measured precision.
 *
 * <p>The gold set is FROZEN. Regenerating it from the corpus would defeat the
 * purpose: a set derived from whatever the corpus currently says cannot detect
 * that the corpus changed underneath a measurement.
 */
class GoldExtractionSetTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path GOLD = REPO.resolve("eval").resolve("gold-extraction.tsv");
    private static final Path CORPUS = REPO.resolve("demo").resolve("corpus");

    private static String goldText() throws IOException {
        return Files.readString(GOLD, StandardCharsets.UTF_8);
    }

    // ---- the labels are true ------------------------------------------------

    @Test
    @DisplayName("every gold sentence appears verbatim in the document it is attributed to")
    void labelsAreVerifiableAgainstTheCorpus() throws IOException {
        // The property that makes this a hand-checked set rather than a claim.
        // Nothing else in the harness would catch a fabricated label: a triple the
        // corpus never states would simply be scored as a miss, quietly
        // depressing recall and making the model look worse than it is.
        List<LlmExtractionEvaluationService.GoldTriple> gold =
                LlmExtractionEvaluationService.parseGold(goldText());
        assertFalse(gold.isEmpty(), "the gold set must not be empty");

        for (LlmExtractionEvaluationService.GoldTriple g : gold) {
            Path doc = CORPUS.resolve(g.document());
            assertTrue(Files.exists(doc), "gold row names a document that does not exist: " + doc);
            String body = Files.readString(doc, StandardCharsets.UTF_8);
            assertTrue(body.contains(g.sentence()),
                    "label is not supported by the source.\n  document: " + g.document()
                            + "\n  sentence: " + g.sentence()
                            + "\n  This label would score a correct extractor as imprecise.");
        }
    }

    @Test
    @DisplayName("every gold triple is exactly what its sentence states")
    void subjectPredicateObjectMatchTheSentence() throws IOException {
        // Guards against the three fields being transcribed inconsistently with
        // the sentence they came from -- e.g. a sentence naming one object and a
        // row naming another. Substring containment is the right check here
        // because the sentence IS the canonical rendering.
        for (LlmExtractionEvaluationService.GoldTriple g
                : LlmExtractionEvaluationService.parseGold(goldText())) {
            assertTrue(g.sentence().contains(g.subject()),
                    "subject missing from its sentence: " + g.sentence());
            assertTrue(g.sentence().contains(" " + g.predicate() + " "),
                    "predicate missing from its sentence: " + g.sentence());
            assertTrue(g.sentence().endsWith(g.object() + "."),
                    "object is not the sentence's final phrase: " + g.sentence());
        }
    }

    @Test
    @DisplayName("the gold set is frozen and non-trivial")
    void goldSetHasSubstance() throws IOException {
        List<LlmExtractionEvaluationService.GoldTriple> gold =
                LlmExtractionEvaluationService.parseGold(goldText());

        // Deliberately a floor rather than an exact count: adding a row to the
        // corpus should not fail this test, but emptying it must.
        assertTrue(gold.size() >= 90,
                "the gold set shrank to " + gold.size() + " rows; it must not be quietly reduced");

        Set<String> predicates = new LinkedHashSet<>();
        Set<String> documents = new LinkedHashSet<>();
        for (LlmExtractionEvaluationService.GoldTriple g : gold) {
            predicates.add(g.predicate());
            documents.add(g.document());
        }
        assertTrue(predicates.size() >= 10,
                "the set must span several predicates, found " + predicates.size());
        assertTrue(documents.size() >= 20,
                "the set must span most of the corpus, found " + documents.size());
    }

    @Test
    @DisplayName("the corpus document with no extractable fact is honestly unlabelled")
    void uninformativeDocumentHasNoLabels() throws IOException {
        // Document 24 states no canonical fact. If the gold set grew a row for it,
        // someone had invented a label, which is precisely what these tests exist
        // to prevent.
        for (LlmExtractionEvaluationService.GoldTriple g
                : LlmExtractionEvaluationService.parseGold(goldText())) {
            assertFalse(g.document().startsWith("24-"),
                    "document 24 deliberately states no extractable fact, but it is labelled: "
                            + g.sentence());
        }
    }

    // ---- the parser refuses bad labels --------------------------------------

    @Test
    @DisplayName("a label using an unregistered predicate is refused, not skipped")
    void unregisteredPredicateIsRefused() {
        // Such a row would score a correct extractor as imprecise, because PRISM
        // itself could never produce it. Failing loudly beats producing a metric
        // that is wrong for a reason nobody can see.
        String bad = "01-meridian-q1-board-memo.md\tMeridian Group\tteleports_to\tMars\t"
                + "Meridian Group teleports_to Mars.";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LlmExtractionEvaluationService.parseGold(bad));
        assertTrue(ex.getMessage().contains("not in the registry"), ex.getMessage());
        assertTrue(ex.getMessage().contains("teleports_to"), ex.getMessage());
    }

    @Test
    @DisplayName("a truncated row is refused with its line number")
    void malformedRowIsRefused() {
        String bad = "01-meridian-q1-board-memo.md\tMeridian Group\treports_to\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LlmExtractionEvaluationService.parseGold(bad));
        assertTrue(ex.getMessage().contains("expected 5"), ex.getMessage());
    }

    @Test
    @DisplayName("an empty gold set is refused rather than reported as perfect")
    void emptyGoldSetIsRefused() {
        // Without this, zero rows would produce zero counts, and a precision of
        // 1.0 over nothing -- which is how a harness ends up reporting a flawless
        // score for having measured nothing.
        assertThrows(IllegalArgumentException.class,
                () -> LlmExtractionEvaluationService.parseGold("# only a comment\n"));
    }

    // ---- metric arithmetic --------------------------------------------------

    @Test
    @DisplayName("counts derive precision, recall and F1 correctly")
    void metricArithmetic() {
        var perfect = new LlmExtractionEvaluationService.Counts(10, 0, 0);
        assertEquals(1.0, perfect.precision(), 1e-9);
        assertEquals(1.0, perfect.recall(), 1e-9);
        assertEquals(1.0, perfect.f1(), 1e-9);

        var half = new LlmExtractionEvaluationService.Counts(5, 5, 5);
        assertEquals(0.5, half.precision(), 1e-9);
        assertEquals(0.5, half.recall(), 1e-9);
        assertEquals(0.5, half.f1(), 1e-9);

        // Imbalanced: precision 1.0, recall 1/3, so F1 is 0.5 -- not the 0.667 an
        // average of the two would give. This is the case where the convention
        // actually changes the number.
        var lopsided = new LlmExtractionEvaluationService.Counts(1, 0, 2);
        assertEquals(1.0, lopsided.precision(), 1e-9);
        assertEquals(1.0 / 3, lopsided.recall(), 1e-9);
        assertEquals(0.5, lopsided.f1(), 1e-9);
    }

    @Test
    @DisplayName("a chunk with nothing predicted is a recall miss, not a divide-by-zero")
    void emptyPredictionCountsAsAMiss() {
        // A model that refuses every chunk must not score well. With zero
        // predictions, precision is vacuously 1, so recall has to carry the
        // penalty -- which it does here, and F1 collapses to 0.
        var refused = new LlmExtractionEvaluationService.Counts(0, 0, 7);
        assertEquals(1.0, refused.precision(), 1e-9);
        assertEquals(0.0, refused.recall(), 1e-9);
        assertEquals(0.0, refused.f1(), 1e-9);
    }

    // ---- the caveat travels with the number --------------------------------

    @Test
    @DisplayName("the corpus limitation is stated where it cannot be skipped")
    void caveatIsCarriedByTheHarness() {
        // The risk with this harness is that its figures are quoted as extraction
        // accuracy without the corpus caveat. So the caveat is a constant the
        // report embeds, not prose in a document someone may not read.
        String limitation = LlmExtractionEvaluationService.CORPUS_LIMITATION;
        assertTrue(limitation.contains("canonical"), limitation);
        assertTrue(limitation.contains("NOT extraction"), limitation);
        assertTrue(limitation.toLowerCase(Locale.ROOT).contains("do not quote"),
                limitation);
    }

    // ---- coverage, for the write-up ---------------------------------------

    @Test
    @DisplayName("restated facts are labelled once per document, and scored once")
    void restatedFactsAreScopedToTheirDocument() throws IOException {
        // The corpus deliberately restates facts in later memos, so 34 sentences
        // are labelled by two documents each. Scoring on sentence text alone
        // therefore credits one sentence against every document that contains
        // it, and the harness reported 103 true positives from a 99-row gold set
        // -- it inflated its own result.
        //
        // This asserts the property that makes the fix necessary, so that
        // removing the document scoping in LlmExtractionEvaluationService fails
        // here rather than quietly over-reporting quality forever.
        Map<String, List<String>> documentsPerSentence = new TreeMap<>();
        for (LlmExtractionEvaluationService.GoldTriple g
                : LlmExtractionEvaluationService.parseGold(goldText())) {
            documentsPerSentence
                    .computeIfAbsent(g.sentence().toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>())
                    .add(g.document());
        }
        long restated = documentsPerSentence.values().stream().filter(v -> v.size() > 1).count();
        assertTrue(restated > 0,
                "no sentence is attributed to two documents; if that changed, the document "
                        + "scoping in the harness may no longer be load-bearing and this "
                        + "test should be revisited");

        // No single sentence may be attributed to the same document twice, which
        // would inflate the gold count even with scoping.
        for (Map.Entry<String, List<String>> e : documentsPerSentence.entrySet()) {
            assertEquals(e.getValue().size(), Set.copyOf(e.getValue()).size(),
                    "the same sentence is labelled twice for one document: " + e.getKey());
        }
    }

    @Test
    @DisplayName("predicate coverage is recorded rather than padded")
    void coverageIsHonest() throws IOException {
        // 15 of 28 registered predicates are exercised. The other 13 are absent
        // because the corpus never states one. Padding the set with invented rows
        // for them would improve coverage on paper and be a lie about the corpus.
        Map<String, Integer> byPredicate = new TreeMap<>();
        for (LlmExtractionEvaluationService.GoldTriple g
                : LlmExtractionEvaluationService.parseGold(goldText())) {
            byPredicate.merge(g.predicate(), 1, Integer::sum);
        }
        assertEquals(15, byPredicate.size(), "predicate coverage changed: " + byPredicate);
        assertFalse(byPredicate.containsKey("advises"),
                "advises is registered but the corpus never states one; adding a row for it "
                        + "would be a fabricated label");
    }
}
