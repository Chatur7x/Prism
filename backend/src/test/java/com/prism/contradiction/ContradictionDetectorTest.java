package com.prism.contradiction;

import com.prism.claims.ClaimPolarity;
import com.prism.knowledge.PredicateSemanticRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionDetectorTest {

    private final ContradictionDetector detector =
            new ContradictionDetector(new PredicateSemanticRegistry());

    private static ContradictionDetector.ApprovedTriple triple(Long id, String subject,
                                                               String subjectKey, String predicate, String object) {
        return new ContradictionDetector.ApprovedTriple(id, subject, predicate, object, subjectKey, null,
                "doc-" + id);
    }

    private static ContradictionDetector.ApprovedTriple tripleAt(Long id, String subject, String subjectKey,
                                                                 String predicate, String object, String time) {
        return new ContradictionDetector.ApprovedTriple(id, subject, predicate, object, subjectKey, time,
                "doc-" + id);
    }

    // ---- relation conflicts -----------------------------------------------

    @Test
    @DisplayName("two objects for a single-valued predicate IS a conflict")
    void singleValuedRelationConflicts() {
        List<ContradictionFinding> findings = detector.detectRelationConflicts(List.of(
                triple(1L, "Orion Systems", "orion systems", "reports_to", "Northstar Holdings"),
                triple(2L, "Orion Systems", "orion systems", "reports_to", "Meridian Group")));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(ContradictionFinding.TYPE_RELATION);
        assertThat(findings.get(0).ruleCode())
                .isEqualTo(ContradictionDetector.RULE_RELATION_SINGLE_VALUE);
    }

    @Test
    @DisplayName("two objects for a many-valued predicate is NOT a conflict")
    void multiValuedRelationDoesNotConflict() {
        List<ContradictionFinding> findings = detector.detectRelationConflicts(List.of(
                triple(1L, "Meridian Group", "meridian group", "funds", "Aster Labs"),
                triple(2L, "Meridian Group", "meridian group", "funds", "Orion Systems"),
                triple(3L, "Meridian Group", "meridian group", "funds", "Helix Consortium")));

        assertThat(findings)
                .as("funds is MULTI: multiple grantees are ordinary, not contradictory")
                .isEmpty();
    }

    @Test
    @DisplayName("allied_with accepts many partners")
    void symmetricMultiValuedRelation() {
        assertThat(detector.detectRelationConflicts(List.of(
                triple(1L, "Aster Labs", "aster labs", "allied_with", "Orion Systems"),
                triple(2L, "Aster Labs", "aster labs", "allied_with", "Helix Consortium")))).isEmpty();
    }

    @Test
    @DisplayName("an unknown predicate never produces a relation conflict")
    void unknownPredicateIsPermissive() {
        assertThat(detector.detectRelationConflicts(List.of(
                triple(1L, "Meridian Group", "meridian group", "vaguely_relates_to", "Aster Labs"),
                triple(2L, "Meridian Group", "meridian group", "vaguely_relates_to", "Orion Systems"))))
                .as("failing toward 'no contradiction' avoids manufacturing disputes")
                .isEmpty();
    }

    @Test
    @DisplayName("the same object asserted twice is agreement, not conflict")
    void identicalObjectsAgree() {
        assertThat(detector.detectRelationConflicts(List.of(
                triple(1L, "Orion Systems", "orion systems", "reports_to", "Northstar Holdings"),
                triple(2L, "Orion Systems", "orion systems", "reports_to", "Northstar Holdings"))))
                .isEmpty();
    }

    @Test
    @DisplayName("a temporal relation with different effective times is a change, not a conflict")
    void temporalRelationWithDifferentTimes() {
        assertThat(detector.detectRelationConflicts(List.of(
                tripleAt(1L, "Aster Labs", "aster labs", "located_in", "Geneva", "2019"),
                tripleAt(2L, "Aster Labs", "aster labs", "located_in", "Zurich", "2025"))))
                .as("relocation over time is not a simultaneous contradiction")
                .isEmpty();
    }

    @Test
    @DisplayName("a temporal relation with the SAME time is a real conflict")
    void temporalRelationSameTime() {
        List<ContradictionFinding> findings = detector.detectRelationConflicts(List.of(
                tripleAt(1L, "Aster Labs", "aster labs", "located_in", "Geneva", "2025"),
                tripleAt(2L, "Aster Labs", "aster labs", "located_in", "Zurich", "2025")));
        assertThat(findings).hasSize(1);
    }

    @Test
    @DisplayName("a bounded relation conflicts only once the bound is exceeded")
    void boundedRelationRespectsItsLimit() {
        assertThat(detector.detectRelationConflicts(List.of(
                triple(1L, "Orion Systems", "orion systems", "sources_from", "Supplier A"),
                triple(2L, "Orion Systems", "orion systems", "sources_from", "Supplier B"),
                triple(3L, "Orion Systems", "orion systems", "sources_from", "Supplier C"))))
                .as("sources_from allows 3 suppliers, so 3 is legal")
                .isEmpty();

        assertThat(detector.detectRelationConflicts(List.of(
                triple(1L, "Orion Systems", "orion systems", "sources_from", "Supplier A"),
                triple(2L, "Orion Systems", "orion systems", "sources_from", "Supplier B"),
                triple(3L, "Orion Systems", "orion systems", "sources_from", "Supplier C"),
                triple(4L, "Orion Systems", "orion systems", "sources_from", "Supplier D"))))
                .as("4 exceeds the bound of 3")
                .isNotEmpty();
    }

    @Test
    @DisplayName("different subjects never conflict with each other")
    void differentSubjectsDoNotConflict() {
        assertThat(detector.detectRelationConflicts(List.of(
                triple(1L, "Orion Systems", "orion systems", "reports_to", "Northstar Holdings"),
                triple(2L, "Aster Labs", "aster labs", "reports_to", "Meridian Group"))))
                .isEmpty();
    }

    // ---- polarity conflicts ------------------------------------------------

    @Test
    @DisplayName("opposite polarity on the same relation is a conflict")
    void polarityConflict() {
        List<ContradictionDetector.ApprovedClaim> claims = List.of(
                new ContradictionDetector.ApprovedClaim(1L, "Aster Labs", "aster labs", "acquires",
                        "Orion Systems", ClaimPolarity.POSITIVE,
                        "Aster Labs acquired Orion Systems.", null, "doc-1"),
                new ContradictionDetector.ApprovedClaim(2L, "Aster Labs", "aster labs", "acquires",
                        "Orion Systems", ClaimPolarity.NEGATIVE,
                        "Aster Labs did not acquire Orion Systems.", null, "doc-2"));

        List<ContradictionFinding> findings = detector.detectPolarityConflicts(claims);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(ContradictionFinding.TYPE_POLARITY);
    }

    @Test
    @DisplayName("matching polarity is not a conflict")
    void matchingPolarityAgrees() {
        assertThat(detector.detectPolarityConflicts(List.of(
                new ContradictionDetector.ApprovedClaim(1L, "Aster Labs", "aster labs", "acquires",
                        "Orion Systems", ClaimPolarity.POSITIVE, "Acquired.", null, "d1"),
                new ContradictionDetector.ApprovedClaim(2L, "Aster Labs", "aster labs", "acquires",
                        "Orion Systems", ClaimPolarity.POSITIVE, "Also acquired.", null, "d2"))))
                .isEmpty();
    }

    @Test
    @DisplayName("a claim without a predicate yields no polarity finding")
    void noPredicateNoFinding() {
        assertThat(detector.detectPolarityConflicts(List.of(
                new ContradictionDetector.ApprovedClaim(1L, "Aster Labs", "aster labs", null, null,
                        ClaimPolarity.POSITIVE, "Something happened.", null, "d1"),
                new ContradictionDetector.ApprovedClaim(2L, "Aster Labs", "aster labs", null, null,
                        ClaimPolarity.NEGATIVE, "Something did not happen.", null, "d2"))))
                .as("without a predicate there is no way to tell the claims are about the same fact")
                .isEmpty();
    }

    // ---- verdict conflicts -------------------------------------------------

    @Test
    @DisplayName("a CONTRADICTED verdict on a matching claim conflicts with its approved triple")
    void verdictConflictsWithTriple() {
        List<ContradictionFinding> findings = detector.detectVerdictConflicts(
                List.of(triple(1L, "Aster Labs", "aster labs", "acquires", "Orion Systems")),
                List.of(new ContradictionDetector.VerdictRecord(10L, "Aster Labs", "acquires",
                        "Orion Systems", ClaimPolarity.POSITIVE, true,
                        "Document 7 states the acquisition did not occur.")));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(ContradictionFinding.TYPE_VERDICT);
    }

    @Test
    @DisplayName("a SUPPORTED verdict produces no conflict")
    void supportedVerdictNoConflict() {
        assertThat(detector.detectVerdictConflicts(
                List.of(triple(1L, "Aster Labs", "aster labs", "acquires", "Orion Systems")),
                List.of(new ContradictionDetector.VerdictRecord(10L, "Aster Labs", "acquires",
                        "Orion Systems", ClaimPolarity.POSITIVE, false, "Confirmed."))))
                .isEmpty();
    }

    // ---- determinism -------------------------------------------------------

    @Test
    @DisplayName("detection is deterministic and order-independent")
    void deterministic() {
        List<ContradictionDetector.ApprovedTriple> a = List.of(
                triple(1L, "Orion Systems", "orion systems", "reports_to", "Northstar Holdings"),
                triple(2L, "Orion Systems", "orion systems", "reports_to", "Meridian Group"));
        List<ContradictionDetector.ApprovedTriple> b = List.of(
                triple(2L, "Orion Systems", "orion systems", "reports_to", "Meridian Group"),
                triple(1L, "Orion Systems", "orion systems", "reports_to", "Northstar Holdings"));

        List<ContradictionFinding> first = detector.detectRelationConflicts(a);
        List<ContradictionFinding> second = detector.detectRelationConflicts(b);
        assertThat(first).hasSameSizeAs(second);
        assertThat(first.get(0).conflictHash()).isEqualTo(second.get(0).conflictHash());
    }

    @Test
    @DisplayName("the conflict hash is stable and order-independent")
    void hashIsOrderIndependent() {
        String h1 = ContradictionFinding.buildHash("RELATION_CONFLICT", "Orion Systems",
                "reports_to", 1L, 2L);
        String h2 = ContradictionFinding.buildHash("RELATION_CONFLICT", "Orion Systems",
                "reports_to", 2L, 1L);
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    @DisplayName("empty and null inputs are handled")
    void emptyInputs() {
        assertThat(detector.detectAll(List.of(), List.of(), List.of())).isEmpty();
        assertThat(detector.detectAll(null, null, null)).isEmpty();
    }
}
