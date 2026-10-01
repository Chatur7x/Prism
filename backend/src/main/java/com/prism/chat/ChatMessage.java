package com.prism.chat;

import com.prism.corpus.Corpus;
import com.prism.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One turn in a grounded conversation.
 *
 * <p>{@code grounded} and {@code insufficientEvidence} are separate flags
 * because the UI must be able to say "here is what the corpus says" while also
 * saying "and this part is not settled". Collapsing them into one confidence
 * number is how a chat UI ends up implying certainty the backend never had.
 */
@Entity
@Table(name = "chat_messages",
        indexes = {
                @Index(name = "ix_chatmessage_session", columnList = "session_id,created_at"),
                @Index(name = "ix_chatmessage_corpus", columnList = "corpus_id"),
                @Index(name = "ix_chatmessage_user", columnList = "user_id")
        })
public class ChatMessage {

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Only ids the backend validated. A stored message is always re-verifiable. */
    @Lob
    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "model", length = 200)
    private String model;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "retrieval_count", nullable = false)
    private int retrievalCount;

    /** True when the answer rests on validated retrieved evidence. */
    @Column(nullable = false)
    private boolean grounded;

    /** True when the corpus does not contain enough to answer. */
    @Column(name = "insufficient_evidence", nullable = false)
    private boolean insufficientEvidence;

    @Column(name = "trace_run_id")
    private Long traceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ChatMessage() {
        // for JPA
    }

    public ChatMessage(ChatSession session, User user, Role role, String content) {
        this.session = session;
        this.corpus = session.getCorpus();
        this.user = user;
        this.role = role;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public void attachAssistantMetadata(String citationsJson, String model, String promptVersion,
                                       Long latencyMs, int retrievalCount, boolean grounded,
                                       boolean insufficientEvidence, Long traceRunId) {
        this.citationsJson = citationsJson;
        this.model = model;
        this.promptVersion = promptVersion;
        this.latencyMs = latencyMs;
        this.retrievalCount = retrievalCount;
        this.grounded = grounded;
        this.insufficientEvidence = insufficientEvidence;
        this.traceRunId = traceRunId;
    }

    public Long getId() {
        return id;
    }

    public ChatSession getSession() {
        return session;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public int getRetrievalCount() {
        return retrievalCount;
    }

    public boolean isGrounded() {
        return grounded;
    }

    public boolean isInsufficientEvidence() {
        return insufficientEvidence;
    }

    public Long getTraceRunId() {
        return traceRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
