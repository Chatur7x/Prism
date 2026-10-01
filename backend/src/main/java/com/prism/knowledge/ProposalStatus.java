package com.prism.knowledge;

/**
 * Proposal lifecycle.
 *
 * <p>Only {@link #APPROVED} knowledge enters the trusted graph. {@link #REJECTED}
 * is retained permanently with its provenance — deleting a rejection would
 * destroy the audit trail and make the same bad extraction reappear as new.
 */
public enum ProposalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
