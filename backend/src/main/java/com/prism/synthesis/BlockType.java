package com.prism.synthesis;

/**
 * What a report block asserts.
 *
 * <p>The type tells a reader how much weight to give the statement before
 * reading it, which matters when a report mixes settled findings with
 * unresolved questions.
 */
public enum BlockType {
    /** What the council concluded. */
    FINDING,
    /** Where the personas disagreed. */
    DISAGREEMENT,
    /** What the deterministic record says, independent of the arguments. */
    MACHINE_RECORD,
    /** What the evidence does not settle. */
    UNRESOLVED,
    /** What the council suggests doing next. */
    RECOMMENDATION
}
