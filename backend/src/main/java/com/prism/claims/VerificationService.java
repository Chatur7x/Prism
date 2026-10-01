package com.prism.claims;

import com.prism.common.CitationValidator;
import com.prism.common.error.ApiException;
import com.prism.config.LlmProperties;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import com.prism.document.DocumentChunkRepository;
import com.prism.graph.GraphService;
import com.prism.llm.LlmCompletion;
import com.prism.llm.LlmPermanentException;
import com.prism.llm.LlmTransientException;
import com.prism.llm.Prompts;
import com.prism.llm.RetryingLlmService;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceOperationType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunStatus;
import com.prism.trace.TraceStep;
import com.prism.user.User;
import com.prism.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Redline: verifies a claim against retrieved evidence.
 *
 * <p>Pipeline, in this order, because the order is what makes it trustworthy:
 * <ol>
 *   <li><b>Deterministic rule analysis</b> on the claim text. Runs first and
 *       does not depend on the model at all, so it cannot be influenced by a
 *       model's framing.</li>
 *   <li><b>Retrieval</b>, hard-scoped to the claim's corpus.</li>
 *   <li><b>Deterministic evidence check.</b> If nothing was retrieved the
 *       verdict is SOURCE_MISSING regardless of what the model says.</li>
 *   <li><b>LLM judge</b>, given the passages and the rule signals.</li>
 *   <li><b>Citation validation</b> against the retrieved set.</li>
 *   <li><b>Fusion</b> of llm score and rule penalty, kept as separate fields.</li>
 *   <li><b>Persist</b> verdict and evidence; supersede any prior verdict into
 *       history rather than overwriting it.</li>
 * </ol>
 *
 * <p>Steps 3 and 6 are the reason a model cannot talk PRISM into calling
 * something supported.
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final ClaimRepository claims;
    private final VerdictRepository verdicts;
    private final RetrievalService retrieval;
    private final ClaimRuleEngine ruleEngine;
    private final JudgeResultParser judgeParser;
    private final RetryingLlmService llm;
    private final TraceRecorder traces;
    private final CorpusAccessService access;
    private final UserService users;
    private final LlmProperties llmProperties;
    private final GraphService graphService;
    private final VerdictPersistenceService persistence;

    public VerificationService(ClaimRepository claims, VerdictRepository verdicts,
                               RetrievalService retrieval, ClaimRuleEngine ruleEngine,
                               JudgeResultParser judgeParser, RetryingLlmService llm,
                               TraceRecorder traces, CorpusAccessService access, UserService users,
                               LlmProperties llmProperties, GraphService graphService,
                               VerdictPersistenceService persistence) {
        this.claims = claims;
        this.verdicts = verdicts;
        this.retrieval = retrieval;
        this.ruleEngine = ruleEngine;
        this.judgeParser = judgeParser;
        this.llm = llm;
        this.traces = traces;
        this.access = access;
        this.users = users;
        this.llmProperties = llmProperties;
        this.graphService = graphService;
        this.persistence = persistence;
    }

    public record VerificationOutcome(Long claimId, Long verdictId, VerdictType verdictType,
                                      VerdictType machineVerdictType, Double llmScore, Double rulePenalty,
                                      Double fusedScore, EvidenceStatus evidenceStatus,
                                      AdjudicationState adjudicationState, int evidenceCount,
                                      boolean succeeded, String error) {
    }

    /**
     * Verifies one claim.
     *
     * @param userId the requesting user, used for authorisation and the trace
     */
    public VerificationOutcome verify(Long userId, Long claimId) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("Claim", claimId));
        Corpus corpus = access.requireAccessible(claim.getCorpus().getId(), userId);

        if (!claim.isEligibleForVerification()) {
            throw ApiException.stateConflict("claim " + claimId + " has status " + claim.getStatus()
                    + "; only APPROVED claims are verified");
        }

        User requester = users.getById(userId);
        TraceRun traceRun = traces.startRun(TraceOperationType.VERIFICATION, corpus,
                claim.getSourceChunk().getDocument(), requester, "verify:claim:" + claimId, null);
        TraceStep root = traces.simple(traceRun.getId(), null, ActorType.ENGINE,
                TraceEventType.RETRIEVAL_STARTED, "Verification started");

        try {
            // ---- 1. deterministic rule analysis (model-independent) ----
            ClaimRuleEngine.RuleAnalysis analysis = ruleEngine.analyze(claim.getClaimText());
            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.RULE_ANALYSIS,
                    "Deterministic claim rule analysis",
                    TraceRecorder.StepPayload.builder()
                            .inputSummary(claim.getClaimText())
                            .ruleVersion(analysis.ruleVersion())
                            .outputSummary("weasels=" + analysis.weaselHits().size()
                                    + " absolutes=" + analysis.absoluteHits().size()
                                    + " penalty=" + analysis.penalty() + " — " + analysis.explanation())
                            .build());

            // ---- 2. retrieval, scoped to this corpus only ----
            String query = buildQuery(claim);
            RetrievalService.RetrievalResult result = retrieval.retrieve(corpus.getId(), query);
            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.RETRIEVAL_STARTED,
                    "Evidence retrieval",
                    TraceRecorder.StepPayload.builder()
                            .inputSummary("query=\"" + query + "\"")
                            .outputSummary(result.passages().size() + " passages retrieved; "
                                    + "normalized fulltext=\"" + result.normalizedQuery() + "\"")
                            .outputRefs(result.passages().stream()
                                    .map(RetrievalService.RetrievedPassage::chunkId).toList())
                            .build());

            EvidenceStatus evidenceStatus = result.hasEvidence()
                    ? EvidenceStatus.EVIDENCE_FOUND
                    : EvidenceStatus.NO_EVIDENCE;

            // ---- 3. no evidence is decided here, not by the model ----
            if (!result.hasEvidence()) {
                VerdictType verdict = VerdictType.SOURCE_MISSING;
                traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.LLM_JUDGMENT,
                    "Judge skipped: no evidence was retrieved",
                    TraceRecorder.StepPayload.builder()
                            .ruleVersion(analysis.ruleVersion())
                            .outputSummary("verdict=SOURCE_MISSING. The model was not consulted "
                                    + "because there was nothing to judge against.")
                            .build());
                VerificationOutcome outcome = persistAndLink(claim, verdict, verdict, null, analysis,
                        evidenceStatus, "No relevant passages were retrieved from this corpus.",
                        null, null, query, traceRun.getId(), List.of());
                finishTrace(traceRun, root, outcome);
                return outcome;
            }

            List<Prompts.Passage> promptPassages = result.passages().stream()
                    .map(p -> new Prompts.Passage(p.chunkId(), p.text()))
                    .toList();
            Set<Long> allowedChunkIds = promptPassages.stream()
                    .map(Prompts.Passage::id).collect(Collectors.toSet());

            // ---- 4. judge ----
            traces.record(traceRun.getId(), root, ActorType.LLM, TraceEventType.LLM_REQUEST,
                    "Judge request",
                    TraceRecorder.StepPayload.builder()
                            .inputSummary(allowedChunkIds.size() + " passages supplied")
                            .inputRefs(List.copyOf(allowedChunkIds))
                            .promptVersion(Prompts.JUDGE_V1)
                            .model(llmProperties.judgeModel())
                            .build());

            LlmCompletion completion;
            try {
                completion = llm.complete(Prompts.judgeSystem(),
                        Prompts.judgeUser(claim.getClaimText(), analysis.explanation(), promptPassages),
                        LlmProperties.Purpose.JUDGE, Prompts.JUDGE_V1,
                        "judge:claim:" + claimId, traceRun.getId(), root);
            } catch (LlmPermanentException | LlmTransientException ex) {
                log.warn("Judge call failed for claim {}: {}", claimId, ex.getMessage());
                traces.failure(traceRun.getId(), root, ActorType.LLM, TraceEventType.PIPELINE_FAILED,
                        "Judge call failed", ex.getMessage());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
                // A provider failure is not a verdict. Leave the claim APPROVED so
                // the job can be retried, and report the failure.
                return new VerificationOutcome(claimId, null, null, null, null, analysis.penalty(),
                        null, null, null, 0, false, ex.getMessage());
            }

            // ---- 5. validate the judge's output ----
            JudgeResultParser.JudgeResult parsed = judgeParser.parse(completion.rawText(), allowedChunkIds);
            if (!parsed.valid()) {
                traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.VALIDATION_FAILED,
                        "Judge output rejected by validation",
                        TraceRecorder.StepPayload.builder()
                                .status("REJECTED")
                                .promptVersion(Prompts.JUDGE_V1)
                                .model(completion.model())
                                .outputSummary(parsed.reason() + ": " + parsed.error())
                                .build());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, parsed.error());
                return new VerificationOutcome(claimId, null, null, null, null, analysis.penalty(),
                        null, null, null, result.passages().size(), false, parsed.error());
            }

            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.CITATION_VALIDATED,
                    "Judge citations validated",
                    TraceRecorder.StepPayload.builder()
                            .outputSummary(parsed.validPassageIds().size() + " citations accepted")
                            .outputRefs(parsed.validPassageIds())
                            .build());

            // ---- 6. deterministic fusion ----
            VerdictType machineVerdict = ConfidenceFusion.resolveVerdict(
                    parsed.verdictType(), evidenceStatus, analysis.penalty(), parsed.confidence());
            Double fused = ConfidenceFusion.fuse(parsed.confidence(), analysis.penalty());

            String reason = buildReason(machineVerdict, evidenceStatus, analysis, parsed, result);

            VerificationOutcome outcome = persistAndLink(claim, machineVerdict, machineVerdict,
                    parsed.confidence(), analysis, evidenceStatus, reason, parsed.reasoning(),
                    completion.model(), query, traceRun.getId(), result.passages());
            finishTrace(traceRun, root, outcome);
            return outcome;

        } catch (RuntimeException ex) {
            log.error("Verification failed for claim {}", claimId, ex);
            traces.failure(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.PIPELINE_FAILED,
                    "Verification failed", ex.getMessage());
            traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
            return new VerificationOutcome(claimId, null, null, null, null, null, null, null, null,
                    0, false, ex.getMessage());
        }
    }

    private void finishTrace(TraceRun traceRun, TraceStep root, VerificationOutcome outcome) {
        traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.VERDICT_CREATED,
                "Verdict recorded",
                TraceRecorder.StepPayload.builder()
                        .outputSummary("verdict=" + outcome.verdictType()
                                + " evidence=" + outcome.evidenceStatus()
                                + " fusedScore=" + outcome.fusedScore()
                                + " adjudication=" + outcome.adjudicationState())
                        .outputRefs(outcome.verdictId() == null ? List.of() : List.of(outcome.verdictId()))
                        .build());
        traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
        graphService.invalidateAll();
    }

    private String buildReason(VerdictType verdict, EvidenceStatus evidenceStatus,
                               ClaimRuleEngine.RuleAnalysis analysis,
                               JudgeResultParser.JudgeResult parsed,
                               RetrievalService.RetrievalResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Machine verdict: ").append(verdict).append(". ");
        sb.append("Evidence status: ").append(evidenceStatus)
                .append(" (").append(result.passages().size()).append(" passages retrieved). ");
        sb.append(analysis.explanation()).append(' ');
        if (parsed.confidence() != null) {
            sb.append("Judge confidence: ").append(parsed.confidence()).append(". ");
        }
        if (parsed.reasoning() != null && !parsed.reasoning().isBlank()) {
            sb.append("Judge explanation: ").append(parsed.reasoning());
        }
        return sb.toString();
    }

    /** Claim text plus subject terms, which is what retrieval needs to be useful. */
    String buildQuery(Claim claim) {
        StringBuilder sb = new StringBuilder();
        sb.append(claim.getSubject()).append(' ').append(claim.getClaimText());
        if (claim.getPredicate() != null && !claim.getPredicate().isBlank()) {
            sb.append(' ').append(claim.getPredicate().replace('_', ' '));
        }
        if (claim.getObjectText() != null && !claim.getObjectText().isBlank()) {
            sb.append(' ').append(claim.getObjectText());
        }
        return sb.toString().trim();
    }

    private VerificationOutcome persistAndLink(Claim claim, VerdictType effective, VerdictType machine,
                                               Double llmScore, ClaimRuleEngine.RuleAnalysis analysis,
                                               EvidenceStatus evidenceStatus, String reason,
                                               String llmReasoning, String model, String query,
                                               Long traceRunId,
                                               List<RetrievalService.RetrievedPassage> retrieved) {
        VerdictPersistenceService.Persisted persisted = persistence.persistVerdict(claim, effective, machine,
                llmScore, analysis, evidenceStatus, reason, llmReasoning, model, query, traceRunId);
        int evidenceCount = retrieved.isEmpty() ? 0
                : persistence.persistEvidence(persisted.verdictId(), claim.getCorpus().getId(), retrieved);

        return new VerificationOutcome(claim.getId(), persisted.verdictId(), persisted.effective(),
                persisted.machine(), persisted.llmScore(), persisted.rulePenalty(),
                persisted.fusedScore(), persisted.evidenceStatus(), persisted.adjudicationState(),
                evidenceCount, true, null);
    }
}
