package com.prism.claims;

/**
 * Verdict taxonomy.
 *
 * <p>The point of keeping these separate is that they mean genuinely different
 * things and drive different downstream behaviour:
 *
 * <ul>
 *   <li>{@link #SUPPORTED} — evidence establishes the claim.</li>
 *   <li>{@link #CONTRADICTED} — evidence refutes the claim. Requires evidence
 *       to exist; a claim cannot be contradicted by nothing.</li>
 *   <li>{@link #INSUFFICIENT_EVIDENCE} — relevant evidence exists but does not
 *       settle the question.</li>
 *   <li>{@link #EXAGGERATED} — the claim is directionally right but overstates
 *       strength, scope, or universality.</li>
 *   <li>{@link #SOURCE_MISSING} — no relevant evidence was retrieved at all.
 *       This is a statement about the corpus, not about the claim's truth.</li>
 * </ul>
 *
 * <p>Collapsing any of these into a single "UNSUPPORTED" destroys the
 * distinction between "we found nothing" and "we found the opposite", which is
 * the single most common way an RAG system misleads its reader.
 */
public enum VerdictType {
    SUPPORTED,
    CONTRADICTED,
    INSUFFICIENT_EVIDENCE,
    EXAGGERATED,
    SOURCE_MISSING;

    /** Whether this verdict asserts something about the claim's truth. */
    public boolean isPositive() {
        return this == SUPPORTED;
    }

    /** Whether this verdict requires at least one retrieved passage to be valid. */
    public boolean requiresEvidence() {
        return this == SUPPORTED || this == CONTRADICTED || this == EXAGGERATED;
    }

    /** Whether a human must review this before it is treated as settled. */
    public boolean requiresAdjudication() {
        return this == CONTRADICTED || this == EXAGGERATED || this == SOURCE_MISSING;
    }
}
