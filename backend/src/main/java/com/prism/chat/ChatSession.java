package com.prism.chat;

import com.prism.corpus.Corpus;
import com.prism.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A conversation, permanently bound to one corpus.
 *
 * <p>There is no cross-corpus chat. A session cannot cite outside the corpus it
 * was created in, because retrieval for every message in it is scoped to
 * {@code corpus_id} alone.
 */
@Entity
@Table(name = "chat_sessions",
        indexes = {
                @Index(name = "ix_chatsession_user", columnList = "user_id"),
                @Index(name = "ix_chatsession_corpus", columnList = "corpus_id"),
                @Index(name = "ix_chatsession_user_updated", columnList = "user_id,updated_at")
        })
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "corpus_id", nullable = false)
    private Corpus corpus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ChatSession() {
        // for JPA
    }

    public ChatSession(Corpus corpus, User user, String title) {
        this.corpus = corpus;
        this.user = user;
        this.title = title;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void recordMessage(Instant now) {
        this.messageCount++;
        this.lastMessageAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public User getUser() {
        return user;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public long getVersion() {
        return version;
    }
}
