package com.prism.knowledge;

import com.prism.common.error.ApiException;
import com.prism.corpus.Corpus;
import com.prism.document.DocumentChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolution of a surface form to an entity, and creation when it is new.
 *
 * <p>Wraps the pure {@link EntityResolver} with the database lookups it needs.
 * All lookups are corpus-scoped: an entity id from another corpus is treated as
 * absent, so a mis-scoped call fails closed rather than crossing the isolation
 * boundary.
 *
 * <p><b>Concurrency.</b> Extraction resolves the same entity from several worker
 * threads at once, so every write here is delegated to
 * {@link EntityStoreService}, which uses an atomic counter update and a
 * duplicate-tolerant insert. This class orchestrates; it does not write. That
 * split is what keeps a 24-document corpus from failing half its jobs on
 * duplicate-key and stale-version errors.
 *
 * <p><b>Returns snapshots, not entities.</b> {@code spring.jpa.open-in-view} is
 * disabled, so a managed entity handed out of a transaction becomes a
 * landmine. Callers that need a real {@link Entity} reference — to assign a
 * {@code @ManyToOne} — call {@link #reference(Long)} from inside their own
 * transaction.
 */
@Service
public class EntityResolutionService {

    private final EntityRepository entities;
    private final EntityAliasRepository aliases;
    private final EntityStoreService store;
    private final EntityResolver resolver = new EntityResolver();

    public EntityResolutionService(EntityRepository entities, EntityAliasRepository aliases,
                                   EntityStoreService store) {
        this.entities = entities;
        this.aliases = aliases;
        this.store = store;
    }

    /**
     * Resolves a surface form to an entity, creating one if the identity is new.
     *
     * <p>Not transactional by design: it is pure orchestration over
     * {@link EntityStoreService}, each step of which owns a short transaction of
     * its own. Wrapping the whole thing in one transaction would hold a
     * connection open across a create-or-race-retry and would mark the
     * transaction rollback-only when the insert lost the race.
     *
     * @return a snapshot, or empty when the surface form is unusable or the
     *         identity is genuinely ambiguous and needs human review
     */
    public Optional<EntityStoreService.Snapshot> resolveOrCreate(Long corpusId, String surfaceForm,
                                                                Long sourceChunkId) {
        String key = EntityNormalizer.normalizeKey(surfaceForm);
        if (key.isEmpty()) {
            return Optional.empty();
        }

        // 1. The unique index on (corpus_id, normalized_name) is the authority.
        Optional<EntityStoreService.Snapshot> direct = store.findByKey(corpusId, key);
        if (direct.isPresent()) {
            store.recordSupport(direct.get().id(), sourceChunkId, Instant.now());
            return store.findByKey(corpusId, key);
        }

        // 2. An alias recorded for an existing entity.
        Optional<AliasHit> aliasHit = aliases.findByCorpusIdAndNormalizedAlias(corpusId, key)
                .map(a -> new AliasHit(a.getEntity().getId(), a.getId()));
        if (aliasHit.isPresent()) {
            store.recordSupport(aliasHit.get().entityId(), sourceChunkId, Instant.now());
            aliases.findById(aliasHit.get().aliasId()).ifPresent(a -> {
                a.recordSupport();
                aliases.save(a);
            });
            return store.findById(aliasHit.get().entityId());
        }

        // 3. New identity. insertIfAbsent returns empty when a concurrent worker
        //    won the unique-key race, in which case its row is adopted.
        Optional<EntityStoreService.Snapshot> created = store.insertIfAbsent(corpusId,
                EntityNormalizer.normalizeDisplay(surfaceForm), key, sourceChunkId, Instant.now());
        return created.isPresent() ? created : store.findByKey(corpusId, key);
    }

    private record AliasHit(Long entityId, Long aliasId) {
    }

    /**
     * A managed {@link Entity} reference for use inside the caller's transaction.
     *
     * <p>A proxy, not a database load: the caller only needs the id for a
     * {@code @ManyToOne} join column, and forcing a load per endpoint would turn
     * one extraction into several needless queries.
     */
    public Entity reference(Long entityId) {
        return entities.getReferenceById(entityId);
    }

    /** Resolution decision without writing, for the review UI. */
    @Transactional(readOnly = true)
    public EntityResolver.Decision analyze(Corpus corpus, String surfaceForm) {
        List<EntityResolver.KnownEntity> known = entities.findLiveInCorpus(corpus.getId()).stream()
                .map(e -> new EntityResolver.KnownEntity(e.getId(), e.getDisplayName(),
                        e.getNormalizedName(), e.getResolutionState()))
                .toList();
        return resolver.resolve(surfaceForm, known, knownAliases(corpus));
    }

    /**
     * All aliases in a corpus, for the resolver's alias pass.
     * Fetched corpus-scoped so a lookup can never match an alias from elsewhere.
     */
    @Transactional(readOnly = true)
    public List<EntityResolver.KnownAlias> knownAliases(Corpus corpus) {
        return entities.findLiveInCorpus(corpus.getId()).stream()
                .flatMap(e -> aliases.findByEntityIdInCorpus(e.getId(), corpus.getId()).stream())
                .map(a -> new EntityResolver.KnownAlias(a.getNormalizedAlias(), a.getEntity().getId()))
                .collect(Collectors.toList());
    }

    /** Records a surface form as an additional name for an existing entity. */
    @Transactional
    public EntityAlias addAlias(Long entityId, String aliasText) {
        Entity entity = entities.findById(entityId)
                .orElseThrow(() -> ApiException.notFound("Entity", entityId));
        String normalized = EntityNormalizer.normalizeKey(aliasText);
        if (normalized.isEmpty()) {
            throw ApiException.validation("alias text is required");
        }
        Optional<EntityAlias> existing =
                aliases.findByCorpusIdAndNormalizedAlias(entity.getCorpus().getId(), normalized);
        if (existing.isPresent()) {
            if (!existing.get().getEntity().getId().equals(entityId)) {
                // The alias already belongs to a different entity. Merging
                // implicitly here would be exactly the silent-merge bug this
                // design exists to prevent.
                throw ApiException.conflict("alias '" + aliasText + "' is already recorded for entity "
                        + existing.get().getEntity().getId() + "; resolve the identity conflict explicitly");
            }
            existing.get().recordSupport();
            return aliases.save(existing.get());
        }
        return aliases.save(new EntityAlias(entity, aliasText, normalized));
    }

    @Transactional(readOnly = true)
    public Optional<Entity> findInCorpus(Long entityId, Long corpusId) {
        return entities.findByIdAndCorpusId(entityId, corpusId);
    }

    /**
     * Flags an entity for human review, without merging or discarding it.
     *
     * <p>Uses the single-statement update rather than load/mutate/save: a reviewer
     * flagging an entity that extraction is concurrently incrementing would
     * otherwise fail on a stale version and lose the flag, which is precisely the
     * signal a reviewer most needs to be durable.
     */
    public void flagForReview(Long entityId) {
        entities.markResolution(entityId, ResolutionState.REVIEW, Instant.now());
    }
}
