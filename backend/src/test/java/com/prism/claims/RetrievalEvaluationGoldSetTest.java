package com.prism.claims;

import com.prism.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gold-set parser.
 *
 * <p>No Spring context and no database: this is the part of retrieval evaluation
 * that decides whether a measurement is valid, and it must be right before any
 * retrieval runs. Every case here is one that could otherwise produce a
 * partially-applied gold set — metrics that look real and are silently wrong.
 */
class RetrievalEvaluationGoldSetTest {

    @Test
    @DisplayName("parses 'query = chunkId' lines into ordered gold queries")
    void parsesLines() {
        List<RetrievalEvaluationService.GoldQuery> parsed =
                RetrievalEvaluationService.parseGoldSet("""
                        Meridian headquarters = 412
                        who owns Aster = 87
                        """);

        assertEquals(2, parsed.size());
        assertEquals("Meridian headquarters", parsed.get(0).query());
        assertEquals(412L, parsed.get(0).goldChunkId());
        assertEquals("who owns Aster", parsed.get(1).query());
        assertEquals(87L, parsed.get(1).goldChunkId());
    }

    @Test
    @DisplayName("a query containing '=' is split on the LAST separator, not the first")
    void splitsOnLastEqualsOnly() {
        // A natural-language gold query can contain '=' ("revenue = 4.2m"), and the
        // chunk id is always a bare integer in the final field. Splitting on the
        // first separator would truncate such a query and then measure the wrong
        // text while reporting a plausible-looking score.
        List<RetrievalEvaluationService.GoldQuery> parsed =
                RetrievalEvaluationService.parseGoldSet("revenue = 4.2m or higher = 42");

        assertEquals(1, parsed.size());
        assertEquals("revenue = 4.2m or higher", parsed.get(0).query());
        assertEquals(42L, parsed.get(0).goldChunkId());
    }

    @Test
    @DisplayName("blank lines and comments are ignored so a gold set can be annotated")
    void ignoresBlanksAndComments() {
        List<RetrievalEvaluationService.GoldQuery> parsed =
                RetrievalEvaluationService.parseGoldSet("""
                        # Meridian group structure, verified by hand 2026-10-01

                        Meridian headquarters = 412

                        # ownership line below
                        who owns Aster = 87

                        """);

        assertEquals(2, parsed.size());
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed from both sides")
    void trimsWhitespace() {
        List<RetrievalEvaluationService.GoldQuery> parsed =
                RetrievalEvaluationService.parseGoldSet("   Meridian headquarters   =   412   ");

        assertEquals("Meridian headquarters", parsed.get(0).query());
        assertEquals(412L, parsed.get(0).goldChunkId());
    }

    @Test
    @DisplayName("a line with no separator is rejected and names its line number")
    void rejectsLineWithoutSeparator() {
        ApiException ex = assertThrows(ApiException.class,
                () -> RetrievalEvaluationService.parseGoldSet("valid = 1\nthis line has no separator\n"));

        // Naming the line is the difference between a fixable report and a
        // guessing game: a gold set is maintained by hand and will be wrong.
        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
    }

    @Test
    @DisplayName("a non-numeric chunk id is rejected and names its line number")
    void rejectsNonNumericChunkId() {
        ApiException ex = assertThrows(ApiException.class,
                () -> RetrievalEvaluationService.parseGoldSet("valid = 1\nbroken = notanumber\n"));

        assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("notanumber"), ex.getMessage());
    }

    @Test
    @DisplayName("an empty query is rejected rather than measured as a blank search")
    void rejectsEmptyQuery() {
        ApiException ex = assertThrows(ApiException.class,
                () -> RetrievalEvaluationService.parseGoldSet("   = 42\n"));

        assertTrue(ex.getMessage().contains("line 1"), ex.getMessage());
    }

    @Test
    @DisplayName("a gold set with no usable lines is rejected")
    void rejectsEmptyGoldSet() {
        // Silently measuring zero queries and reporting recall 0.0 would be
        // indistinguishable from a retriever that has stopped working.
        assertThrows(ApiException.class, () -> RetrievalEvaluationService.parseGoldSet("   \n\n# only comments\n"));
    }

    @Test
    @DisplayName("an over-long query is rejected against the column width")
    void rejectsOverlongQuery() {
        String longQuery = "x".repeat(501);
        ApiException ex = assertThrows(ApiException.class,
                () -> RetrievalEvaluationService.parseGoldSet(longQuery + " = 1\n"));

        // query_text is VARCHAR(500); letting a longer value through would fail
        // at the insert, after retrieval had already been spent on it.
        assertTrue(ex.getMessage().contains("500"), ex.getMessage());
    }
}