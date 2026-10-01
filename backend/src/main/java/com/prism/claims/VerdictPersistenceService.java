package com.prism.claims;

import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Short-transaction persistence for verification results.
 *
 * <p>A separate bean from {@link VerificationService} on purpose: Spring's
 * transaction advice is proxy-based, so a {@code @Transactional} method invoked
 * from inside its own class never passes through the proxy and the annotation
 * silently does nothing. Splitting the boundary out is what makes these
 * transactions real.
 */
@Service
public class VerdictPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(VerdictPersistenceService.class);

    private final VerdictRepository verdicts;
    private final VerdictPassageRepository passages;
    private final VerdictHistoryRepository history;
    private final ClaimRepository claims;
    private final DocumentChunkRepository chunks;

    public VerdictPersistenceService(VerdictRepository verdicts, VerdictPassageRepository passages,
                                     VerdictHistoryRepository history, ClaimRepository claims,
                                     DocumentChunkRepository chunks) {
        this.verdicts = verdicts;
        this.passages = passages;
        this.history = history;
        this.claims = claims;
        this.chunks = chunks;
    }

    public record Persisted(Long verdictId, VerdictType effective, VerdictType machine,
                            Double llmScore, double rulePenalty, Double fusedScore,
                            EvidenceStatus evidenceStatus, AdjudicationState adjudicationState) {
    }

    /**
     * Writes the verdict, moving any prior verdict to history rather than
     * overwriting it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Persisted persistVerdict(Claim claim, VerdictType effective, VerdictType machine,
                                    Double llmScore, ClaimRuleEngine.RuleAnalysis analysis,
                                    EvidenceStatus evidenceStatus, String reason,
                                    String llmReasoning, String model, String retrievalQuery,
                                    Long traceRunId) {
        // Supersede, never overwrite: the previous assessment must stay auditable.
        verdicts.findByClaimId(claim.getId()).ifPresent(prior -> {
            history.save(new VerdictHistory(claim.getId(), claim.getCorpus().getId(), prior,
                    "superseded by a new verification run"));
            passages.deleteByVerdictId(prior.getId());
            verdicts.delete(prior);
            verdicts.flush();
        });

        Double fused = ConfidenceFusion.fuse(llmScore, analysis.penalty());
        Verdict verdict = new Verdict(claim, machine, evidenceStatus, llmScore, analysis.penalty(),
                fused, reason, llmReasoning, model, com.prism.llm.Prompts.JUDGE_V1,
                analysis.ruleVersion(), retrievalQuery, traceRunId);
        Verdict saved = verdicts.save(verdict);
        claim.markVerified(Instant.now());
        claims.save(claim);

        return new Persisted(saved.getId(), effective, machine, llmScore, analysis.penalty(),
                fused, evidenceStatus, saved.getAdjudicationState());
    }

    /**
     * Links evidence to a verdict.
     *
     * <p>Each chunk is re-checked against the verdict's corpus before linking.
     * The retrieval query is already corpus-scoped; this is the second lock on
     * the same door, so a query defect cannot attach another corpus's text as
     * evidence for this claim.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int persistEvidence(Long verdictId, Long corpusId,
                               List<RetrievalService.RetrievedPassage> retrieved) {
        Verdict verdict = verdicts.findById(verdictId).orElse(null);
        if (verdict == null) {
            return 0;
        }
        int linked = 0;
        for (RetrievalService.RetrievedPassage p : retrieved) {
            var chunk = chunks.findByIdAndCorpusId(p.chunkId(), corpusId);
            if (chunk.isEmpty()) {
                log.warn("Refusing to attach chunk {} as evidence: it is not in corpus {}",
                        p.chunkId(), corpusId);
                continue;
            }
            DocumentChunk managed = chunk.get();
            passages.save(new VerdictPassage(verdict, verdict.getCorpus(), managed,
                    managed.getDocument(), p.text(), p.rank(), p.score()));
            linked++;
        }
        return linked;
    }
}
