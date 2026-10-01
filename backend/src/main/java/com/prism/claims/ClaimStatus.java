package com.prism.claims;

/**
 * Claim lifecycle. A claim reaches the verification layer only once a human has
 * approved it, but it is a separate decision from a triple's: a claim is a
 * proposition to be tested, and a triple is an asserted fact.
 */
public enum ClaimStatus {
    /** Extracted, not yet reviewed. Not part of trusted knowledge. */
    PROPOSED,
    /** A Verifier approved the claim for verification. */
    APPROVED,
    /** A Verifier rejected the claim outright. */
    REJECTED,
    /** Verification completed. See the associated verdict. */
    VERIFIED,
    /** Verification completed and a human adjudicated a contested outcome. */
    ADJUDICATED
}
