package com.prism.contradiction;

import com.prism.corpus.Corpus;
import com.prism.claims.ClaimPolarity;
import com.prism.claims.ClaimRepository;
import com.prism.claims.VerdictRepository;
import com.prism.claims.VerdictType;
import com.prism.knowledge.PredicateSemanticRegistry;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Runs the deterministic contradiction detector over a corpus and persists
 * what it finds.
 *
 * <p>Idempotent: findings are keyed by a stable conflict hash, so re-scanning
 * after every approval does not create duplicates. A re-scan also refreshes
 * findings whose source records have since been resolved.
 */
@Service
public class ContradictionScanService {

    private static final Logger log = LoggerFactory.getLogger(ContradictionScanService.class);

    private final ContradictionRepository contradictions;
    private final TripleRepository triples;
    private final ClaimRepository claims;
    private final VerdictRepository verdicts;
    private final ContradictionDetector detector;
    private final PredicateSemanticRegistry predicates;

    public ContradictionScanService(ContradictionRepository contradictions, TripleRepository triples,
                                   ClaimRepository claims, VerdictRepository verdicts,
                                   PredicateSemanticRegistry predicates) {
        this.contradictions = contradictions;
        this.triples = triples;
        this.claims = claims;
        this.verdicts = verdicts;
        this.predicates = predicates;
        this.detector = new ContradictionDetector(predicates);
    }

    public record ScanResult(int created, int unchanged, int findings, Long traceRunId) {
    }

    /**
     * Scans a corpus the caller has already been authorized for.
     *
     * <p>Authorization happens before this method is called, not inside it,
     * because {@code ApprovalService} also triggers a scan and must not be able
     * to do so for a corpus it has not checked. The {@link Corpus} arrives
     * already resolved by {@link com.prism.corpus.CorpusAccessService} — the
     * single authority on corpus access — so this method never loads a corpus by
     * id on its own, which is what previously made an empty corpus look
     * nonexistent.
     */
    @Transactional
    public ScanResult scanAuthorized(Corpus corpus, Long traceRunId) {
        Long corpusId = corpus.getId();
        List<Triple> approved = triples.findApprovedInCorpus(corpusId);

        List<ContradictionDetector.ApprovedTriple> tripleViews = approved.stream()
                .map(ContradictionScanService::toView)
                .toList();

        List<ContradictionDetector.ApprovedClaim> claimViews =
                claims.findApprovedInCorpus(corpusId).stream()
                        .map(ContradictionScanService::toView)
                        .toList();

        List<ContradictionDetector.VerdictRecord> verdictViews = verdicts.findRecent(corpusId,
                        org.springframework.data.domain.PageRequest.of(0, 500)).stream()
                .filter(v -> v.getClaim().getPredicate() != null)
                .map(v -> new ContradictionDetector.VerdictRecord(
                        v.getClaim().getId(),
                        v.getClaim().getSubject(),
                        v.getClaim().getPredicate(),
                        v.getClaim().getObjectText(),
                        v.getClaim().getPolarity(),
                        v.getVerdictType() == VerdictType.CONTRADICTED,
                        v.getVerdictReason()))
                .toList();

        List<ContradictionFinding> findings =
                detector.detectAll(tripleViews, claimViews, verdictViews);

        int created = 0;
        int unchanged = 0;
        for (ContradictionFinding finding : findings) {
            if (contradictions.findByCorpusIdAndConflictHash(corpusId, finding.conflictHash()).isPresent()) {
                unchanged++;
                continue;
            }
            contradictions.save(new Contradiction(corpus, finding, traceRunId));
            created++;
        }
        if (created > 0) {
            log.info("Contradiction scan for corpus {} created {} new findings ({} unchanged, {} total)",
                    corpusId, created, unchanged, findings.size());
        }
        return new ScanResult(created, unchanged, findings.size(), traceRunId);
    }

    static ContradictionDetector.ApprovedTriple toView(Triple t) {
        return new ContradictionDetector.ApprovedTriple(
                t.getId(),
                t.getSubject(),
                t.getPredicate(),
                t.getObject(),
                t.getSubjectEntity().getNormalizedName(),
                null,
                "document \"" + t.getSourceChunk().getDocument().getTitle()
                        + "\", chunk " + t.getSourceChunk().getChunkIndex());
    }

    static ContradictionDetector.ApprovedClaim toView(com.prism.claims.Claim c) {
        return new ContradictionDetector.ApprovedClaim(
                c.getId(),
                c.getSubject(),
                c.getSubject(),
                c.getPredicate(),
                c.getObjectText(),
                c.getPolarity(),
                c.getClaimText(),
                c.getEffectiveTime(),
                "document \"" + c.getSourceChunk().getDocument().getTitle()
                        + "\", chunk " + c.getSourceChunk().getChunkIndex());
    }
}
