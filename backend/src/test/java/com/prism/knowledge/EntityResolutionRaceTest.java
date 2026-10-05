package com.prism.knowledge;

import com.prism.extraction.ExtractionRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lost unique-key race during entity resolution, tested without a database.
 *
 * <p><b>The production incident.</b> A PDF demo corpus was uploaded for the first
 * time. Ten documents, one of which shared its principal entity with its
 * neighbour, and one document failed outright:
 *
 * <pre>
 *   Duplicate entry '20-delacroix' for key 'entities.uk_entity_corpus_key'
 *   Extraction failed for document 48
 *   UnexpectedRollbackException: Transaction silently rolled back because it
 *   has been marked as rollback-only
 *       at EntityResolutionService.resolveOrCreate(EntityResolutionService.java:90)
 * </pre>
 *
 * <p><b>Why the existing guard did not hold.</b> {@code insertIfAbsent} is
 * annotated {@code @Transactional(REQUIRES_NEW)} and wraps its insert in
 * {@code catch (DataIntegrityViolationException)}, returning empty so the caller
 * can adopt the row a concurrent worker won. That catch was dead code. The
 * constraint violation marks the transaction rollback-only at the moment it
 * happens, so by the time the catch block runs there is nothing left to salvage,
 * and Spring raises {@link UnexpectedRollbackException} at the proxy
 * boundary as the method returns. The class javadoc advertised a
 * "duplicate-tolerant insert" that could not tolerate anything.
 *
 * <p><b>Why the fix lives on the caller.</b> {@code resolveOrCreate} is
 * deliberately non-transactional. That is what makes recovery possible: with no
 * poisoned transaction still active, the read that follows the failed insert runs
 * in a clean transaction and sees the winner's committed row.
 *
 * <p>Sharing one entity across two documents is the normal case in this system,
 * not an edge case. It is why the constraint exists at all.
 */
@DisplayName("Entity resolution survives losing the unique-key race")
class EntityResolutionRaceTest {

    private static final long CORPUS_ID = 20L;
    private static final long CHUNK_ID = 7L;
    private static final String SURFACE = "Delacroix Group";

    private static EntityStoreService.Snapshot winner(long id, String key) {
        return new EntityStoreService.Snapshot(id, "Delacroix Group", key, "ORG",
                ResolutionState.MERGE, 1, CHUNK_ID);
    }

    private static EntityResolutionService serviceOver(EntityStoreService store) {
        return new EntityResolutionService(
                mock(EntityRepository.class),
                mock(EntityAliasRepository.class),
                store);
    }

    @Test
    @DisplayName("a rollback-only insert is a lost race, and the winner's row is adopted")
    void adoptsTheWinnersRowWhenTheInsertRollsBack() {
        String key = EntityNormalizer.normalizeKey(SURFACE);
        EntityStoreService store = mock(EntityStoreService.class);

        // First read: nobody holds this identity yet, so resolution proceeds to
        // create it. Second read: the recovery read, after the failed attempt.
        when(store.findByKey(CORPUS_ID, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner(91L, key)));

        // What actually happens when a concurrent worker wins: the insert
        // collides, the transaction is already poisoned, and the exception that
        // escapes is the rollback, not the constraint violation.
        when(store.insertIfAbsent(eq(CORPUS_ID), anyString(), eq(key), eq(CHUNK_ID), any()))
                .thenThrow(new UnexpectedRollbackException(
                        "Transaction silently rolled back because it has been marked as rollback-only"));

        Optional<EntityStoreService.Snapshot> resolved =
                serviceOver(store).resolveOrCreate(CORPUS_ID, SURFACE, CHUNK_ID);

        // The defect: this threw, and the throw propagated out and failed the
        // whole extraction job for the document.
        assertThat(resolved).as("a lost race must not fail the extraction").isPresent();
        assertThat(resolved.get().id())
                .as("the concurrent worker's row must be adopted, not replaced")
                .isEqualTo(91L);
    }

    @Test
    @DisplayName("a constraint violation is handled the same way as a rollback")
    void adoptsTheWinnersRowOnDataIntegrityViolation() {
        String key = EntityNormalizer.normalizeKey(SURFACE);
        EntityStoreService store = mock(EntityStoreService.class);
        when(store.findByKey(CORPUS_ID, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner(92L, key)));
        when(store.insertIfAbsent(eq(CORPUS_ID), anyString(), eq(key), eq(CHUNK_ID), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        Optional<EntityStoreService.Snapshot> resolved =
                serviceOver(store).resolveOrCreate(CORPUS_ID, SURFACE, CHUNK_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(92L);
    }

    @Test
    @DisplayName("an uncontested create returns the row that was written")
    void returnsTheCreatedRowDirectly() {
        String key = EntityNormalizer.normalizeKey(SURFACE);
        EntityStoreService store = mock(EntityStoreService.class);
        when(store.findByKey(CORPUS_ID, key)).thenReturn(Optional.empty());
        when(store.insertIfAbsent(eq(CORPUS_ID), anyString(), eq(key), eq(CHUNK_ID), any()))
                .thenReturn(Optional.of(winner(93L, key)));

        Optional<EntityStoreService.Snapshot> resolved =
                serviceOver(store).resolveOrCreate(CORPUS_ID, SURFACE, CHUNK_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(93L);
        // No second read: the insert succeeded, so re-reading would be waste.
        verify(store).findByKey(CORPUS_ID, key);
    }

    @Test
    @DisplayName("an existing identity is recorded as support, never re-created")
    void neverInsertsWhenTheIdentityAlreadyExists() {
        String key = EntityNormalizer.normalizeKey(SURFACE);
        EntityStoreService store = mock(EntityStoreService.class);
        when(store.findByKey(CORPUS_ID, key)).thenReturn(Optional.of(winner(94L, key)));

        Optional<EntityStoreService.Snapshot> resolved =
                serviceOver(store).resolveOrCreate(CORPUS_ID, SURFACE, CHUNK_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(94L);
        verify(store).recordSupport(anyLong(), any(), any());
        // The insert path must not even be attempted: an unnecessary insert is
        // exactly how a race gets started in the first place.
        verify(store, org.mockito.Mockito.never())
                .insertIfAbsent(anyLong(), anyString(), anyString(), any(), any());
    }
}

/**
 * The repository contract that made the progress endpoint return 500.
 *
 * <p>{@code findByDocumentIdOrderByIdDesc} returned {@code Optional<ExtractionRun>}
 * while ordering by id descending and nothing else. Spring Data sorts and then
 * calls {@code getSingleResult()}, which throws
 * {@code NonUniqueResultException} as soon as a document has two runs — and a
 * document gains a second run every time {@code /reprocess} is called. So the
 * progress endpoint 500'd for every document that had ever been reprocessed.
 *
 * <p>This asserts the method name carries the {@code findFirst} prefix, which is
 * what makes Spring Data apply {@code setMaxResults(1)}. It is a structural test
 * rather than a behavioural one on purpose: the failure mode is in the query
 * derivation, and the honest way to pin that is to assert the contract the
 * derivation depends on. The behavioural half — two real runs, latest wins — is
 * covered against a real MySQL in {@code ExtractionRunLookupIntegrationTest}.
 */
@DisplayName("The latest-run lookup is a single-row query")
class ExtractionRunRepositoryContractTest {

    @Test
    @DisplayName("the latest-run finder is declared with findFirst")
    void latestRunFinderLimitsToOneRow() throws Exception {
        java.lang.reflect.Method method =
                ExtractionRunRepository.class.getMethod("findFirstByDocumentIdOrderByIdDesc", Long.class);

        assertThat(method.getReturnType())
                .as("must return Optional, and findFirst is what makes it a single-row query")
                .isEqualTo(Optional.class);

        // The old name must not come back: it reads correctly and behaves
        // wrongly, which is the most expensive kind of bug to reintroduce.
        assertThat(java.util.Arrays.stream(ExtractionRunRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .as("no unlimited-row finder may be declared for a single run")
                .doesNotContain("findByDocumentIdOrderByIdDesc");
    }

    @Test
    @DisplayName("the stale-run recovery query is untouched by the rename")
    void staleRunQueryRemainsAvailable() {
        // Restart recovery reads RUNNING runs by heartbeat. The latest-run fix
        // must not have disturbed it, because a missing stale-run query would
        // silently disable crash recovery.
        assertThat(java.util.Arrays.stream(ExtractionRunRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .contains("findStaleRunning", "findByStatus");
    }
}