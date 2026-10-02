package com.prism.claims;

import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Retrieval evaluation endpoints.
 *
 * <p>Verifier-gated. This is not a diagnostic: the numbers decide whether a
 * retriever change is safe to keep, so running them is a judgement about the
 * system rather than a read of it. An analyst can submit proposals all day; a
 * verifier decides whether the pipeline is working.
 */
@RestController
@RequestMapping("/api/retrieval-evaluation")
@Tag(name = "Retrieval evaluation",
        description = "Gold-set measurement of retrieval quality: Recall@k and MRR")
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService service;
    private final CorpusAccessService access;

    public RetrievalEvaluationController(RetrievalEvaluationService service,
                                         CorpusAccessService access) {
        this.service = service;
        this.access = access;
    }

    public record EvaluateRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100000) String goldSet) {
    }

    public record RowResponse(Long id, String runKey, String expanderVersion, String queryText,
                              Long goldChunkId, Integer retrievedRank,
                              Double score, boolean recallAt1, boolean recallAt3, boolean recallAt5,
                              Double reciprocalRank, java.time.Instant createdAt) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Run a gold set and return the resulting metrics",
            description = "The gold set is 'query = goldChunkId' lines; blank lines and # comments are "
                    + "ignored. Every line is validated before any retrieval runs, so a typo cannot "
                    + "produce a partially-applied measurement that looks real.")
    public Map<String, Object> evaluate(@RequestParam Long corpusId,
                                        @RequestBody @jakarta.validation.Valid EvaluateRequest request) {
        Long userId = access.requireCurrentUserId();
        // The resolved Corpus is passed on, not its id, so the run can never be
        // scoped to a corpus the caller cannot reach.
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        access.requireVerifier(userId);

        List<RetrievalEvaluationService.GoldQuery> queries =
                RetrievalEvaluationService.parseGoldSet(request.goldSet());
        return service.toResponse(service.evaluateAndSummarise(corpus, userId, queries));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Aggregate Recall@k and MRR for a corpus")
    public Map<String, Object> summary(@RequestParam Long corpusId) {
        Long userId = access.requireCurrentUserId();
        return service.toResponse(service.summarise(corpusId, userId));
    }

    @GetMapping("/rows")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Individual evaluation rows for one benchmark run",
            description = "Per-query rows rather than an aggregate, so a regression can be traced to "
                    + "the query that caused it instead of only being observed as a number moving. "
                    + "Defaults to the newest run, in gold-set order; pass runKey for an older one. "
                    + "Every row is stamped with the expansion version that produced it, so a figure "
                    + "is attributable to a retriever configuration and not only to a corpus.")
    public List<RowResponse> rows(@RequestParam Long corpusId,
                                  @RequestParam(required = false) String runKey) {
        Long userId = access.requireCurrentUserId();
        access.requireVerifier(userId);
        return service.runRows(corpusId, userId, runKey).stream()
                .map(e -> new RowResponse(e.getId(), e.getRunKey(), e.getExpanderVersion(),
                        e.getQueryText(), e.getGoldChunkId(),
                        e.getRetrievedRank(),
                        e.getScore() == null ? null : e.getScore().doubleValue(),
                        e.isRecallAt1(), e.isRecallAt3(), e.isRecallAt5(),
                        e.getReciprocalRank() == null ? null : e.getReciprocalRank().doubleValue(),
                        e.getCreatedAt()))
                .toList();
    }
}