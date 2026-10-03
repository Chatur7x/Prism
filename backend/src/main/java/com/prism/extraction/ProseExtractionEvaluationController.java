package com.prism.extraction;

import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.TreeMap;

/**
 * Extraction quality against hand-written prose labels.
 *
 * <p>Separate from the canonical gold-set endpoint on purpose: that one measures
 * transcription, this one measures extraction from prose, and reporting them
 * through one interface would invite quoting the wrong number.
 *
 * <p>Verifier-gated for the same reason as the retrieval benchmark: a figure that
 * decides whether a model or prompt change is safe to keep is a judgement about
 * the system, not a read of it.
 */
@RestController
@RequestMapping("/api/llm-evaluation/prose")
@Tag(name = "LLM evaluation",
        description = "Hand-labelled measurement of extraction quality over natural prose")
public class ProseExtractionEvaluationController {

    private final ProseExtractionEvaluationService service;
    private final CorpusAccessService access;

    public ProseExtractionEvaluationController(ProseExtractionEvaluationService service,
                                               CorpusAccessService access) {
        this.service = service;
        this.access = access;
    }

    public record EvaluateRequest(
            @jakarta.validation.constraints.NotBlank(message = "the gold set is required")
            @jakarta.validation.constraints.Size(max = 2000000,
                    message = "the gold set is larger than the 2 MB the request allows")
            String goldSet) {
    }

    @PostMapping("/extraction")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Measure triple and claim extraction against the prose gold set",
            description = "Runs the production extraction path over every chunk of the labelled "
                    + "documents and reports precision, recall and F1 for triples and claims, with "
                    + "the raw totals (expected, predicted, correct, missed, incorrect), plus the "
                    + "malformed, quarantine and ungrounded rates. Read-only: nothing is persisted "
                    + "and no trace run is created. Every report carries datasetLimitation, which "
                    + "must be quoted alongside the numbers.")
    public ProseExtractionEvaluationService.ProseReport evaluate(
            @RequestParam Long corpusId,
            @RequestParam(defaultValue = "false") boolean requireRealModel,
            @RequestBody @jakarta.validation.Valid EvaluateRequest request) {
        Long userId = access.requireCurrentUserId();
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        access.requireVerifier(userId);
        return service.evaluate(corpus, userId, request.goldSet(), requireRealModel);
    }

    /**
     * Turns "a real model is required but none is configured" into a distinct,
     * machine-readable failure.
     *
     * <p>Without this the caller receives an ordinary 500 and the most important
     * fact in the response -- that no model was involved -- is buried in a stack
     * trace. The status is 412 Precondition Failed because the request was
     * well-formed; it is the server's configuration that does not satisfy it. The
     * body carries the {@code REAL_MODEL_EXECUTION_REQUIRED} token so a CI job
     * can match on it without parsing prose.
     */
    @ExceptionHandler(ProseExtractionEvaluationService.RealModelExecutionRequired.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    public Map<String, Object> realModelRequired(
            ProseExtractionEvaluationService.RealModelExecutionRequired ex) {
        return Map.of(
                "status", ProseExtractionEvaluationService.RealModelExecutionRequired.TOKEN,
                "executed", false,
                "reason", ex.getMessage());
    }

    @PostMapping("/gold-set")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Describe the prose gold set without calling a provider",
            description = "Lets a reviewer confirm the dataset is the expected version and see its "
                    + "shape -- including how many sentences expect nothing to be extracted, which "
                    + "is the number that makes the precision figure meaningful. No side effects "
                    + "and no cost.")
    public Map<String, Object> describe(@RequestBody @jakarta.validation.Valid EvaluateRequest request) {
        var gold = service.parseGold(request.goldSet());
        Map<String, Integer> byPredicate = new TreeMap<>();
        for (var triples : gold.triplesByDocument().values()) {
            triples.forEach(t -> byPredicate.merge(t.predicate(), 1, Integer::sum));
        }
        return Map.of(
                "datasetVersion", gold.datasetVersion(),
                "goldDocuments", gold.documents(),
                "labelledSentences", gold.labelledSentences(),
                "negativeSentences", gold.negativeSentences(),
                "negativesArePercentOfLabelled",
                        gold.labelledSentences() == 0 ? 0
                                : 100 * gold.negativeSentences() / gold.labelledSentences(),
                "expectedTriples", gold.expectedTriples(),
                "expectedClaims", gold.expectedClaims(),
                "predicatesExercised", byPredicate.size(),
                "rowsByPredicate", byPredicate,
                "datasetLimitation", ProseExtractionEvaluationService.DATASET_LIMITATION);
    }
}