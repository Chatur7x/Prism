package com.prism.claims;

/**
 * Polarity of an extracted assertion.
 *
 * <p>Polarity alone does not determine contradiction. Two POSITIVE claims about
 * the same subject and predicate with different objects are frequently
 * compatible; contradiction requires predicate semantics to agree.
 */
public enum ClaimPolarity {
    POSITIVE,
    NEGATIVE,
    NEUTRAL
}
