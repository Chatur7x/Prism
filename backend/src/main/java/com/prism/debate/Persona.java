package com.prism.debate;

/** The three council personas. Names are fixed; behaviour is prompt-driven. */
public enum Persona {
    /** Argues the strongest reading the evidence supports. */
    HAWK,
    /** Argues the most cautious reading the evidence supports. */
    DOVE,
    /**
     * Tests both positions against machine-derived facts: live verdicts,
     * PageRank, community membership, and the graph record.
     */
    SKEPTIC
}
