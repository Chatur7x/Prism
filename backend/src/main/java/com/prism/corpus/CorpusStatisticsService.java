package com.prism.corpus;

import com.prism.claims.AdjudicationState;
import com.prism.claims.ClaimRepository;
import com.prism.claims.ClaimStatus;
import com.prism.claims.VerdictRepository;
import com.prism.claims.VerdictType;
import com.prism.contradiction.ContradictionRepository;
import com.prism.contradiction.ContradictionStatus;
import com.prism.debate.DebateRepository;
import com.prism.debate.DebateState;
import com.prism.document.Document;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentStatus;
import com.prism.extraction.ExtractionQuarantineRepository;
import com.prism.knowledge.ProposalStatus;
import com.prism.knowledge.TripleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corpus-wide pipeline counters.
 *
 * <p>Counted with {@code countBy...} projections rather than by loading rows:
 * the corpora list view needs these for every corpus the caller can see, and
 * materialising thousands of entities to draw a row of numbers would be absurd.
 *
 * <p>Distinct from a status breakdown, which is what a reviewer actually needs
 * before deciding whether to act: knowing there are 40 pending claims without
 * knowing that 39 of them are about one subject is not enough to prioritise.
 */
@Service
public class CorpusStatisticsService {

    private final DocumentRepository documents;
    private final TripleRepository triples;
    private final ClaimRepository claims;
    private final VerdictRepository verdicts;
    private final ContradictionRepository contradictions;
    private final DebateRepository debates;
    private final ExtractionQuarantineRepository quarantine;

    public CorpusStatisticsService(DocumentRepository documents, TripleRepository triples,
                                   ClaimRepository claims, VerdictRepository verdicts,
                                   ContradictionRepository contradictions, DebateRepository debates,
                                   ExtractionQuarantineRepository quarantine) {
        this.documents = documents;
        this.triples = triples;
        this.claims = claims;
        this.verdicts = verdicts;
        this.contradictions = contradictions;
        this.debates = debates;
        this.quarantine = quarantine;
    }

    /**
     * Counts every stage of the pipeline for one corpus.
     *
     * <p>Separate from the status breakdown because the breakdown is what a
     * reviewer actually needs: knowing there are 40 pending claims without
     * knowing they are spread across the whole corpus is not enough to decide
     * where to start.
     */
    public record CorpusStatistics(
            long documents,
            Map<String, Long> documentsByStatus,
            long triples,
            Map<String, Long> triplesByStatus,
            long claims,
            Map<String, Long> claimsByStatus,
            long verdicts,
            Map<String, Long> verdictsByType,
            long adjudicated,
            long contradictions,
            Map<String, Long> contradictionsByStatus,
            long debates,
            long openDebates,
            long quarantined) {
    }

    @Transactional(readOnly = true)
    public CorpusStatistics forCorpus(Long corpusId) {
        Map<String, Long> docStatus = new LinkedHashMap<>();
        for (DocumentStatus s : DocumentStatus.values()) {
            docStatus.put(s.name(), documents.countByCorpusIdAndStatus(corpusId, s));
        }

        Map<String, Long> tripleStatus = new LinkedHashMap<>();
        for (ProposalStatus s : ProposalStatus.values()) {
            tripleStatus.put(s.name(), triples.countByCorpusIdAndStatus(corpusId, s));
        }

        Map<String, Long> claimStatus = new LinkedHashMap<>();
        for (ClaimStatus s : ClaimStatus.values()) {
            claimStatus.put(s.name(), claims.countByCorpusIdAndStatus(corpusId, s));
        }

        Map<String, Long> verdictType = new LinkedHashMap<>();
        for (VerdictType t : VerdictType.values()) {
            verdictType.put(t.name(), verdicts.countByCorpusIdAndVerdictType(corpusId, t));
        }

        Map<String, Long> contradictionStatus = new LinkedHashMap<>();
        for (ContradictionStatus s : ContradictionStatus.values()) {
            contradictionStatus.put(s.name(),
                    contradictions.countByCorpusIdAndStatus(corpusId, s));
        }

        long totalDocuments = docStatus.values().stream().mapToLong(Long::longValue).sum();
        long totalTriples = tripleStatus.values().stream().mapToLong(Long::longValue).sum();
        long totalClaims = claimStatus.values().stream().mapToLong(Long::longValue).sum();
        long totalVerdicts = verdictType.values().stream().mapToLong(Long::longValue).sum();
        long totalContradictions = contradictionStatus.values().stream().mapToLong(Long::longValue).sum();

        long adjudicated = 0;
        for (AdjudicationState s : AdjudicationState.values()) {
            adjudicated += verdicts.countByCorpusIdAndAdjudicationState(corpusId, s);
        }

        long openDebates = 0;
        for (DebateState s : DebateState.values()) {
            if (s == DebateState.ROUND_ACTIVE || s == DebateState.AWAITING_CHAIR
                    || s == DebateState.SYNTHESIZING) {
                openDebates += debates.countByCorpusIdAndState(corpusId, s);
            }
        }

        return new CorpusStatistics(
                totalDocuments, docStatus,
                totalTriples, tripleStatus,
                totalClaims, claimStatus,
                totalVerdicts, verdictType,
                adjudicated,
                totalContradictions, contradictionStatus,
                debates.countByCorpusId(corpusId), openDebates,
                quarantine.countByCorpus(corpusId));
    }
}
