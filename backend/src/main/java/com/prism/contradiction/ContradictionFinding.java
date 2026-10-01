package com.prism.contradiction;

import java.util.List;

/**
 * A deterministic finding that two approved records cannot both be true.
 *
 * <p>Produced by {@link ContradictionDetector}, never by a model. The rule that
 * fired is recorded so a human can audit why the system believed a conflict
 * existed, and so a rule change can be evaluated against historical findings.
 */
public record ContradictionFinding(
        String type,
        String subjectText,
        String predicate,
        String leftDescription,
        String rightDescription,
        String ruleCode,
        String ruleVersion,
        String explanation,
        Long leftTripleId,
        Long rightTripleId,
        Long leftClaimId,
        Long rightClaimId,
        String conflictHash) {

    /** Conflict classes the detector can assert. */
    public static final String TYPE_RELATION = "RELATION_CONFLICT";
    public static final String TYPE_POLARITY = "POLARITY_CONFLICT";
    public static final String TYPE_VERDICT = "VERDICT_CONFLICT";

    /** Stable identity of a conflict, so a re-scan does not duplicate it. */
    public static String buildHash(String type, String subject, String predicate,
                                   Long leftId, Long rightId) {
        // Order-independent: a conflict found as (A,B) and (B,A) is one conflict.
        long lo = Math.min(nz(leftId), nz(rightId));
        long hi = Math.max(nz(leftId), nz(rightId));
        String material = type + "|" + norm(subject) + "|" + (predicate == null ? "" : predicate)
                + "|" + lo + "|" + hi;
        return com.prism.common.Hashing.sha256Hex(material);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public List<Long> sourceTripleIds() {
        return java.util.stream.Stream.of(leftTripleId, rightTripleId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<Long> sourceClaimIds() {
        return java.util.stream.Stream.of(leftClaimId, rightClaimId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
