package com.prism.synthesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.CitationValidator;
import com.prism.common.error.ApiException;
import com.prism.config.LlmProperties;
import com.prism.corpus.CorpusAccessService;
import com.prism.debate.Argument;
import com.prism.debate.ArgumentRepository;
import com.prism.debate.ArgumentWeight;
import com.prism.debate.ArgumentWeightRepository;
import com.prism.debate.Debate;
import com.prism.debate.DebateEngine;
import com.prism.debate.DebateEventBroker;
import com.prism.debate.DebateRepository;
import com.prism.debate.DebateState;
import com.prism.debate.SkepticBrief;
import com.prism.debate.SkepticBriefBuilder;
import com.prism.graph.GraphService;
import com.prism.knowledge.TripleRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the council's synthesis report.
 *
 * <p>Three input layers are kept visibly separate and are never merged before
 * the model sees them:
 * <ol>
 *   <li><b>Arguments</b> — what the personas said, with their validated
 *       citations.</li>
 *   <li><b>Human assessment</b> — the chair's weights, appended as an audit
 *       fact and never modified by the model.</li>
 *   <li><b>Machine evidence</b> — the same real figures the Skeptic received,
 *       rebuilt from current state.</li>
 * </ol>
 *
 * <p>Every output block is stored with its own citations. Citations are
 * validated against what was actually supplied, so a block cannot point at an
 * argument or machine fact that does not exist.
 */
@Service
public class SynthesisService {

    private static final Logger log = LoggerFactory.getLogger(SynthesisService.class);

    private final SynthesisReportRepository reports;
    private final ReportBlockRepository blocks;
    private final ReportBlockCitationRepository blockCitations;
    private final DebateRepository debates;
    private final ArgumentRepository arguments;
    private final ArgumentWeightRepository weights;
    private final SkepticBriefBuilder briefBuilder;
    private final RetryingLlmService llm;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traces;
    private final CorpusAccessService access;
    private final UserService users;
    private final DebateEventBroker broker;
    private final LlmProperties llmProperties;
    private final SynthesisPersistenceService persistence;

    public SynthesisService(SynthesisReportRepository reports, ReportBlockRepository blocks,
                            ReportBlockCitationRepository blockCitations, DebateRepository debates,
                            ArgumentRepository arguments, ArgumentWeightRepository weights,
                             SkepticBriefBuilder briefBuilder,
                            RetryingLlmService llm, ObjectMapper objectMapper, TraceRecorder traces,
                            CorpusAccessService access, UserService users, DebateEventBroker broker,
                            LlmProperties llmProperties,
                            SynthesisPersistenceService persistence) {
        this.reports = reports;
        this.blocks = blocks;
        this.blockCitations = blockCitations;
        this.debates = debates;
        this.arguments = arguments;
        this.weights = weights;
        this.briefBuilder = briefBuilder;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.traces = traces;
        this.access = access;
        this.users = users;
        this.broker = broker;
        this.llmProperties = llmProperties;
        this.persistence = persistence;
    }

    /**
     * Generates the synthesis report for a completed debate.
     *
     * <p>Idempotent: an existing report is returned rather than regenerated, so
     * a retried request cannot produce two conflicting completions.
     */
    public SynthesisReport synthesize(Long userId, Long debateId) {
        access.requireVerifier(userId);
        Debate debate = debates.findById(debateId)
                .orElseThrow(() -> ApiException.notFound("Debate", debateId));
        access.requireAccessible(debate.getCorpus().getId(), userId);

        SynthesisReport existing = reports.findByDebateId(debateId).orElse(null);
        if (existing != null) {
            return existing;
        }
        if (debate.getState() != DebateState.SYNTHESIZING) {
            throw ApiException.stateConflict("debate " + debateId + " is " + debate.getState()
                    + "; synthesis runs only from SYNTHESIZING");
        }

        User chair = users.getById(userId);
        TraceRun traceRun = traces.startRun(TraceOperationType.SYNTHESIS, debate.getCorpus(),
                null, chair, "synthesis:debate:" + debateId, null);
        TraceStep root = traces.simple(traceRun.getId(), null, ActorType.ENGINE,
                TraceEventType.SYNTHESIS_STARTED, "Synthesis started");

        try {
            // ---- layer 1: arguments ----
            List<Argument> allArguments = arguments.findByDebateIdOrderByDebateRoundIdAscIdAsc(debateId);
            List<Argument> usable = allArguments.stream().filter(a -> !a.isFailed()).toList();

            // ---- layer 2: human weights ----
            Map<Long, ArgumentWeight> weightByArgument = new LinkedHashMap<>();
            for (ArgumentWeight w : weights.findLatestWeightsForDebate(debateId)) {
                weightByArgument.put(w.getArgument().getId(), w);
            }

            // ---- layer 3: machine evidence, rebuilt from live state ----
            SkepticBriefBuilder.BriefResult brief =
                    briefBuilder.build(userId, debate.getCorpus().getId(), debate.getContradiction(), traceRun.getId());

            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.SYNTHESIS_STARTED,
                    "Synthesis inputs assembled",
                    TraceRecorder.StepPayload.builder()
                            .outputSummary(usable.size() + " usable arguments, "
                                    + weightByArgument.size() + " chair weights, "
                                    + brief.brief().factCount() + " machine facts")
                            .build());

            String prompt = buildPrompt(debate, usable, weightByArgument, brief.rendered());
            Set<Long> allowedArgumentIds = usable.stream().map(Argument::getId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> allowedFactIds = brief.brief().allowedIds();

            traces.record(traceRun.getId(), root, ActorType.LLM, TraceEventType.LLM_REQUEST,
                    "Synthesis request",
                    TraceRecorder.StepPayload.builder()
                            .promptVersion(Prompts.SYNTHESIS_V1)
                            .model(llmProperties.synthesisModel())
                            .outputRefs(new ArrayList<>(allowedArgumentIds))
                            .build());

            LlmCompletion completion;
            try {
                completion = llm.complete(Prompts.synthesisSystem(), prompt,
                        LlmProperties.Purpose.SYNTHESIS, Prompts.SYNTHESIS_V1,
                        "synthesis:debate:" + debateId, traceRun.getId(), root);
            } catch (LlmPermanentException | LlmTransientException ex) {
                log.warn("Synthesis call failed for debate {}: {}", debateId, ex.getMessage());
                traces.failure(traceRun.getId(), root, ActorType.LLM, TraceEventType.PIPELINE_FAILED,
                        "Synthesis call failed", ex.getMessage());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
                throw new ApiException(com.prism.common.error.ErrorCode.LLM_UNAVAILABLE,
                        "synthesis could not be generated: " + ex.getMessage(), ex);
            }

            ParsedSynthesis parsed = parse(completion.rawText(), allowedArgumentIds, allowedFactIds);
            if (!parsed.valid()) {
                traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.VALIDATION_FAILED,
                        "Synthesis output rejected",
                        TraceRecorder.StepPayload.builder()
                                .status("REJECTED")
                                .outputSummary(parsed.error())
                                .build());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, parsed.error());
                throw new ApiException(com.prism.common.error.ErrorCode.LLM_INVALID_OUTPUT,
                        "synthesis output failed validation: " + parsed.error());
            }

            // Every write from here to the commit is delegated to one bean with
            // one transaction. This method cannot be transactional itself: it
            // makes a model call above and must not hold a connection open across
            // it. See SynthesisPersistenceService for why that matters beyond
            // tidiness.
            SynthesisPersistenceService.Persisted persisted = persistence.persist(
                    debate, parsed, completion.model(), Prompts.SYNTHESIS_V1,
                    completion.durationMs(), traceRun.getId(),
                    allowedArgumentIds, allowedFactIds);
            SynthesisReport report = persisted.report();

            recordCompletion(traceRun.getId(), root, persisted);

            broker.publish(debateId, DebateEventBroker.EVENT_SYNTHESIS_READY,
                    Map.of("reportId", report.getId(), "debateId", debateId,
                            "blockCount", persisted.blockCount(),
                            "conclusion", truncate(parsed.conclusion(), 400)));
            broker.completeAll(debateId);
            traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
            return report;

        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Synthesis failed for debate {}", debateId, ex);
            traces.failure(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.PIPELINE_FAILED,
                    "Synthesis failed", ex.getMessage());
            traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Records that synthesis finished.
     *
     * <p>Trace only. The state transition and the contradiction resolution
     * happen inside {@link SynthesisPersistenceService}'s transaction, because a
     * trace step that survived a rolled-back report would assert something that
     * never became true -- and the trace is the thing a reader trusts when the
     * report is missing.
     */
    private void recordCompletion(Long traceRunId, TraceStep root,
                                  SynthesisPersistenceService.Persisted persisted) {
        traces.record(traceRunId, root, ActorType.ENGINE, TraceEventType.SYNTHESIS_COMPLETED,
                "Synthesis completed; debate resolved",
                TraceRecorder.StepPayload.builder()
                        .outputSummary("state=COMPLETED; contradiction marked RESOLVED; "
                                + persisted.blockCount() + " blocks, "
                                + persisted.citationCount() + " citations")
                        .build());
    }

    /**
     * Renders the three layers with explicit headings so the model cannot blur
     * machine evidence into an argument, nor a weight into a fact.
     */
    String buildPrompt(Debate debate, List<Argument> usable,
                       Map<Long, ArgumentWeight> weightByArgument, String machineEvidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("<CONTRADICTION id=\"contradiction\">\n")
                .append(debate.getContradiction().getSubjectText()).append(' ')
                .append(debate.getContradiction().getPredicate() == null ? ""
                        : debate.getContradiction().getPredicate())
                .append('\n')
                .append("Position A: ").append(debate.getContradiction().getLeftDescription()).append('\n')
                .append("Position B: ").append(debate.getContradiction().getRightDescription()).append('\n')
                .append("Topic: ").append(debate.getTopic()).append('\n')
                .append("</CONTRADICTION>\n\n");

        sb.append("<COUNCIL_ARGUMENTS>\n");
        if (usable.isEmpty()) {
            sb.append("(no usable arguments: every persona failed this debate)\n");
        }
        for (Argument a : usable) {
            ArgumentWeight w = weightByArgument.get(a.getId());
            sb.append("- [argument ").append(a.getId()).append("] round ")
                    .append(a.getDebateRound().getRoundNumber())
                    .append(", persona ").append(a.getPersona().name())
                    .append(", chair weight ").append(w == null ? "NOT WEIGHTED" : w.getWeight())
                    .append('\n');
            sb.append("  ").append(a.getArgumentText().replace("\n", "\n  ")).append('\n');
        }
        sb.append("</COUNCIL_ARGUMENTS>\n\n");

        // The machine evidence is inserted as untrusted data, not as an
        // instruction block, so a fact's text cannot act on the model.
        sb.append(Prompts.UNTRUSTED_DATA_PREAMBLE).append('\n');
        sb.append("<DATA id=\"machine-evidence\">\n").append(machineEvidence)
                .append("\n</DATA>\n\n");

        sb.append("Cite arguments as [argument <id>] and machine facts by their bracketed id. "
                + "Report what remains unresolved rather than closing it artificially.\n");
        return sb.toString();
    }

    private ParsedSynthesis parse(String rawText, Set<Long> allowedArgumentIds, Set<String> allowedFactIds) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(rawText));
        } catch (Exception ex) {
            return new ParsedSynthesis(null, List.of(), false,
                    "response was not valid JSON: " + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            return new ParsedSynthesis(null, List.of(), false, "response was not a JSON object");
        }

        String conclusion = root.path("conclusion").asText("").trim();
        if (conclusion.isEmpty()) {
            return new ParsedSynthesis(null, List.of(), false, "response contained no 'conclusion'");
        }

        JsonNode blocksNode = root.path("blocks");
        if (!blocksNode.isArray()) {
            return new ParsedSynthesis(null, List.of(), false, "response contained no 'blocks' array");
        }

        List<ParsedBlock> parsed = new ArrayList<>();
        for (JsonNode blockNode : blocksNode) {
            BlockType type;
            try {
                type = BlockType.valueOf(blockNode.path("block_type").asText("FINDING")
                        .trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return new ParsedSynthesis(null, List.of(), false,
                        "unknown block_type '" + blockNode.path("block_type").asText() + "'");
            }
            String text = blockNode.path("text").asText("").trim();
            if (text.isEmpty()) {
                return new ParsedSynthesis(null, List.of(), false, "a report block had no text");
            }
            if (text.length() > 4000) {
                text = text.substring(0, 4000);
            }

            List<Object> rawArgumentIds = new ArrayList<>();
            if (blockNode.path("argument_ids").isArray()) {
                blockNode.path("argument_ids").forEach(n -> rawArgumentIds.add(
                        n.isNumber() ? n.asLong() : n.asText()));
            }
            CitationValidator.Result<Long> argCitations = CitationValidator
                    .validateChunkIds(rawArgumentIds, allowedArgumentIds);
            if (argCitations.hasHallucinatedCitations()) {
                return new ParsedSynthesis(null, List.of(), false,
                        "a block cited arguments that do not exist: "
                                + argCitations.rejected().stream()
                                        .map(CitationValidator.Rejection::id).toList());
            }

            List<Object> rawFactIds = new ArrayList<>();
            if (blockNode.path("machine_fact_ids").isArray()) {
                blockNode.path("machine_fact_ids").forEach(n -> rawFactIds.add(n.asText()));
            }
            CitationValidator.Result<String> factCitations = CitationValidator
                    .validateStringIds(rawFactIds, allowedFactIds);
            if (factCitations.hasHallucinatedCitations()) {
                return new ParsedSynthesis(null, List.of(), false,
                        "a block cited machine facts that do not exist: "
                                + factCitations.rejected().stream()
                                        .map(CitationValidator.Rejection::id).toList());
            }

            parsed.add(new ParsedBlock(type, text, argCitations.valid(), factCitations.valid()));
        }

        if (parsed.isEmpty()) {
            return new ParsedSynthesis(null, List.of(), false, "response contained no report blocks");
        }
        return new ParsedSynthesis(conclusion, List.copyOf(parsed), true, null);
    }


    @Transactional(readOnly = true)
    public SynthesisReport reportFor(Long userId, Long debateId) {
        Debate debate = debates.findById(debateId)
                .orElseThrow(() -> ApiException.notFound("Debate", debateId));
        access.requireAccessible(debate.getCorpus().getId(), userId);
        return reports.findByDebateId(debateId)
                .orElseThrow(() -> ApiException.notFound("Synthesis report for debate", debateId));
    }

    @Transactional(readOnly = true)
    public List<ReportBlock> blocksOf(Long reportId) {
        return blocks.findByReportIdOrderBySequenceNoAsc(reportId);
    }

    @Transactional(readOnly = true)
    public List<ReportBlockCitation> citationsOf(Long reportId) {
        return blockCitations.findByReportId(reportId);
    }

    private static String stripFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
