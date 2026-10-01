package com.prism.document;

/**
 * Pipeline states for a document.
 *
 * <p>A document only reaches READY once its extraction run has finished and all
 * surviving proposals are in the approval queue. READY does not mean the
 * proposals were approved — it means no further automated work is pending.
 */
public enum DocumentStatus {
    UPLOADED,
    CHUNKING,
    CHUNKED,
    EXTRACTING,
    /** Extraction finished; proposals await a Verifier. Nothing is trusted yet. */
    AWAITING_APPROVAL,
    READY,
    FAILED
}
