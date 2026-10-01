package com.prism.extraction;

/** Why a model response was quarantined. Kept as a closed set for audit queries. */
public enum QuarantineReason {
    /** Response body was not valid JSON. */
    MALFORMED_JSON,
    /** Valid JSON but failed bean validation (missing field, bad enum, bad length). */
    SCHEMA_VALIDATION_FAILED,
    /** Passed schema but failed semantic validation (fabricated sentence, unknown predicate). */
    SEMANTIC_VALIDATION_FAILED,
    /** Response exceeded the configured per-chunk object ceiling. */
    OBJECT_LIMIT_EXCEEDED,
    /** The provider call failed permanently. */
    PROVIDER_ERROR,
    /** The provider call exhausted its bounded retries. */
    PROVIDER_TIMEOUT,
    /** A repair attempt after malformed JSON also failed. */
    REPAIR_FAILED
}
