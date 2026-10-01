package com.prism.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);

    Page<ChatSession> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    List<ChatSession> findByUserIdAndCorpusIdOrderByUpdatedAtDesc(Long userId, Long corpusId);

    long countByCorpusId(Long corpusId);
}
