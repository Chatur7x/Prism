package com.prism.user;

/**
 * Closed role set. Authorization is always evaluated against this enum plus an
 * object-level access check — never against a role claimed by the client.
 */
public enum Role {
    /** Full system administration: users, health, system operations. */
    ADMIN,
    /** Ingestion, corpus management, proposed-knowledge inspection, grounded chat. */
    ANALYST,
    /** Approves triples, adjudicates verdicts, chairs debates. */
    VERIFIER
}
