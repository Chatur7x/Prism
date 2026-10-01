package com.prism.claims;

/** Whether a verdict came from the model alone, was contested, or was decided by a human. */
public enum AdjudicationState {
    /** Machine verdict stands unreviewed. */
    MACHINE_ONLY,
    /** A human has been asked to review this outcome. */
    CONTESTED,
    /** A human decided. The machine verdict is preserved alongside. */
    HUMAN_DECISION
}
