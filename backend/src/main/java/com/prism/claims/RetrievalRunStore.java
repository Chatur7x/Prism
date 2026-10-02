package com.prism.claims;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one database write a benchmark run needs outside its own loop.
 *
 * <p>Exists because of the project's transaction-proxy rule: a
 * {@code @Transactional} method called from inside its own class is invoked
 * directly, the proxy never runs, and the annotation is silently ignored.
 * {@link RetrievalEvaluationService#evaluate} is deliberately not transactional
 * -- it loops over retrieval, which issues its own queries -- so the delete that
 * discards the previous run has to be reached from outside the service.
 *
 * <p>{@code REQUIRES_NEW} rather than the default, so the discard commits even
 * if the caller later rolls back: the old figures should stop being current the
 * moment a new measurement begins, not only if the new one succeeds.
 */
@Component
public class RetrievalRunStore {

    private final RetrievalEvaluationRepository evaluations;

    public RetrievalRunStore(RetrievalEvaluationRepository evaluations) {
        this.evaluations = evaluations;
    }

    /** Discards a previous run of the same gold set. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int discard(Long corpusId, String runKey) {
        return evaluations.deleteRun(corpusId, runKey);
    }
}
