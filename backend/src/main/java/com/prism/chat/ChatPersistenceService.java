package com.prism.chat;

import com.prism.common.error.ApiException;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunRepository;
import com.prism.user.User;
import com.prism.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Chat persistence, isolated from the model call.
 *
 * <p><b>Separate bean by necessity.</b> Spring's transaction advice is
 * proxy-based, so a {@code @Transactional} method called from inside its own
 * class never passes through the proxy and the annotation silently does
 * nothing. {@link ChatService#ask} performs model calls and deliberately holds
 * no transaction; keeping the writes here means each turn commits in its own
 * short transaction rather than pinning a pooled connection across a 60-second
 * model call.
 *
 * <p>Also materialises associations inside the transaction. With open-in-view
 * disabled, a {@link ChatSession} handed back to a caller would carry a lazy
 * {@code corpus} proxy that throws on first use.
 */
@Service
public class ChatPersistenceService {

    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final UserService users;
    private final TraceRunRepository traceRuns;
    private final com.prism.corpus.CorpusRepository corpora;

    public ChatPersistenceService(ChatSessionRepository sessions, ChatMessageRepository messages,
                                  UserService users, TraceRunRepository traceRuns,
                                  com.prism.corpus.CorpusRepository corpora) {
        this.sessions = sessions;
        this.messages = messages;
        this.users = users;
        this.traceRuns = traceRuns;
        this.corpora = corpora;
    }

    /**
     * The corpus facts a chat turn needs.
     *
     * <p>Plain values rather than entities, so nothing lazy escapes the session.
     */
    public record SessionScope(Long sessionId, Long corpusId, String corpusName) {
    }

    /**
     * Loads a session's scope, verifying ownership in the same query.
     *
     * <p>Ownership is a hard requirement: a session is private to the user who
     * opened it, independently of corpus access.
     */
    @Transactional(readOnly = true)
    public SessionScope loadScope(Long userId, Long sessionId) {
        ChatSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("Chat session", sessionId));
        return new SessionScope(session.getId(), session.getCorpus().getId(),
                session.getCorpus().getName());
    }

    /**
     * Attaches the corpus to a trace run so the trace remains attributable.
     *
     * <p>The Glass Box authorizes a trace by its corpus. A run with no corpus
     * would either be invisible or readable by anyone, and neither is
     * acceptable, so chat traces are linked immediately after creation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attachCorpusToTrace(Long traceRunId, Long corpusId) {
        if (traceRunId == null || corpusId == null) {
            return;
        }
        traceRuns.findById(traceRunId).ifPresent(run -> {
            corpora.findById(corpusId).ifPresent(run::attachCorpus);
            traceRuns.save(run);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistUserTurn(Long sessionId, Long userId, String question) {
        ChatSession session = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Chat session", sessionId));
        User user = users.getById(userId);
        messages.save(new ChatMessage(session, user, ChatMessage.Role.USER, question));
        session.recordMessage(Instant.now());
        sessions.save(session);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage persistAssistantTurn(Long sessionId, User user, String answer,
                                             String citationsJson, String model, String promptVersion,
                                             Long latencyMs, int retrievalCount, boolean grounded,
                                             boolean insufficient, Long traceRunId) {
        ChatSession session = sessions.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Chat session", sessionId));
        ChatMessage message = new ChatMessage(session, user, ChatMessage.Role.ASSISTANT, answer);
        message.attachAssistantMetadata(citationsJson, model, promptVersion, latencyMs,
                retrievalCount, grounded, insufficient, traceRunId);
        ChatMessage saved = messages.save(message);
        session.recordMessage(Instant.now());
        sessions.save(session);
        return saved;
    }
}
