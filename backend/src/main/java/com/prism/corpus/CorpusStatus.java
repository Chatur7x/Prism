package com.prism.corpus;

/** Lifecycle of a corpus. Only ACTIVE corpora participate in retrieval and graph queries. */
public enum CorpusStatus {
    ACTIVE,
    ARCHIVED
}
