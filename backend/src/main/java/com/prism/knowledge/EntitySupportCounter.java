package com.prism.knowledge;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The transactional increment of an entity's support count.
 *
 * <p>A separate bean for one reason, and it is the same reason
 * {@code ExtractionPersistenceService}, {@code ProposalWriter}, and
 * {@code BackgroundJobService} are all separate beans: Spring's transaction
 * advice is proxy-based, so a {@code @Transactional} method invoked from inside
 * its own class never passes through the proxy. The annotation is then silently
 * ignored, no transaction is opened, and any {@code @Modifying} query fails with
 * "No EntityManager with actual transaction available for current thread".
 *
 * <p>Keeping the single statement in its own tiny bean also keeps the retry
 * wrapper in {@link EntityStoreService} honest: the retry loop and the
 * transaction it retries are genuinely separate objects, so every attempt really
 * does get its own short transaction.
 */
@Service
public class EntitySupportCounter {

    private final EntityRepository entities;

    public EntitySupportCounter(EntityRepository entities) {
        this.entities = entities;
    }

    /**
     * Increments the counter in its own transaction and reports whether the row
     * was still there.
     *
     * <p>Short by design: it must never be combined with the proposal writes, or
     * the entity row lock would be held for the whole write and concurrent
     * documents would serialise behind it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean increment(Long entityId, Long chunkId, Instant now) {
        return entities.incrementSupport(entityId, chunkId, now) > 0;
    }
}
