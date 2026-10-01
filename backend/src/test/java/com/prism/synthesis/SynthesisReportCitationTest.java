package com.prism.synthesis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report citation contract.
 *
 * <p>No Spring context and no database. What is under test is the rule every
 * consumer of a synthesis report relies on: a citation names something a reader
 * can go and look at, and it names exactly one thing.
 *
 * <p>This exists because the report endpoint once published only four of a
 * citation's six target fields. A citation into a triple, claim, or verdict came
 * back with every field null and was indistinguishable from a broken citation —
 * and because the endpoint returned an untyped map, the OpenAPI schema could not
 * be used to discover the omission either. {@code DebateController} returning
 * the raw entity instead of the shared {@code DebateResponse} was the same
 * failure mode on a different endpoint.
 */
class SynthesisReportCitationTest {

    private static SynthesisReportResponse.CitationResponse citation(
            Long chunkId, Long tripleId, Long claimId, Long verdictId,
            Long argumentId, String machineFactId) {
        return new SynthesisReportResponse.CitationResponse(1L, "TEST", chunkId, tripleId,
                claimId, verdictId, argumentId, machineFactId);
    }

    @Test
    @DisplayName("a citation to a chunk names its target")
    void chunkCitation() {
        assertTrue(SynthesisReportResponse.citationNamesATarget(
                citation(412L, null, null, null, null, null)));
    }

    @Test
    @DisplayName("a citation to a triple, claim, or verdict names its target")
    void evidenceCitations() {
        assertTrue(SynthesisReportResponse.citationNamesATarget(
                citation(null, 87L, null, null, null, null)), "triple");
        assertTrue(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, 120L, null, null, null)), "claim");
        assertTrue(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, 9L, null, null)), "verdict");
    }

    @Test
    @DisplayName("a citation to an argument or a machine fact names its target")
    void argumentAndFactCitations() {
        // These are the two kinds synthesis actually produces, so if the contract
        // did not cover them every real report would fail its own validation.
        assertTrue(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, null, 28L, null)), "argument");
        assertTrue(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, null, null, "FACT-7")), "machine fact");
    }

    @Test
    @DisplayName("a citation naming nothing is rejected")
    void emptyCitation() {
        // The case the old API could not even express: all six fields came back
        // null, and nothing in the response said whether that meant "no target" or
        // "the field you needed was never published".
        assertFalse(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("a blank machine fact id does not count as a target")
    void blankFactId() {
        assertFalse(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, null, null, "")));
        assertFalse(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, null, null, "   ")));
    }

    @Test
    @DisplayName("a citation naming two targets is rejected as ambiguous")
    void ambiguousCitation() {
        // Not "more support", but unattributable: a reader cannot tell which
        // source supports the sentence, and no later step can repair that. The
        // database's CHECK on kind does not cover this, which is why it is
        // enforced here.
        assertFalse(SynthesisReportResponse.citationNamesATarget(
                citation(412L, 87L, null, null, null, null)));
        assertFalse(SynthesisReportResponse.citationNamesATarget(
                citation(null, null, null, null, 28L, "FACT-7")));
    }
}