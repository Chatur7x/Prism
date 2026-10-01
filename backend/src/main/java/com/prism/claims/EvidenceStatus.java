package com.prism.claims;

/** Whether the retrieval step found usable evidence. Distinct from the verdict itself. */
public enum EvidenceStatus {
    /** At least one passage was retrieved and offered to the judge. */
    EVIDENCE_FOUND,
    /** Nothing relevant was retrieved. The verdict must then be SOURCE_MISSING. */
    NO_EVIDENCE,
    /**
     * A retrieval attempt tried to cross the corpus boundary and was blocked.
     * Recorded rather than silently ignored: it is a signal that something is
     * wrong with a caller's scoping, and hiding it would mask a real defect.
     */
    CROSS_CORPUS_ATTEMPT_BLOCKED
}
