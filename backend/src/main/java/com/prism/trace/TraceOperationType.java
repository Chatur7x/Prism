package com.prism.trace;

/** Coarse operation grouping so the UI can list and filter runs. */
public enum TraceOperationType {
    DOCUMENT_INGESTION,
    EXTRACTION,
    VERIFICATION,
    CONTRADICTION_SCAN,
    DEBATE,
    SYNTHESIS,
    CHAT,
    GRAPH_COMPUTATION,
    ADMIN
}
