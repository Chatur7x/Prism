package com.prism.corpus;

import com.prism.common.error.ApiException;
import com.prism.user.User;
import com.prism.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class CorpusService {

    private static final int MAX_NAME = 200;
    private static final int MAX_DESCRIPTION = 2000;

    private final CorpusRepository corpora;
    private final CorpusAccessService access;
    private final UserService users;

    public CorpusService(CorpusRepository corpora, CorpusAccessService access, UserService users) {
        this.corpora = corpora;
        this.access = access;
        this.users = users;
    }

    @Transactional
    public Corpus create(Long userId, String name, String description) {
        User owner = users.getById(userId);
        String cleanName = validateName(name);
        String cleanDescription = validateDescription(description);
        return corpora.save(new Corpus(cleanName, cleanDescription, owner));
    }

    @Transactional(readOnly = true)
    public List<Corpus> list(Long userId) {
        return access.listAccessible(userId);
    }

    @Transactional(readOnly = true)
    public Corpus get(Long userId, Long corpusId) {
        return access.requireAccessible(corpusId, userId);
    }

    @Transactional
    public Corpus update(Long userId, Long corpusId, String name, String description) {
        Corpus corpus = access.requireAccessible(corpusId, userId);
        // Only the owner may reshape a corpus; ADMIN is not an implicit editor.
        if (!corpus.getOwner().getId().equals(userId)) {
            throw ApiException.forbidden("Only the corpus owner can modify it");
        }
        String cleanName = name == null ? null : validateName(name);
        String cleanDescription = description == null ? null : validateDescription(description);
        corpus.rename(cleanName, cleanDescription, Instant.now());
        return corpora.save(corpus);
    }

    /**
     * Archive rather than hard-delete. Deleting a corpus would destroy the
     * provenance chain of every document, verdict, and report inside it.
     */
    @Transactional
    public void archive(Long userId, Long corpusId) {
        Corpus corpus = access.requireAccessible(corpusId, userId);
        if (!corpus.getOwner().getId().equals(userId)) {
            throw ApiException.forbidden("Only the corpus owner can archive it");
        }
        corpus.archive(Instant.now());
        corpora.save(corpus);
    }

    @Transactional
    public Corpus reactivate(Long userId, Long corpusId) {
        Corpus corpus = access.requireAccessible(corpusId, userId);
        if (!corpus.getOwner().getId().equals(userId)) {
            throw ApiException.forbidden("Only the corpus owner can reactivate it");
        }
        corpus.reactivate(Instant.now());
        return corpora.save(corpus);
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.validation("corpus name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw ApiException.validation("corpus name must be at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }

    private String validateDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION) {
            throw ApiException.validation("corpus description must be at most " + MAX_DESCRIPTION + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
