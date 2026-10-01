package com.prism.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Document status transitions as isolated single-statement transactions.
 *
 * <p><b>Why this exists.</b> A {@link Document} is a versioned entity. If a
 * long-running operation (extraction spans one model call per chunk) holds a
 * reference loaded at the start and saves it several times, the second save
 * fails with {@code StaleObjectStateException}: the instance is detached, the
 * first {@code save} merged it into a different managed copy, and the local
 * {@code version} never advanced.
 *
 * <p>The fix is to never carry the entity across the operation. Each transition
 * here re-reads the row and writes it inside one short transaction, so the
 * version is always current. It also keeps the pipeline's long transaction from
 * holding a row lock while a model is being called.
 */
@Service
public class DocumentStatusService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStatusService.class);

    private final DocumentRepository documents;

    public DocumentStatusService(DocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * Moves a document to a new status.
     *
     * <p>Never throws: status updates are bookkeeping, and a failure here must
     * not abort the pipeline step that is actually doing the work. The document's
     * real outcome is recorded in the extraction run and the trace.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(Long documentId, DocumentStatus status) {
        try {
            documents.findById(documentId).ifPresent(document -> {
                document.transitionTo(status, Instant.now());
                documents.saveAndFlush(document);
            });
        } catch (RuntimeException ex) {
            log.warn("Could not set document {} to {}: {}", documentId, status, ex.getMessage());
        }
    }

    /**
     * Re-reads a document, so callers get a fully initialised, current instance
     * rather than a stale or detached one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Document reload(Long documentId) {
        return documents.findById(documentId).orElse(null);
    }
}
