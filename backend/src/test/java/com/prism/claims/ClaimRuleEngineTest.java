package com.prism.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimRuleEngineTest {

    private final ClaimRuleEngine engine = new ClaimRuleEngine(0.04, 0.06, 0.40, "RULE_V1");

    @Test
    @DisplayName("clean factual text carries no penalty")
    void cleanText() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze(
                "Meridian Group reports to Northstar Holdings.");
        assertThat(result.hasWeasels()).isFalse();
        assertThat(result.hasAbsolutes()).isFalse();
        assertThat(result.penalty()).isZero();
    }

    @Test
    @DisplayName("hedging terms are detected")
    void detectsHedging() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze(
                "The acquisition was reportedly completed and Orion Systems allegedly supplies them.");
        assertThat(result.hasWeasels()).isTrue();
        assertThat(result.weaselHits()).extracting(ClaimRuleEngine.RuleHit::term)
                .contains("reportedly", "allegedly");
    }

    @Test
    @DisplayName("absolutes are detected")
    void detectsAbsolutes() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze(
                "Northstar Holdings always funds every project that Aster Labs owns.");
        assertThat(result.hasAbsolutes()).isTrue();
        assertThat(result.absoluteHits()).extracting(ClaimRuleEngine.RuleHit::term)
                .contains("always", "every");
    }

    @Test
    @DisplayName("'all' does not match small, allow, or ball")
    void wordBoundaryNotSubstring() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze(
                "The small allowance was a ball game for the allowance committee.");
        assertThat(result.absoluteHits())
                .as("substring matching would falsely flag 'all' inside these words")
                .isEmpty();
    }

    @Test
    @DisplayName("'every' inside 'everyone' is matched as the phrase, not twice as tokens")
    void everyoneIsOneToken() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze("Everyone at the firm agreed.");
        assertThat(result.absoluteHits()).extracting(ClaimRuleEngine.RuleHit::term)
                .contains("everyone");
    }

    @Test
    @DisplayName("a negated absolute is not treated as an overstatement")
    void negationSuppressesAbsolute() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze("The acquisition is not guaranteed.");
        assertThat(result.absoluteHits())
                .as("'not guaranteed' is a denial, not an overstatement")
                .isEmpty();
    }

    @Test
    @DisplayName("multi-word absolutes are detected")
    void multiWordAbsolutes() {
        assertThat(engine.analyze("Without exception, the policy applies.")
                .absoluteHits()).extracting(ClaimRuleEngine.RuleHit::term)
                .contains("without exception");
        assertThat(engine.analyze("The finding is beyond doubt.")
                .absoluteHits()).extracting(ClaimRuleEngine.RuleHit::term)
                .contains("beyond doubt");
    }

    @Test
    @DisplayName("penalty is capped and never exceeds the configured maximum")
    void penaltyIsCapped() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze(
                "It is always, never, everyone, no one, all, certainly, definitely and guaranteed, "
                        + "absolutely, unquestionably, invariably, forever, exclusively.");
        assertThat(result.penalty()).isLessThanOrEqualTo(0.40);
    }

    @Test
    @DisplayName("penalty scales with the number of hits")
    void penaltyScales() {
        double one = engine.analyze("The firm reportedly expanded.").penalty();
        double three = engine.analyze(
                "The firm reportedly, allegedly and purportedly expanded.").penalty();
        assertThat(three).isGreaterThan(one);
    }

    @Test
    @DisplayName("empty input is handled without throwing")
    void emptyInput() {
        assertThat(engine.analyze(null).penalty()).isZero();
        assertThat(engine.analyze("").penalty()).isZero();
        assertThat(engine.analyze("   ").penalty()).isZero();
    }

    @Test
    @DisplayName("analysis is deterministic")
    void deterministic() {
        String text = "It is always reported that the acquisition was possibly completed.";
        assertThat(engine.analyze(text)).isEqualTo(engine.analyze(text));
    }

    @Test
    @DisplayName("the result carries its rule version and a human-readable explanation")
    void resultIsSelfDescribing() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze("The claim is always true.");
        assertThat(result.ruleVersion()).isEqualTo("RULE_V1");
        assertThat(result.explanation())
                .contains("Absolute terms detected")
                .contains("RULE_V1".substring(0, 0) + "Rule penalty");
    }

    @Test
    @DisplayName("punctuation and case do not defeat matching")
    void normalizesInput() {
        ClaimRuleEngine.RuleAnalysis result = engine.analyze("ALWAYS, DEFINITELY, GUARANTEED.");
        assertThat(result.absoluteHits()).hasSize(3);
    }
}
