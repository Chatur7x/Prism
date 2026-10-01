package com.prism.claims;

/**
 * Deterministic fusion of the model score and the rule penalty.
 *
 * <p>Pure arithmetic, no model, no I/O.
 *
 * <p><b>Read this before interpreting the output.</b> {@code fusedScore} is a
 * ranking score used to order and filter verdicts. It is <b>not</b> a
 * calibrated probability. No calibration experiment (reliability diagram,
 * Brier decomposition, held-out isotonic fit) has been run against a labelled
 * dataset in this project, so a fused score of 0.9 does not mean "90% likely
 * to be correct". Presenting it as a probability would be a false precision
 * claim. See {@code docs/evaluation.md} for the calibration plan.
 */
public final class ConfidenceFusion {

    private ConfidenceFusion() {
    }

    /**
     * Fuses the judge score with the deterministic rule penalty.
     *
     * <p>The rule penalty is applied multiplicatively rather than subtracted so
     * the result stays inside [0, 1] regardless of how the components combine.
     *
     * @param llmScore    judge score in [0, 1], or null when the judge produced
     *                    no usable score
     * @param rulePenalty deduction from the rule engine, in [0, 1]
     * @return fused score in [0, 1], or null when there is nothing to fuse
     */
    public static Double fuse(Double llmScore, double rulePenalty) {
        if (llmScore == null) {
            return null;
        }
        double clampedScore = clamp(llmScore, 0.0, 1.0);
        double clampedPenalty = clamp(rulePenalty, 0.0, 1.0);
        double fused = clampedScore * (1.0 - clampedPenalty);
        return round3(clamp(fused, 0.0, 1.0));
    }

    /**
     * Resolves the effective verdict.
     *
     * <p>Two deterministic overrides apply before the judge's answer is trusted:
     *
     * <ol>
     *   <li>No evidence retrieved means {@link VerdictType#SOURCE_MISSING},
     *       whatever the judge said. A model that claims SUPPORTED while being
     *       shown nothing is hallucinating, and the deterministic layer must
     *       not accept that.</li>
     *   <li>Absolute overstatement plus weak judge confidence downgrades to
     *       {@link VerdictType#EXAGGERATED}, because a universal claim cannot be
     *       established by thin evidence.</li>
     * </ol>
     */
    public static VerdictType resolveVerdict(VerdictType judgeVerdict,
                                            EvidenceStatus evidenceStatus,
                                            double rulePenalty,
                                            Double llmScore) {
        if (evidenceStatus == EvidenceStatus.NO_EVIDENCE
                || evidenceStatus == EvidenceStatus.CROSS_CORPUS_ATTEMPT_BLOCKED) {
            return VerdictType.SOURCE_MISSING;
        }
        VerdictType base = judgeVerdict == null ? VerdictType.INSUFFICIENT_EVIDENCE : judgeVerdict;

        boolean overstates = rulePenalty >= 0.10;
        boolean thinEvidence = llmScore == null || llmScore < 0.6;
        if (base == VerdictType.SUPPORTED && overstates && thinEvidence) {
            return VerdictType.EXAGGERATED;
        }
        return base;
    }

    /**
     * Whether a machine verdict is strong enough to stand, or must be escalated
     * to a human Verifier.
     *
     * <p>Contested and missing-evidence outcomes are always surfaced. A human
     * decides; the machine does not get the last word.
     */
    public static boolean requiresHumanAdjudication(VerdictType verdict, EvidenceStatus evidenceStatus) {
        if (evidenceStatus != EvidenceStatus.EVIDENCE_FOUND) {
            return true;
        }
        return verdict != null && verdict.requiresAdjudication();
    }

    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
