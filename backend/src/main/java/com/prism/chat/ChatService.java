package com.prism.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.claims.RetrievalService;
import com.prism.claims.RetrievalService.RetrievedPassage;
import com.prism.common.CitationValidator;
import com.prism.common.error.ApiException;
import com.prism.config.LlmProperties;
import com.prism.config.PrismTuningProperties;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import com.prism.knowledge.Triple;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grounded chat over one corpus.
 *
 * <p><b>Grounding is enforced here, not by asking the model.</b> Telling a model
 * "only answer from the context" is a request, not a control. The actual
 * guarantee comes from four backend steps:
 *
 * <ol>
 *   <li>the model is given only corpus-scoped retrieved passages and approved
 *       triples — it has no other source;</li>
 *   <li>every citation it returns is checked against the ids it was actually
 *       given, and a response with an invented id is rejected outright;</li>
 *   <li>when the corpus cannot answer the question, the backend returns a
 *       refusal without consulting the model at all;</li>
 *   <li>at persistence time each cited chunk is re-checked against the session's
 *       corpus, so a defect in a query cannot attach foreign evidence.</li>
 * </ol>
 *
 * <p><b>Transaction discipline.</b> {@code ask} performs model calls and holds
 * no transaction. All persistence goes through {@link ChatPersistenceService},
 * which commits each write in its own short transaction, and all values needed
 * later are extracted as plain scalars rather than lazy JPA proxies —
 * {@code spring.jpa.open-in-view} is deliberately disabled, so a proxy
 * dereferenced after the session closes throws.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final ChatPersistenceService persistence;
    private final RetrievalService retrieval;
    private final TripleRepository triples;
    private final com.prism.claims.VerdictRepository verdicts;
    private final com.prism.document.DocumentChunkRepository chunks;
    private final RetryingLlmService llm;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traces;
    private final CorpusAccessService access;
    private final UserService users;
    private final LlmProperties llmProperties;
    private final int maxRetrievalChunks;

    public ChatService(ChatSessionRepository sessions, ChatMessageRepository messages,
                       ChatPersistenceService persistence, RetrievalService retrieval,
                       TripleRepository triples, com.prism.claims.VerdictRepository verdicts,
                       com.prism.document.DocumentChunkRepository chunks, RetryingLlmService llm,
                       ObjectMapper objectMapper, TraceRecorder traces, CorpusAccessService access,
                       UserService users, LlmProperties llmProperties, PrismTuningProperties tuning) {
        this.sessions = sessions;
        this.messages = messages;
        this.persistence = persistence;
        this.retrieval = retrieval;
        this.triples = triples;
        this.verdicts = verdicts;
        this.chunks = chunks;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.traces = traces;
        this.access = access;
        this.users = users;
        this.llmProperties = llmProperties;
        this.maxRetrievalChunks = tuning.chat().maxRetrievalChunks();
    }

    // ---- sessions ----------------------------------------------------------

    @Transactional
    public ChatSession createSession(Long userId, Long corpusId, String title) {
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        User user = users.getById(userId);
        String cleanTitle = (title == null || title.isBlank()) ? "New conversation" : truncate(title, 300);
        return sessions.save(new ChatSession(corpus, user, cleanTitle));
    }

    @Transactional(readOnly = true)
    public List<ChatSession> listSessions(Long userId) {
        return sessions.findByUserIdOrderByUpdatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 200)).getContent();
    }

    @Transactional(readOnly = true)
    public ChatSession getSession(Long userId, Long sessionId) {
        // Ownership is enforced here, not merely corpus access: a session is
        // private to the user who opened it.
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("Chat session", sessionId));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(Long userId, Long sessionId) {
        getSession(userId, sessionId);
        return messages.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
    }

    /** One validated citation attached to an answer. */
    public record Citation(String kind, Long chunkId, Long documentId, String documentTitle,
                           Integer chunkIndex, String excerpt) {
    }

    public record ChatResponse(Long messageId, String answer, List<Citation> citations,
                               boolean grounded, boolean insufficientEvidence,
                               int retrievalCount, String model) {
    }

    // ---- grounded answering ------------------------------------------------

    /**
     * Asks a grounded question about the session's corpus.
     *
     * @return the assistant reply plus the validated citations behind it
     */
    public ChatResponse ask(Long userId, Long sessionId, String question) {
        // Plain scalars, extracted inside a transaction. Nothing lazy escapes it.
        ChatPersistenceService.SessionScope scope = persistence.loadScope(userId, sessionId);
        final Long corpusId = scope.corpusId();
        final String corpusName = scope.corpusName();

        if (question == null || question.isBlank()) {
            throw ApiException.validation("a question is required");
        }
        String cleanQuestion = truncate(question.trim(), 500);

        // The user's turn is persisted first so history stays complete even if
        // the answer fails.
        persistence.persistUserTurn(sessionId, userId, cleanQuestion);

        User user = users.getById(userId);
        TraceRun traceRun = traces.startRun(TraceOperationType.CHAT, null, null, user,
                "chat:session:" + sessionId, null);
        // Linked immediately so the Glass Box can authorize this trace by corpus.
        persistence.attachCorpusToTrace(traceRun.getId(), corpusId);

        TraceStep root = traces.simple(traceRun.getId(), null, ActorType.ENGINE,
                TraceEventType.CHAT_RETRIEVAL, "Chat retrieval started");

        try {
            // ---- 1. corpus-scoped retrieval ----
            RetrievalService.RetrievalResult evidence =
                    retrieval.retrieve(corpusId, cleanQuestion);
            List<RetrievedPassage> passages = evidence.passages().stream()
                    .limit(maxRetrievalChunks)
                    .toList();

            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.CHAT_RETRIEVAL,
                    "Evidence retrieved for the question",
                    TraceRecorder.StepPayload.builder()
                            .inputSummary(cleanQuestion)
                            .outputSummary(passages.size() + " passages retrieved from corpus " + corpusId)
                            .outputRefs(passages.stream().map(RetrievedPassage::chunkId).toList())
                            .build());

            // ---- 2. approved triples matching the question ----
            List<Triple> relevantTriples = findRelevantTriples(corpusId, cleanQuestion, passages);

            // ---- 3. verdict summary over the retrieved evidence ----
            Map<String, String> verdictSummary = summarizeVerdicts(corpusId, cleanQuestion);

            // ---- 4. the backend refuses when the corpus cannot answer ----
            if (passages.isEmpty() && relevantTriples.isEmpty()) {
                String refusal = "The corpus \"" + corpusName + "\" does not contain enough "
                        + "information to answer that. No passages matched the question, so there "
                        + "is nothing to ground an answer in.";
                ChatMessage reply = persistence.persistAssistantTurn(sessionId, user, refusal, "[]",
                        null, null, 0L, 0, false, true, traceRun.getId());
                traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.CHAT_RESPONSE,
                        "Refused: no evidence available",
                        TraceRecorder.StepPayload.builder()
                                .outputSummary("insufficient_evidence=true; the model was not consulted")
                                .build());
                traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
                return new ChatResponse(reply.getId(), refusal, List.of(), false, true, 0, null);
            }

            // ---- 5. grounded prompt ----
            Set<Long> allowedChunkIds = passages.stream().map(RetrievedPassage::chunkId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> allowedFactIds = new LinkedHashSet<>();
            Map<String, String> graphFactText = new LinkedHashMap<>();
            for (Triple t : relevantTriples) {
                String factId = "GF-" + t.getId();
                allowedFactIds.add(factId);
                graphFactText.put(factId, t.getSubject() + " " + t.getPredicate() + " " + t.getObject()
                        + " (approved triple " + t.getId() + ")");
            }
            allowedFactIds.addAll(verdictSummary.keySet());

            traces.record(traceRun.getId(), root, ActorType.LLM, TraceEventType.LLM_REQUEST,
                    "Grounded chat request",
                    TraceRecorder.StepPayload.builder()
                            .inputSummary(allowedChunkIds.size() + " passages, "
                                    + allowedFactIds.size() + " graph/verdict facts")
                            .promptVersion(Prompts.CHAT_V1)
                            .model(llmProperties.chatModel())
                            .build());

            LlmCompletion completion;
            try {
                completion = llm.complete(Prompts.chatSystem(),
                        buildPrompt(cleanQuestion, passages, graphFactText, verdictSummary),
                        LlmProperties.Purpose.CHAT, Prompts.CHAT_V1,
                        "chat:session:" + sessionId, traceRun.getId(), root);
            } catch (LlmPermanentException | LlmTransientException ex) {
                log.warn("Chat call failed for session {}: {}", sessionId, ex.getMessage());
                traces.failure(traceRun.getId(), root, ActorType.LLM, TraceEventType.PIPELINE_FAILED,
                        "Chat call failed", ex.getMessage());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
                throw new ApiException(com.prism.common.error.ErrorCode.LLM_UNAVAILABLE,
                        "the assistant is unavailable: " + ex.getMessage(), ex);
            }

            // ---- 6. validate the response and its citations ----
            Parsed parsed = parse(completion.rawText(), allowedChunkIds, allowedFactIds);
            if (!parsed.valid()) {
                traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.VALIDATION_FAILED,
                        "Chat response rejected",
                        TraceRecorder.StepPayload.builder()
                                .status("REJECTED")
                                .outputSummary(parsed.error())
                                .build());
                traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, parsed.error());
                String safe = "The assistant produced a response that failed validation and has been "
                        + "discarded. No answer is shown because its citations could not be verified. "
                        + "Reason: " + parsed.error();
                ChatMessage reply = persistence.persistAssistantTurn(sessionId, user, safe, "[]",
                        completion.model(), Prompts.CHAT_V1, completion.durationMs(), passages.size(),
                        false, true, traceRun.getId());
                return new ChatResponse(reply.getId(), safe, List.of(), false, true,
                        passages.size(), completion.model());
            }

            // ---- 7. re-check every citation against the corpus, then store ----
            List<Citation> citations = new ArrayList<>();
            for (Long chunkId : parsed.citedChunkIds()) {
                var chunk = chunks.findByIdAndCorpusId(chunkId, corpusId);
                chunk.ifPresent(c -> citations.add(new Citation(
                        "CHUNK", chunkId, c.getDocument().getId(), c.getDocument().getTitle(),
                        c.getChunkIndex(), truncate(c.getContent(), 600))));
            }

            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.CITATION_VALIDATED,
                    "Chat citations validated",
                    TraceRecorder.StepPayload.builder()
                            .outputSummary(citations.size() + " chunk citations accepted, "
                                    + parsed.citedFactIds().size() + " graph fact citations accepted")
                            .outputRefs(parsed.citedChunkIds())
                            .build());

            String answer = parsed.answer();
            if (parsed.claimedInsufficient()) {
                answer = answer.isBlank()
                        ? "The corpus does not contain enough evidence to answer that question."
                        : answer;
            }

            ChatMessage reply = persistence.persistAssistantTurn(sessionId, user, answer,
                    toJson(citations), completion.model(), Prompts.CHAT_V1,
                    completion.durationMs(), passages.size(), true, parsed.claimedInsufficient(),
                    traceRun.getId());

            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.CHAT_RESPONSE,
                    "Grounded answer produced",
                    TraceRecorder.StepPayload.builder()
                            .outputSummary("grounded=true insufficient_evidence="
                                    + parsed.claimedInsufficient() + " citations=" + citations.size())
                            .build());
            traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);

            return new ChatResponse(reply.getId(), answer, citations, true,
                    parsed.claimedInsufficient(), passages.size(), completion.model());

        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Chat failed for session {}", sessionId, ex);
            traces.failure(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.PIPELINE_FAILED,
                    "Chat failed", ex.getMessage());
            traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
            throw ex;
        }
    }

    // ---- evidence selection ------------------------------------------------

    /** Approved triples whose text overlaps the question or the evidence. */
    private List<Triple> findRelevantTriples(Long corpusId, String question,
                                             List<RetrievedPassage> passages) {
        // Escape LIKE metacharacters before wrapping in %...%. Without this, a
        // question containing % matches every approved triple's subject and
        // object, stuffing same-corpus facts into the prompt. Bounded blast
        // radius (corpus-scoped, APPROVED-only, citation-checked), but
        // over-inclusion in a model prompt is still a defect.
        String needle = "%" + escapeLike(question.toLowerCase(Locale.ROOT).trim()) + "%";
        List<Triple> direct = triples.searchApprovedText(corpusId, needle,
                org.springframework.data.domain.PageRequest.of(0, 20));
        if (!direct.isEmpty()) {
            return direct;
        }
        // Fall back to triples whose text overlaps the retrieved evidence.
        if (passages.isEmpty()) {
            return List.of();
        }
        StringBuilder terms = new StringBuilder();
        for (RetrievedPassage p : passages.stream().limit(3).toList()) {
            p.text().toLowerCase(Locale.ROOT).lines()
                    .flatMap(line -> java.util.Arrays.stream(line.split("[^a-z0-9 ]+")))
                    .filter(t -> t.length() >= 4)
                    .limit(12)
                    .forEach(t -> terms.append(t).append(' '));
        }
        if (terms.isEmpty()) {
            return List.of();
        }
        return triples.searchApprovedTextForAll(corpusId,
                java.util.Arrays.stream(terms.toString().split(" "))
                        .filter(t -> !t.isBlank()).toList());
    }

    /**
     * Escapes the LIKE metacharacters {@code %}, {@code _} and the escape
     * character itself, so user text is matched literally. MySQL treats
     * backslash as the LIKE escape by default, which is what the queries here
     * run against.
     */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Verdict facts relevant to the question, keyed by a citable id. */
    private Map<String, String> summarizeVerdicts(Long corpusId, String question) {
        Map<String, String> facts = new LinkedHashMap<>();
        List<com.prism.claims.Verdict> recent = verdicts.findRecent(corpusId,
                org.springframework.data.domain.PageRequest.of(0, 25));
        String lower = question.toLowerCase(Locale.ROOT);
        for (com.prism.claims.Verdict v : recent) {
            var claim = v.getClaim();
            boolean relevant = lower.contains(claim.getSubject().toLowerCase(Locale.ROOT))
                    || containsAnyWord(lower, claim.getClaimText());
            if (!relevant) {
                continue;
            }
            facts.put("VD-" + claim.getId(),
                    "Claim " + claim.getId() + " (" + claim.getSubject() + ") is "
                            + v.getVerdictType() + " with evidence status " + v.getEvidenceStatus()
                            + (v.getAdjudicationState() == com.prism.claims.AdjudicationState.HUMAN_DECISION
                            ? ", decided by a human verifier" : ""));
            if (facts.size() >= 8) {
                break;
            }
        }
        return facts;
    }

    private static boolean containsAnyWord(String haystack, String text) {
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() >= 4 && haystack.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ---- prompt and response parsing ---------------------------------------

    String buildPrompt(String question, List<RetrievedPassage> passages,
                       Map<String, String> graphFacts, Map<String, String> verdictFacts) {
        StringBuilder sb = new StringBuilder();
        sb.append(Prompts.UNTRUSTED_DATA_PREAMBLE).append('\n');
        sb.append("<DATA id=\"evidence\">\n");
        for (RetrievedPassage p : passages) {
            sb.append("<PASSAGE id=\"").append(p.chunkId()).append("\" document=\"")
                    .append(escapeAttribute(p.documentTitle())).append("\">\n")
                    .append(p.text()).append("\n</PASSAGE>\n");
        }
        sb.append("</DATA>\n\n");

        sb.append("<DATA id=\"graph-facts\">\n");
        graphFacts.forEach((id, text) ->
                sb.append("<FACT id=\"").append(id).append("\">\n").append(text).append("\n</FACT>\n"));
        if (graphFacts.isEmpty()) {
            sb.append("(no approved triples matched)\n");
        }
        sb.append("</DATA>\n\n");

        sb.append("<DATA id=\"verdict-facts\">\n");
        verdictFacts.forEach((id, text) ->
                sb.append("<FACT id=\"").append(id).append("\">\n").append(text).append("\n</FACT>\n"));
        if (verdictFacts.isEmpty()) {
            sb.append("(no verdicts are relevant to this question)\n");
        }
        sb.append("</DATA>\n\n");

        sb.append("QUESTION: ").append(question).append('\n');
        sb.append("\nAnswer only from the DATA blocks above. Cite passage ids from evidence and "
                + "fact ids from graph-facts and verdict-facts exactly as written. If the data does "
                + "not answer the question, set sufficient_evidence to false and say so plainly.\n");
        return sb.toString();
    }

    private record Parsed(String answer, List<Long> citedChunkIds, List<String> citedFactIds,
                          boolean claimedInsufficient, boolean valid, String error) {
    }

    private Parsed parse(String rawText, Set<Long> allowedChunkIds, Set<String> allowedFactIds) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(rawText));
        } catch (Exception ex) {
            return new Parsed(null, List.of(), List.of(), false, false,
                    "response was not valid JSON: " + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            return new Parsed(null, List.of(), List.of(), false, false,
                    "response was not a JSON object");
        }
        String answer = root.path("answer").asText("").trim();
        if (answer.isEmpty()) {
            return new Parsed(null, List.of(), List.of(), false, false,
                    "response contained no 'answer' field");
        }
        if (answer.length() > 8000) {
            answer = answer.substring(0, 8000);
        }

        List<Object> rawChunkIds = new ArrayList<>();
        if (root.path("passage_ids").isArray()) {
            root.path("passage_ids").forEach(n -> rawChunkIds.add(n.isNumber() ? n.asLong() : n.asText()));
        }
        CitationValidator.Result<Long> chunkCitations =
                CitationValidator.validateChunkIds(rawChunkIds, allowedChunkIds);
        if (chunkCitations.hasHallucinatedCitations()) {
            return new Parsed(null, List.of(), List.of(), false, false,
                    "response cited passages that were not retrieved: "
                            + chunkCitations.rejected().stream()
                                    .map(CitationValidator.Rejection::id).toList());
        }

        List<Object> rawFactIds = new ArrayList<>();
        if (root.path("graph_fact_ids").isArray()) {
            root.path("graph_fact_ids").forEach(n -> rawFactIds.add(n.asText()));
        }
        CitationValidator.Result<String> factCitations =
                CitationValidator.validateStringIds(rawFactIds, allowedFactIds);
        if (factCitations.hasHallucinatedCitations()) {
            return new Parsed(null, List.of(), List.of(), false, false,
                    "response cited graph facts that were not supplied: "
                            + factCitations.rejected().stream()
                                    .map(CitationValidator.Rejection::id).toList());
        }

        boolean sufficient = !root.path("sufficient_evidence").asBoolean(true);
        return new Parsed(answer, chunkCitations.valid(), factCitations.valid(), !sufficient, true, null);
    }

    // ---- helpers -----------------------------------------------------------

    private String toJson(List<Citation> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception ex) {
            // A citation list that cannot be serialised must not lose the answer.
            // The message is still stored; it simply has no expandable citations.
            log.warn("Could not serialise {} citations: {}", citations.size(), ex.getMessage());
            return "[]";
        }
    }

    private static String escapeAttribute(String value) {
        return value == null ? "" : value.replace("\"", "'").replace("\n", " ");
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
