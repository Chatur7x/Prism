package com.prism.contradiction;

/** Lifecycle of a detected contradiction. */
public enum ContradictionStatus {
    /** Detected, no debate convened. */
    OPEN,
    /** A debate is in progress. */
    IN_DEBATE,
    /** Resolved by synthesis. */
    RESOLVED,
    /** A human determined there is no genuine conflict. */
    DISMISSED
}
