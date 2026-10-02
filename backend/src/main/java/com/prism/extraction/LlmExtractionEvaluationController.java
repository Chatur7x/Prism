package com.prism.extraction;

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
 * Extraction-quality measurement against a hand-checked gold set.
 *
 * <p>Verifier-gated, like the retrieval benchmark and for the same reason: these
 * figures decide whether a model or prompt change is safe to keep, so running
 * them is a judgement about the system rather than a read of it. An analyst can
 * submit proposals all day; a verifier decides whether extraction is working.
 *
 * <p><b>Costs money and time.</b> One provider call per chunk carrying a gold
 * sentence. The response body is the whole report, so the caller can archive it
 * as the artifact of record. Nothing is persisted: an evaluation run is an
 * experiment, and a table of past experiments would drift out of step with the
 * code that produced them.
 */
@RestController
@RequestMapping("/api/llm-evaluation")
@Tag(name = "LLM evaluation",
        description = "Hand-labelled measurement of extraction quality for the configured provider")
public class LlmExtractionEvaluationController {

    private final LlmExtractionEvaluationService service;
    private final CorpusAccessService access;

    public LlmExtractionEvaluationController(LlmExtractionEvaluationService service,
                                             CorpusAccessService access) {
        this.service = service;
        this.access = access;
    }

    public record EvaluateRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 500000, message = "the gold set is too large")
            String goldSet) {
    }

    @PostMapping("/extraction")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Measure extraction precision, recall and F1 against a gold set",
            description = "Runs the production extraction path -- the same prompt, the same retry "
                    + "policy and the same parser -- over every chunk carrying a gold sentence, and "
                    + "reports per-provider metrics. Read-only: nothing is persisted and no trace run "
                    + "is created, so this cannot alter the state it measures. Every report carries "
                    + "corpusLimitation, which must be quoted alongside the numbers.")
    public LlmExtractionEvaluationService.ExtractionReport evaluate(
            @RequestParam Long corpusId,
            @RequestBody @jakarta.validation.Valid EvaluateRequest request) {
        Long userId = access.requireCurrentUserId();
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        access.requireVerifier(userId);
        return service.evaluate(corpus, userId, request.goldSet());
    }

    @PostMapping("/gold-set")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Report what the shipped gold set contains, without calling a provider",
            description = "Lets a reviewer check the labels are sane and see which predicates the "
                    + "corpus exercises, at no cost and with no side effects.")
    public Map<String, Object> describeGoldSet(@RequestBody @jakarta.validation.Valid EvaluateRequest request) {
        List<LlmExtractionEvaluationService.GoldTriple> gold =
                LlmExtractionEvaluationService.parseGold(request.goldSet());
        Map<String, Integer> byPredicate = new java.util.TreeMap<>();
        Map<String, Integer> byDocument = new java.util.TreeMap<>();
        for (LlmExtractionEvaluationService.GoldTriple g : gold) {
            byPredicate.merge(g.predicate(), 1, Integer::sum);
            byDocument.merge(g.document(), 1, Integer::sum);
        }
        return Map.of(
                "goldRows", gold.size(),
                "goldDocuments", byDocument.size(),
                "predicatesExercised", byPredicate.size(),
                "rowsByPredicate", byPredicate,
                "rowsByDocument", byDocument,
                "corpusLimitation", LlmExtractionEvaluationService.CORPUS_LIMITATION);
    }
}
