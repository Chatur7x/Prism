package com.prism.knowledge;

import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException; // documents which concrete type surfaces
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Concurrency-safe entity writes.
 *
 * <p>Extraction of a multi-document corpus resolves the same entity from many
 * worker threads at once. Two failure modes follow from that, and both were
 * observed on a 24-memo corpus before this class existed:
 *
 * <ol>
 *   <li><b>Duplicate insert.</b> Two workers both miss the lookup and both
 *       insert, so the unique index on {@code (corpus_id, normalized_name)}
 *       rejects the second with {@code DataIntegrityViolationException} and the
 *       whole document fails.</li>
 *   <li><b>Lost support count.</b> Two workers both load the row, both
 *       increment {@code supportCount}, and the second write fails with
 *       {@code OptimisticLockingFailureException} because its version is stale —
 *       or worse, succeeds against a stale read and discards the first
 *       increment.</li>
 * </ol>
 *
 * <p>Both are fixed structurally rather than with retry loops:
 *
 * <ul>
 *   <li>The counter is incremented by a single atomic {@code UPDATE
 *       support_count = support_count + 1}. The database, not the application
 *       layer, serialises it, so there is no read-modify-write window at all.
 *   </li>
 *   <li>The insert runs in its own transaction and treats the unique-constraint
 *       violation as a <em>signal that someone else won the race</em>, not as a
 *       failure. The winner's row is then re-read. Isolating it in its own
 *       transaction matters: a constraint violation marks the transaction
 *       rollback-only, so catching it in the caller's transaction would poison
 *       every later statement on that connection.</li>
 * </ul>
 *
 * <p>Nothing here returns a managed {@link Entity}. A snapshot leaves the
 * session, so no caller can trip over a lazy proxy after the transaction closes.
 */
@Service
public class EntityStoreService {

    private static final Logger log = LoggerFactory.getLogger(EntityStoreService.class);

    /**
     * Attempts allowed for a contended support-count increment.
     *
     * <p>Four is enough to ride out a burst of concurrent documents without
     * turning a hot counter into a long serialised queue.
     */
    private static final int MAX_SUPPORT_ATTEMPTS = 4;

    private final EntityRepository entities;
    private final CorpusRepository corpora;
    private final EntitySupportCounter counter;

    public EntityStoreService(EntityRepository entities, CorpusRepository corpora,
                             EntitySupportCounter counter) {
        this.entities = entities;
        this.corpora = corpora;
        this.counter = counter;
    }

    /**
     * The subset of an entity that callers outside a transaction may read.
     *
     * <p>A record rather than the entity itself, so a value read here stays valid
     * after the session closes.
     */
    public record Snapshot(Long id, String displayName, String normalizedName, String type,
                           ResolutionState resolutionState, int supportCount, Long firstSeenChunkId) {
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> findByKey(Long corpusId, String normalizedKey) {
        return entities.findByCorpusIdAndNormalizedName(corpusId, normalizedKey).map(EntityStoreService::toSnapshot);
    }

    /**
     * Increments the support count atomically, retrying transient lock failures.
     *
     * <p>No version round-trip, so concurrent callers neither lose an increment
     * nor fail on a stale version.
     *
     * <p><b>Why retry.</b> Entity rows are the hottest rows in the system — every
     * document in a corpus mentions the same entities, and documents are
     * extracted concurrently — so InnoDB occasionally times out waiting for a row
     * lock (SQLSTATE 40001). That is transient by definition, and the
     * alternative is failing an entire document over an advisory counter.
     *
     * <p><b>Known imprecision, accepted deliberately.</b> Retrying a
     * non-idempotent increment can over-count by one if the first attempt
     * committed but the client saw a failure. {@code supportCount} is an advisory
     * ranking signal used for entity listing order and for the Skeptic's brief. It
     * never gates approval, a verdict, or a contradiction finding, so a marginal
     * over-count is harmless where a lost document would not be.
     *
     * @return false when the entity no longer exists, or every attempt contended
     */
    public boolean recordSupport(Long entityId, Long chunkId, Instant now) {
        for (int attempt = 1; attempt <= MAX_SUPPORT_ATTEMPTS; attempt++) {
            try {
                // counter is a separate bean, so this really is a fresh short
                // transaction per attempt rather than a self-invoked no-op.
                return counter.increment(entityId, chunkId, now);
            } catch (PessimisticLockingFailureException | QueryTimeoutException ex) {
                if (attempt == MAX_SUPPORT_ATTEMPTS) {
                    log.warn("Support count for entity {} contended {} times; it is advisory, "
                            + "so extraction continues without it", entityId, attempt);
                    return false;
                }
                // Short, growing backoff. Kept far below the 50s InnoDB default
                // lock-wait timeout so the retry lands while there is still time to
                // succeed, rather than after another full timeout.
                sleepQuietly(attempt * 40L);
                log.debug("Entity {} support increment contended (attempt {}/{}), retrying",
                        entityId, attempt, MAX_SUPPORT_ATTEMPTS);
            }
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            // Preserve the interrupt: a cancelled extraction must not quietly
            // continue doing work.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Inserts a new entity unless the key is already taken.
     *
     * @return the stored snapshot, or empty when a concurrent worker won the race
     *         and the caller should re-read
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Snapshot> insertIfAbsent(Long corpusId, String displayName, String normalizedKey,
                                             Long firstSeenChunkId, Instant now) {
        // Re-check inside this transaction. It narrows the window but cannot
        // close it — that is what the constraint below is for.
        if (entities.existsByCorpusIdAndNormalizedName(corpusId, normalizedKey)) {
            return Optional.empty();
        }
        Corpus corpus = corpora.getReferenceById(corpusId);
        Entity created = new Entity(corpus, displayName, normalizedKey, "UNSPECIFIED", firstSeenChunkId);
        try {
            // Flush eagerly: the constraint violation must surface here, inside
            // this transaction, not at an arbitrary commit later on.
            return Optional.of(toSnapshot(entities.saveAndFlush(created)));
        } catch (DataIntegrityViolationException ex) {
            log.debug("Entity '{}' was created concurrently in corpus {}; deferring to the winner",
                    normalizedKey, corpusId);
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> findById(Long entityId) {
        return entities.findById(entityId).map(EntityStoreService::toSnapshot);
    }

    private static Snapshot toSnapshot(Entity e) {
        return new Snapshot(e.getId(), e.getDisplayName(), e.getNormalizedName(), e.getType(),
                e.getResolutionState(), e.getSupportCount(), e.getFirstSeenChunkId());
    }
}
