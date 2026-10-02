package com.prism.claims;

import com.prism.knowledge.PredicateSemanticRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic query expansion.
 *
 * <p>No Spring context and no database. The expander is a pure function from a
 * caller's own question to a boolean fulltext expression, and it is on the
 * trusted retrieval path -- it decides which passages a verdict may cite -- so it
 * must be right without a running system.
 *
 * <p>The bug this class exists for was measured, not imagined: in an
 * eleven-query gold set, "Who does Calder collaborate with" failed to retrieve
 * the gold passage. The corpus says {@code collaborates_with}; the question says
 * {@code collaborate}; FULLTEXT indexes the identifier as {@code collaborates}
 * and {@code with}, and the question contained neither.
 */
class QueryExpanderTest {

    // ---- the reported miss --------------------------------------------------

    @Test
    @DisplayName("the known miss: 'collaborate' expands to the corpus's 'collaborates_with'")
    void fixesTheKnownMiss() {
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("Who does Calder collaborate with?"), 12);

        assertTrue(expansion.expandedAnything(), "expected the query to be expanded");
        assertEquals("collaborates_with", expansion.applied().get("collaborate"));
        // The target must be the FULL identifier. InnoDB FULLTEXT treats an
        // underscore as a word character, so `collaborates` scores 0 against a
        // chunk containing `collaborates_with`. An expansion to the head word
        // looks right and retrieves nothing.
        assertTrue(expansion.fulltextQuery().contains("collaborates_with"),
                expansion.fulltextQuery());
    }

    @Test
    @DisplayName("the question's own term is kept alongside the corpus token")
    void keepsBothForms() {
        // The point is to add the corpus token, not to replace the question's.
        // Dropping `collaborate` would lose a genuine match on a document that
        // happens to use the uninflected word.
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("Calder collaborate"), 12);

        assertEquals("+calder +collaborate +collaborates_with", expansion.fulltextQuery());
    }

    @Test
    @DisplayName("the emitted query is what MySQL actually scores, not a boolean fiction")
    void queryCarriesNoBooleanSyntax() {
        // The retriever issues IN NATURAL LANGUAGE MODE, which discards boolean
        // operators. An earlier version emitted `(+a OR +b)` on the belief that the
        // base query was a boolean AND being widened; the parentheses and OR were
        // discarded by MySQL, so the claim was carried by syntax that did nothing.
        // Asserted here so it cannot be reintroduced as "documentation".
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("Calder collaborate funds Aster"), 12);

        assertFalse(expansion.fulltextQuery().contains("OR"),
                expansion.fulltextQuery());
        assertFalse(expansion.fulltextQuery().contains("("),
                expansion.fulltextQuery());
    }

    @Test
    @DisplayName("a question with no mapped word is left exactly as it was")
    void plainTermsUnaffected() {
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("Meridian Corvale register"), 12);

        assertFalse(expansion.expandedAnything(),
                "no surface form here has a mapping: " + expansion.fulltextQuery());
        assertEquals("+meridian +corvale +register", expansion.fulltextQuery());
    }

    @Test
    @DisplayName("an ordinary word that names a relation does expand")
    void siteExpandsToLocated() {
        // The pairing worth asserting explicitly: "site" and "located" both point
        // at located_in.
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("Where is the Calder site located"), 12);

        assertEquals("located_in", expansion.applied().get("site"));
        assertEquals("located_in", expansion.applied().get("located"));
        assertEquals("+calder +site +located_in +located +located_in",
                expansion.fulltextQuery());
    }

    @Test
    @DisplayName("an identity mapping is not emitted, so no query carries a redundant group")
    void identityMappingIsNotEmitted() {
        // `funds` is its own target. Emitting (+funds OR +funds) matches exactly
        // what +funds matches, so it only wastes bytes in the query and noise in
        // every trace. Normalisation strips underscores, so no other surface form
        // can currently collide with a snake_case target; the guard is kept
        // because the map is edited by hand.
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("funds"), 12);

        assertFalse(expansion.applied().containsKey("funds"),
                "an identity mapping must not be recorded as applied: " + expansion.applied());
        assertEquals("+funds", expansion.fulltextQuery());
    }

    // ---- properties that must hold for every mapping ------------------------

    @Test
    @DisplayName("every expansion target is a registered predicate name")
    void targetsComeFromTheRegistry() {
        // Guards against a typo'd mapping that expands to a token no document in
        // any corpus will ever contain. Targets must be the complete predicate
        // identifier, because InnoDB FULLTEXT indexes `collaborates_with` as one
        // token -- a mapping to the head word matches nothing at all.
        PredicateSemanticRegistry registry = new PredicateSemanticRegistry();
        for (String surface : QueryExpander.knownSurfaceForms()) {
            String target = QueryExpander.expansionFor(surface);
            assertTrue(target != null && !target.isBlank(),
                    "mapping for '" + surface + "' has no target");
            assertTrue(target.equals(target.toLowerCase(Locale.ROOT)),
                    "target '" + target + "' must be lowercase to match indexed text");
            assertTrue(registry.knownPredicateNames().contains(target),
                    "'" + surface + "' expands to '" + target + "', which is not a registered "
                            + "predicate -- so no document will ever match it");
        }
    }

    @Test
    @DisplayName("the registry's predicates are all reachable from some wording")
    void everyPredicateIsReachable() {
        // The complement of the test above. Without it, a predicate could be
        // registered and used by the corpus yet unreachable from any question,
        // which is the same gap this class exists to close -- just for a
        // different relation.
        PredicateSemanticRegistry registry = new PredicateSemanticRegistry();
        java.util.Set<String> targeted = new java.util.HashSet<>(
                QueryExpander.knownSurfaceForms().stream()
                        .map(QueryExpander::expansionFor).toList());

        for (String predicate : registry.knownPredicateNames()) {
            assertTrue(targeted.contains(predicate),
                    "predicate '" + predicate + "' is registered and used by the corpus but no "
                            + "question wording expands to it");
        }
    }

    @Test
    @DisplayName("every mapping target is one whitespace-free token")
    void targetsAreSingleTokens() {
        // A target containing a space would be split by normalisation and its
        // parts searched separately, which is not what the mapping means. The
        // underscore case is different and deliberate: MySQL indexes it as one
        // token, so `collaborates_with` is correct where `collaborates with`
        // would not be.
        for (String surface : QueryExpander.knownSurfaceForms()) {
            String target = QueryExpander.expansionFor(surface);
            assertFalse(target.contains(" "),
                    "'" + surface + "' -> '" + target + "' contains a space; it must be one token");
        }
    }

    @Test
    @DisplayName("expansion never drops or duplicates a query token")
    void everyTokenSurvives() {
        // The riskiest failure mode: a rewrite that silently loses the entity
        // being asked about would retrieve the wrong passage while looking like
        // it worked.
        List<String> tokens = RetrievalService.queryTokens("Who funds Aster Labs research collaboration");
        QueryExpander.Expansion expansion = QueryExpander.expand(tokens, 12);

        for (String token : tokens) {
            assertTrue(expansion.fulltextQuery().contains(token),
                    "token '" + token + "' vanished from " + expansion.fulltextQuery());
        }
    }

    @Test
    @DisplayName("the term cap truncates the tail even when every token expands")
    void capIsHonoured() {
        // 13 tokens, cap 12. The 13th is `collaborates`, which also appears as the
        // first token, so testing for the substring would prove nothing -- the
        // exact expression does. It also shows what the cap counts: TERMS, not
        // plus signs, so a fully expanded query emits two plus signs per group.
        List<String> tokens = List.of("collaborates", "reports", "parent", "owns", "subsidiary",
                "invests", "supplies", "member", "controls", "funds", "advises", "acquires",
                "collaborates");
        QueryExpander.Expansion expansion = QueryExpander.expand(tokens, 12);

        String expected = "+collaborates +collaborates_with "
                + "+reports +reports_to "
                + "+parent +parent_organization "
                + "+owns "
                + "+subsidiary +subsidiary_of "
                + "+invests +invests_in "
                + "+supplies "
                + "+member +member_of "
                + "+controls +funds +advises +acquires";
        assertEquals(expected, expansion.fulltextQuery());

        // Twelve terms were emitted, and the identity mappings (owns, funds,
        // supplies, controls, advises, acquires) were not expanded.
        assertEquals(6, expansion.applied().size(), expansion.applied().toString());
        assertEquals(1, countOccurrences(expansion.fulltextQuery(), "+collaborates "),
                "the duplicated token must appear once -- the cap dropped the second: "
                        + expansion.fulltextQuery());
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            n++;
            from = at + needle.length();
        }
        return n;
    }

    // ---- versioning and traceability ----------------------------------------

    @Test
    @DisplayName("the version is recorded so a score is attributable to a mapping table")
    void versionIsCarried() {
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("collaborate"), 12);
        assertEquals(QueryExpander.VERSION, expansion.version());
        assertTrue(expansion.describe().startsWith(QueryExpander.VERSION),
                expansion.describe());
        assertTrue(QueryExpander.VERSION.matches("EXPAND_V\\d+"),
                "the version must be an ordered token so a reader can tell which table ran");
    }

    @Test
    @DisplayName("the expansion summary names every surface form it changed")
    void summaryIsReadable() {
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("Who does Calder collaborate with and who funds it"), 12);
        String summary = expansion.describe();

        assertTrue(summary.contains("collaborate -> collaborates"), summary);
        assertTrue(summary.contains("funds -> funds") || !summary.contains("funds"), summary);
    }

    // ---- interactions with normalisation ------------------------------------

    @Test
    @DisplayName("a query that normalises to nothing expands to an empty expression")
    void emptyQueryStaysEmpty() {
        assertEquals("", QueryExpander.expand(RetrievalService.queryTokens("what is it"), 12)
                .fulltextQuery());
        assertEquals("", QueryExpander.expand(RetrievalService.queryTokens(null), 12)
                .fulltextQuery());
    }

    @Test
    @DisplayName("the unexpanded query builder still works, unchanged")
    void baseBuilderUnchanged() {
        // Chat and the Skeptic brief both surface the plain query, so the
        // expansion must not have changed what the base builder produces.
        assertEquals("+calder +site", RetrievalService.buildFulltextQuery("Where is the Calder site?"));
    }

    @Test
    @DisplayName("expansion applies to the question only, never to stored text")
    void expansionIsOneDirectional() {
        // There is no reverse mapping, and there must not be: rewriting what a
        // document says would edit the evidence. Only the query is rewritten.
        // A user who types the corpus's own word still gets the plain term, and
        // the expander does not chain it onwards.
        QueryExpander.Expansion expansion = QueryExpander.expand(
                RetrievalService.queryTokens("calder collaborates"), 12);

        assertEquals("+calder +collaborates +collaborates_with",
                expansion.fulltextQuery());
        assertEquals("collaborates_with", expansion.applied().get("collaborates"));
    }
}