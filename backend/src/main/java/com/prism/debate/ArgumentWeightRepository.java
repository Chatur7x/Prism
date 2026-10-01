package com.prism.debate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ArgumentWeightRepository extends JpaRepository<ArgumentWeight, Long> {

    /**
     * The most recent weight per argument for a debate.
     *
     * <p>Weights are append-only, so when a chair revises one the earlier
     * assessment stays in the table. Synthesis uses the latest, while the
     * Glass Box can show both.
     *
     * <p>{@code argument} and {@code verifier} are fetched because the response
     * carries both the argument id and the verifier's username, and the caller
     * maps this list after its transaction has closed. Without the fetch the
     * username resolves to null and every argument in the Council would appear
     * unweighted-by-anyone — erasing the human attribution that is the only
     * record that a person weighed in.
     *
     * <p>The inner {@code max} subquery is uncorrelated, so it needs no fetch of
     * its own; the outer fetch applies to the rows actually returned.
     */
    @Query("select w from ArgumentWeight w"
            + " join fetch w.argument a"
            + " join fetch w.verifier v"
            + " where w.id in (select max(w2.id) from ArgumentWeight w2"
            + "                where w2.debate.id = :debateId group by w2.argument.id)"
            + " order by w.id")
    List<ArgumentWeight> findLatestWeightsForDebate(@Param("debateId") Long debateId);

    List<ArgumentWeight> findByArgumentIdOrderByIdDesc(Long argumentId);

    List<ArgumentWeight> findByDebateIdOrderByIdAsc(Long debateId);

    Optional<ArgumentWeight> findByIdAndDebateId(Long id, Long debateId);

    long countByDebateId(Long debateId);
}
