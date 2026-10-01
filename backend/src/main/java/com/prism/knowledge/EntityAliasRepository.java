package com.prism.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EntityAliasRepository extends JpaRepository<EntityAlias, Long> {

    Optional<EntityAlias> findByCorpusIdAndNormalizedAlias(Long corpusId, String normalizedAlias);

    @Query("select a from EntityAlias a join fetch a.entity e where e.corpus.id = :corpusId and e.id = :entityId")
    List<EntityAlias> findByEntityIdInCorpus(@Param("entityId") Long entityId, @Param("corpusId") Long corpusId);

    long countByEntityId(Long entityId);
}
