package com.prism.trace;

/**
 * Who performed a step. This distinction is the core of the Glass Box: the
 * system must always be able to show whether a state change came from
 * deterministic code, a model proposal, or a human decision.
 */
public enum ActorType {
    /** Deterministic Java: the only component allowed to own state. */
    ENGINE,
    /** Model output. Always a proposal, never authoritative. */
    LLM,
    /** A human Verifier or Admin decision. Final authority. */
    HUMAN
}
