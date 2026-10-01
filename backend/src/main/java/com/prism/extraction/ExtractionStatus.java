package com.prism.extraction;

/** Lifecycle of an extraction run. Recovery depends on these states being accurate. */
public enum ExtractionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
