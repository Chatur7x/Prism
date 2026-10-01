package com.prism.contradiction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContradictionRepository extends JpaRepository<Contradiction, Long> {

    @EntityGraph(attributePaths = "corpus")
    @Override
    Optional<Contradiction> findById(Long id);

    @EntityGraph(attributePaths = "corpus")
    Optional<Contradiction> findByIdAndCorpusId(Long id, Long corpusId);

    @EntityGraph(attributePaths = "corpus")
    Optional<Contradiction> findByCorpusIdAndConflictHash(Long corpusId, String conflictHash);


    @EntityGraph(attributePaths = "corpus")
    Page<Contradiction> findByCorpusIdOrderByCreatedAtDesc(Long corpusId, Pageable pageable);

    @EntityGraph(attributePaths = "corpus")
    Page<Contradiction> findByCorpusIdAndStatusOrderByCreatedAtDesc(Long corpusId,
                                                                    ContradictionStatus status,
                                                                    Pageable pageable);

    @EntityGraph(attributePaths = "corpus")
    List<Contradiction> findByCorpusIdAndStatus(Long corpusId, ContradictionStatus status);

    long countByCorpusIdAndStatus(Long corpusId, ContradictionStatus status);

    long countByCorpusId(Long corpusId);

    @EntityGraph(attributePaths = "corpus")
    @Query("select c from Contradiction c where c.corpus.id = :corpusId and c.status = 'OPEN' "
            + "order by c.createdAt desc")
    List<Contradiction> findOpenInCorpus(@Param("corpusId") Long corpusId);

    @Query("select count(c) from Contradiction c where c.corpus.id = :corpusId and c.status = :status")
    long countByCorpusAndStatus(@Param("corpusId") Long corpusId, @Param("status") ContradictionStatus status);
}
