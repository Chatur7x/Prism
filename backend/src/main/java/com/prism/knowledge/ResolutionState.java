package com.prism.knowledge;

/** How an entity's identity was decided. */
public enum ResolutionState {
    /** Confidently the same identity. */
    MERGE,
    /** Confidently a distinct identity. */
    KEEP_SEPARATE,
    /** Ambiguous; a human should decide. Never auto-resolved. */
    REVIEW
}
