package com.prism.debate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DebateRoundRepository extends JpaRepository<DebateRound, Long> {

    Optional<DebateRound> findByDebateIdAndRoundNumber(Long debateId, int roundNumber);

    List<DebateRound> findByDebateIdOrderByRoundNumberAsc(Long debateId);

    long countByDebateId(Long debateId);
}
