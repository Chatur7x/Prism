package com.prism.corpus;

import com.prism.user.User;
import com.prism.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CorpusRepository extends JpaRepository<Corpus, Long> {

    Page<Corpus> findByOwnerOrderByCreatedAtDesc(User owner, Pageable pageable);

    /** Corpus is visible if the user owns it or shares it. Sharing is the extension point. */
    @Query("select c from Corpus c join fetch c.owner where c.owner.id = :userId and c.status = 'ACTIVE' order by c.createdAt desc")
    List<Corpus> findActiveByOwner(@Param("userId") Long userId);

    @Query("select c from Corpus c join fetch c.owner where c.id = :id and c.status = 'ACTIVE'")
    Optional<Corpus> findActiveById(@Param("id") Long id);

    /**
     * Ownership check must not blow up after the transaction closes.
     * Controllers map {@code Corpus} to DTOs outside any session
     * (open-in-view is off), so the owner has to be fetched here.
     */
    @Query("select c from Corpus c join fetch c.owner where c.id = :id")
    Optional<Corpus> findByIdWithOwner(@Param("id") Long id);

    @Query("select c from Corpus c join fetch c.owner order by c.createdAt desc")
    List<Corpus> findAllWithOwner();

    long countByOwner(User owner);
}
