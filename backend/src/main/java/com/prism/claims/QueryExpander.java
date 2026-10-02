package com.prism.claims;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, versioned expansion of a question's wording into the
 * vocabulary a corpus actually uses.
 *
 * <p><b>Why this exists.</b> The corpus states facts as
 * {@code Subject predicate Object} with snake_case predicates, so it contains
 * {@code collaborates_with}. A person asks "Who does Calder collaborate with?".
 * InnoDB FULLTEXT treats an underscore as a word character, so
 * {@code collaborates_with} is indexed as a single token -- and the question
 * contains neither that token nor the uninflected word. The query was
 * {@code +calder +collaborate}, in which only {@code calder} matched anything:
 * {@code collaborates_with} scored 0.8827 against the gold chunk, against 7.2985
 * for {@code +calder +collaborates_with}. The gold passage was never returned at
 * any rank and the system correctly reported {@code insufficientEvidence} rather
 * than guessing. That was measured, not hypothesised: it was the one miss in an
 * eleven-query benchmark, and the cause was a vocabulary gap, not a ranking
 * weakness.
 *
 * <p><b>Why it is not an LLM.</b> Retrieval feeds the verification judge and the
 * Skeptic brief. If a model generated the search terms, then a prompt injected
 * into a document could steer which passages a verdict is allowed to cite. Query
 * expansion is part of the trusted retrieval path, so it stays deterministic: a
 * fixed, curated map, a version string, and no input beyond the caller's own
 * question.
 *
 * <p><b>How expansion works here, stated precisely.</b> The retriever issues
 * {@code MATCH ... AGAINST (... IN NATURAL LANGUAGE MODE)}, and in that mode
 * MySQL treats the query as a bag of words ranked by term frequency and
 * positional proximity. It does <em>not</em> parse boolean syntax: {@code +} is
 * ignored, and {@code OR} and parentheses are discarded. Measured, on the demo
 * corpus: {@code +calder +collaborate} and {@code calder collaborate} both score
 * 0.8827 on chunk 20, and {@code (+a OR +b)} scores identically to {@code +a +b}
 * on every case tried.
 *
 * <p>So expansion here is not "widening an AND" -- there is no AND. It is
 * <b>adding the corpus's own token as an extra relevance term</b>, which is what
 * lets a chunk containing {@code collaborates_with} score on the question's
 * subject matter at all.
 *
 * <p><b>That has a cost, and it is not a pure win.</b> An extra term also pulls
 * in other chunks that mention it. On the same gold set, {@code Who does Orion
 * Systems supply} moved from rank 5 to rank 6 -- out of the top-five window --
 * because adding {@code supplies} promoted three supply-related chunks above the
 * gold one. Recall@3 rose from 0.8182 to 0.9091 and MRR from 0.8033 to 0.8333
 * while Recall@1 and Recall@5 were unchanged, so on eleven queries the net is a
 * small gain with one query traded. Both figures are recorded in
 * {@code docs/evaluation.md} rather than only the flattering ones.
 *
 * <p><b>Versioning.</b> {@link #VERSION} is recorded on every retrieval result,
 * on every benchmark row, and in the trace. A retrieval score is not comparable
 * across versions, so a regression has to be attributable to a change in this map
 * rather than to the corpus or the ranking. Bump it whenever a mapping changes.
 */
public final class QueryExpander {

    /**
     * Version of the mapping table. Recorded on every retrieval result.
     *
     * <p>Bump on any change to {@link #SURFACE_TO_CORPUS_TOKEN}: scores from two
     * versions are not comparable, and a retrieval evaluation run must be
     * attributable to one of them.
     */
    public static final String VERSION = "EXPAND_V1";

    /**
     * Question wording mapped to the token that appears in the corpus.
     *
     * <p><b>Targets are the FULL snake_case identifier, not its head word.</b>
     * That was measured rather than assumed. InnoDB FULLTEXT treats an
     * underscore as a word character, so {@code collaborates_with} is indexed as
     * one token: searching {@code +collaborates} scores 0 against a chunk that
     * demonstrably contains the word at offset 640, while
     * {@code +collaborates_with} scores 6.4 against the same chunk. An expansion
     * to the head word reads as correct, expands nothing, and is invisible unless
     * you query the index directly.
     *
     * <p>Every target is a registered predicate name. A mapping to anything else
     * matches no document and is dead weight in every trace, so
     * {@code QueryExpanderTest} asserts each target against the registry.
     */
    private static final Map<String, String> SURFACE_TO_CORPUS_TOKEN = buildMap();

    private static Map<String, String> buildMap() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "collaborates_with", "collaborate", "collaborates", "collaborated", "collaborating",
                "collaboration", "collaborative", "collaborations", "partners", "partnerships");
        put(m, "reports_to", "report", "reports", "reported", "reporting", "reports",
                "accountable", "accountability", "answerable");
        put(m, "headquartered_in", "headquartered", "headquarters", "headquarter", "head-office", "hq");
        put(m, "parent_organization", "parent", "parents", "parent-company", "parent-organisation",
                "parent-organization", "ultimate-parent");
        put(m, "subsidiary_of", "subsidiary", "subsidiaries", "subsidiary-of");
        put(m, "owns", "owns", "own", "owned", "ownership", "owning", "owner");
        put(m, "located_in", "located", "location", "site", "sites", "situated", "premises", "geographical");
        put(m, "chief_executive", "ceo", "chief", "chief-executive", "chief-executives", "managing-director");
        put(m, "funds", "fund", "funds", "funded", "funding", "finances", "financed", "financing");
        put(m, "controls", "control", "controls", "controlled", "controlling");
        put(m, "invests_in", "invest", "invests", "invested", "investment", "investments");
        put(m, "supplies", "supply", "supplies", "supplied", "supplier", "suppliers", "vendor", "vendors");
        put(m, "member_of", "member", "members", "membership", "belongs", "belonging");
        put(m, "part_of", "part", "part-of", "division", "divisions");
        put(m, "works_for", "work", "works", "worked", "employs", "employed", "employee", "employees", "staff");
        put(m, "advises", "advise", "advises", "advisor", "adviser", "advisors", "advisers");
        put(m, "allied_with", "ally", "allies", "allied", "ally-of");
        put(m, "competes_with", "compete", "competes", "competing", "competitor", "competitors",
                "rival", "rivals");
        put(m, "acquires", "acquire", "acquires", "acquired", "acquisition");
        put(m, "sources_from", "source", "sources", "sourced", "sourcing");
        put(m, "operated_by", "operate", "operates", "operated", "operator", "operators");
        put(m, "licenses_to", "license", "licenses", "licensed", "licence", "licences", "licensing");
        put(m, "exports_to", "export", "exports", "exported", "exporter");
        put(m, "founded_by", "founded", "founder", "founders", "founding");
        put(m, "succeeded_by", "succeed", "succeeds", "succeeded", "successor", "successors");
        put(m, "preceded_by", "precede", "precedes", "preceded", "predecessor", "predecessors");
        put(m, "regulated_by", "regulate", "regulates", "regulated", "regulator", "regulators");
        put(m, "operates_in", "trading", "presence");
        return Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, String> m, String target, String... surfaces) {
        for (String surface : surfaces) {
            m.put(surface, target);
        }
    }
    private QueryExpander() {
        // static utility
    }

    /** Every surface form with a mapping, for tests and diagnostics. */
    public static Set<String> knownSurfaceForms() {
        return SURFACE_TO_CORPUS_TOKEN.keySet();
    }

    /** The corpus token a surface form expands to, or null if it has no mapping. */
    public static String expansionFor(String surfaceForm) {
        return SURFACE_TO_CORPUS_TOKEN.get(surfaceForm);
    }

    /**
     * The expanded query, and what was expanded.
     *
     * @param fulltextQuery the boolean fulltext expression actually issued
     * @param applied       surface form to corpus token, in the order encountered
     * @param version       {@link #VERSION}, carried so a reader can tell which
     *                      mapping table produced a result
     */
    public record Expansion(String fulltextQuery, Map<String, String> applied, String version) {

        public boolean expandedAnything() {
            return !applied.isEmpty();
        }

        public String describe() {
            if (applied.isEmpty()) {
                return version + ": no expansion applied";
            }
            StringBuilder sb = new StringBuilder(version).append(": ");
            boolean first = true;
            for (Map.Entry<String, String> e : applied.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey()).append(" -> ").append(e.getValue());
            }
            return sb.toString();
        }
    }

    /**
     * Expands already-normalised query tokens into a fulltext term list.
     *
     * <p>Tokens arrive from {@link RetrievalService#queryTokens}, so this method
     * does no normalisation of its own and cannot disagree with it about what a
     * token is.
     *
     * <p>A mapped token emits its own form <em>and</em> the corpus token, because
     * the corpus token is often a word the question never used. {@code collaborate}
     * and {@code collaborates_with} share no prefix as far as InnoDB is concerned,
     * so a question containing only the first cannot match a chunk containing only
     * the second.
     *
     * <p>An earlier version emitted {@code (+collaborate OR +collaborates_with)}
     * on the reasoning that the base query was a boolean AND and an OR group
     * would widen rather than narrow it. In natural language mode there is no AND
     * to widen, and the OR form was measured to score identically to the plain
     * pair on every case tried -- so the parentheses and the {@code OR} were
     * carrying a claim about the query that MySQL was discarding along with them.
     *
     * @param tokens   normalised, stop-worded, length-filtered query tokens
     * @param maxTerms cap on emitted query terms, matching the base query's cap.
     *                 The cap counts <em>question terms</em>, not emitted terms,
     *                 so a long question cannot inflate the term count.
     */
    public static Expansion expand(List<String> tokens, int maxTerms) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> applied = new LinkedHashMap<>();
        int emitted = 0;

        for (String token : tokens) {
            if (emitted >= maxTerms) {
                break;
            }
            String target = SURFACE_TO_CORPUS_TOKEN.get(token);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('+').append(token);
            emitted++;
            if (target == null || target.equals(token)) {
                continue;
            }
            sb.append(" +").append(target);
            applied.put(token, target);
        }
        return new Expansion(sb.toString(), Map.copyOf(applied), VERSION);
    }
}
