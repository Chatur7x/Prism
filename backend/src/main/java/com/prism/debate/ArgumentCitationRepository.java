package com.prism.debate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ArgumentCitationRepository extends JpaRepository<ArgumentCitation, Long> {

    List<ArgumentCitation> findByArgumentId(Long argumentId);

    List<ArgumentCitation> findByArgumentIdIn(List<Long> argumentIds);

    @Query("select c from ArgumentCitation c join fetch c.argument a where a.debate.id = :debateId")
    List<ArgumentCitation> findByDebateId(@Param("debateId") Long debateId);
}
