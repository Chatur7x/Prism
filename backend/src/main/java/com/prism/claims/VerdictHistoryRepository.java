package com.prism.claims;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VerdictHistoryRepository extends JpaRepository<VerdictHistory, Long> {

    List<VerdictHistory> findByClaimIdOrderBySupersededAtDesc(Long claimId);

    void deleteByClaimId(Long claimId);
}
