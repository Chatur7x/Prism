package com.prism.claims;

import com.prism.config.PrismTuning.ClaimRules;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic linguistic analysis of a claim's calibration.
 *
 * <p>Pure function of the claim text plus configuration. No model, no I/O, no
 * clock. Given the same text it always returns the same findings, which is what
 * makes a verdict reproducible and its rule component auditable.
 *
 * <p>Matching is token-aware, never substring. "all" must not fire on "small",
 * "allow", or "ball" — a substring check would produce false penalties on
 * perfectly hedged claims and quietly corrupt every fused score downstream.
 */
public final class ClaimRuleEngine {

    /**
     * Hedges and weasel words: language that weakens an assertion.
     * Matched as whole tokens.
     */
    private static final Set<String> WEASEL_TERMS = Set.of(
            "reportedly", "allegedly", "purportedly", "supposedly", "approximately", "roughly",
            "about", "around", "nearly", "almost", "somewhat", "fairly", "rather", "quite",
            "may", "might", "could", "perhaps", "possibly", "probably", "apparently",
            "seemingly", "arguably", "ostensibly", "instructed", "believed", "suspected",
            "estimated", "projected", "expected", "indicated", "suggested", "assumed",
            "reported", "alleged", "purported", "understood", "said", "claimed", "asserted",
            "sometimes", "occasionally", "frequently", "generally", "usually", "typically",
            "often", "rarely", "seldom", "largely", "mostly", "relatively", "some");

    /**
     * Absolutes: language that overstates. Matched as whole tokens.
     */
    private static final Set<String> ABSOLUTE_TERMS = Set.of(
            "always", "never", "everyone", "nobody", "no-one", "all", "none", "impossible",
            "certainly", "definitely", "guaranteed", "guarantee", "guarantees", "undeniably",
            "unquestionably", "indisputably", "absolutely", "completely", "totally", "utterly",
            "entirely", "invariably", "unfailingly", "permanently", "forever", "exclusively",
            "only", "solely", "must", "shall", "proved", "proven",
            "confirmed", "verified", "every", "everybody", "anybody", "any",
            "whatever", "whoever", "whenever", "wherever", "unanimously", "universally",
            "categorically", "emphatically");

    /**
     * Multi-word absolutes that a token scan alone would miss.
     *
     * <p>Patterns separate words with {@code \s+}, not a literal hyphen, because
     * {@link #normalize} collapses punctuation to a single space.
     */
    private static final List<Pattern> ABSOLUTE_PHRASES = List.of(
            Pattern.compile("\\bno\\s+one\\b"),
            Pattern.compile("\\bin\\s+all\\s+(?:cases|respects)\\b"),
            Pattern.compile("\\bwithout\\s+exception\\b"),
            Pattern.compile("\\bin\\s+every\\s+case\\b"),
            Pattern.compile("\\bto\\s+be\\s+certain\\b"),
            Pattern.compile("\\bbeyond\\s+doubt\\b"),
            Pattern.compile("\\bhas\\s+been\\s+proven\\b"),
            Pattern.compile("\\bshall\\s+not\\s+fail\\b"),
            Pattern.compile("\\bat\\s+all\\s+times\\b"),
            Pattern.compile("\\bin\\s+no\\s+circumstances\\b"));

    /** Negation flips a hedge's effect, so a negated absolute is not an absolute. */
    private static final Set<String> NEGATORS = Set.of("not", "no", "never", "cannot", "without");

    private final double penaltyPerWeasel;
    private final double penaltyPerAbsolute;
    private final double maxPenalty;
    private final String ruleVersion;

    public ClaimRuleEngine(ClaimRules rules) {
        this(rules.penaltyPerWeasel(), rules.penaltyPerAbsolute(), rules.maxPenalty(), rules.version());
    }

    public ClaimRuleEngine(double penaltyPerWeasel, double penaltyPerAbsolute,
                           double maxPenalty, String ruleVersion) {
        this.penaltyPerWeasel = penaltyPerWeasel;
        this.penaltyPerAbsolute = penaltyPerAbsolute;
        this.maxPenalty = maxPenalty;
        this.ruleVersion = ruleVersion;
    }

    /** One detected term with the sentence position it was found in. */
    public record RuleHit(String category, String term, int tokenIndex) {
    }

    /**
     * Structured result. {@code penalty} is a deduction from the fused score,
     * not a probability and not a verdict.
     */
    public record RuleAnalysis(
            List<RuleHit> weaselHits,
            List<RuleHit> absoluteHits,
            double penalty,
            String explanation,
            String ruleVersion) {

        public boolean hasWeasels() {
            return !weaselHits.isEmpty();
        }

        public boolean hasAbsolutes() {
            return !absoluteHits.isEmpty();
        }

        public int totalHits() {
            return weaselHits.size() + absoluteHits.size();
        }
    }

    public RuleAnalysis analyze(String claimText) {
        if (claimText == null || claimText.isBlank()) {
            return new RuleAnalysis(List.of(), List.of(), 0.0, "No text to analyze.", ruleVersion);
        }

        String normalized = normalize(claimText);
        String[] tokens = normalized.split(" ");

        List<RuleHit> weasels = new java.util.ArrayList<>();
        List<RuleHit> absolutes = new java.util.ArrayList<>();

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            // A negated absolute is a different claim ("never" inside "not
            // never"), so it must not be penalised as an overstatement.
            boolean negated = i > 0 && NEGATORS.contains(tokens[i - 1]);

            if (WEASEL_TERMS.contains(token)) {
                weasels.add(new RuleHit("WEASEL", token, i));
            }
            if (ABSOLUTE_TERMS.contains(token) && !negated) {
                absolutes.add(new RuleHit("ABSOLUTE", token, i));
            }
        }

        for (Pattern phrase : ABSOLUTE_PHRASES) {
            Matcher m = phrase.matcher(normalized);
            while (m.find()) {
                absolutes.add(new RuleHit("ABSOLUTE_PHRASE", m.group().replaceAll("\\s+", " "), -1));
            }
        }

        double rawPenalty = weasels.size() * penaltyPerWeasel + absolutes.size() * penaltyPerAbsolute;
        double penalty = round3(Math.min(rawPenalty, maxPenalty));

        return new RuleAnalysis(
                List.copyOf(weasels),
                List.copyOf(absolutes),
                penalty,
                explain(weasels, absolutes, penalty),
                ruleVersion);
    }

    private String explain(List<RuleHit> weasels, List<RuleHit> absolutes, double penalty) {
        if (weasels.isEmpty() && absolutes.isEmpty()) {
            return "No hedging or overstatement markers detected. Rule penalty 0.000.";
        }
        StringBuilder sb = new StringBuilder();
        if (!weasels.isEmpty()) {
            sb.append("Hedging terms detected: ")
                    .append(joinTerms(weasels))
                    .append(". A hedged claim asserts less than an unhedged one.");
        }
        if (!absolutes.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("Absolute terms detected: ")
                    .append(joinTerms(absolutes))
                    .append(". Absolutes overstate the generality of what the evidence can support.");
        }
        sb.append(" Rule penalty ").append(String.format(Locale.ROOT, "%.3f", penalty)).append('.');
        return sb.toString();
    }

    private static String joinTerms(List<RuleHit> hits) {
        return hits.stream().map(RuleHit::term).distinct().reduce((a, b) -> a + ", " + b).orElse("");
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
