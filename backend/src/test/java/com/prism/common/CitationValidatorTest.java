package com.prism.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    @Test
    @DisplayName("citations inside the allowed set are accepted")
    void acceptsValidCitations() {
        var result = CitationValidator.validateChunkIds(List.of(1L, 2L, 3L), Set.of(1L, 2L, 3L));
        assertThat(result.isFullyValid()).isTrue();
        assertThat(result.valid()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("a citation the model invented is rejected")
    void rejectsHallucinatedCitation() {
        var result = CitationValidator.validateChunkIds(List.of(1L, 9999L), Set.of(1L, 2L));
        assertThat(result.hasHallucinatedCitations()).isTrue();
        assertThat(result.valid()).containsExactly(1L);
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo("9999"));
    }

    @Test
    @DisplayName("a citation outside the corpus is rejected even if it exists elsewhere")
    void rejectsOutOfScopeCitation() {
        // Chunk 42 exists in another corpus; it is not in this allowed set.
        var result = CitationValidator.validateChunkIds(List.of(42L), Set.of(1L, 2L));
        assertThat(result.hasHallucinatedCitations()).isTrue();
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).contains("not part of the retrieved evidence set"));
    }

    @Test
    @DisplayName("duplicate citations are collapsed")
    void collapsesDuplicates() {
        var result = CitationValidator.validateChunkIds(List.of(1L, 1L, 2L, 2L, 2L), Set.of(1L, 2L));
        assertThat(result.valid()).containsExactly(1L, 2L);
        assertThat(result.isFullyValid()).isTrue();
    }

    @Test
    @DisplayName("non-numeric citation ids are rejected, not ignored")
    void rejectsNonNumeric() {
        // Models sometimes emit the id as a string; the validator must still
        // reject the bad one rather than coercing everything to text.
        var result = CitationValidator.validateChunkIds(List.<Object>of("abc", 1L), Set.of(1L));
        assertThat(result.valid()).containsExactly(1L);
        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).contains("not a number"));
    }

    @Test
    @DisplayName("a null citation list yields no citations and no rejections")
    void handlesNull() {
        var result = CitationValidator.validateChunkIds(null, Set.of(1L));
        assertThat(result.valid()).isEmpty();
        assertThat(result.rejected()).isEmpty();
        assertThat(result.isFullyValid()).isTrue();
    }

    @Test
    @DisplayName("an empty allowed set rejects every citation")
    void emptyAllowedSetRejectsAll() {
        var result = CitationValidator.validateChunkIds(List.of(1L, 2L), Set.of());
        assertThat(result.valid()).isEmpty();
        assertThat(result.rejected()).hasSize(2);
    }

    @Test
    @DisplayName("string identifiers follow the same rule")
    void stringIdentifiers() {
        var ok = CitationValidator.validateStringIds(List.of("PR-1", "COM-0"), Set.of("PR-1", "COM-0"));
        assertThat(ok.isFullyValid()).isTrue();
        assertThat(ok.valid()).containsExactly("PR-1", "COM-0");

        var bad = CitationValidator.validateStringIds(List.of("PR-1", "PR-999"), Set.of("PR-1"));
        assertThat(bad.hasHallucinatedCitations()).isTrue();
        assertThat(bad.valid()).containsExactly("PR-1");
    }

    @Test
    @DisplayName("blank string identifiers are rejected")
    void rejectsBlankStringIds() {
        var result = CitationValidator.validateStringIds(java.util.Arrays.asList("PR-1", null, "  "),
                Set.of("PR-1"));
        assertThat(result.valid()).containsExactly("PR-1");
        assertThat(result.rejected()).hasSize(2);
    }

    @Test
    @DisplayName("whitespace around a string citation is trimmed before matching")
    void trimsStringCitations() {
        var result = CitationValidator.validateStringIds(List.of("  PR-1  "), Set.of("PR-1"));
        assertThat(result.valid()).containsExactly("PR-1");
        assertThat(result.isFullyValid()).isTrue();
    }
}
