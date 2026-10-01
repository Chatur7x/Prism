package com.prism.corpus;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import com.prism.user.Role;
import com.prism.user.User;
import com.prism.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The single authority on corpus access.
 *
 * <p>Every service that reads or mutates corpus-scoped data must obtain its
 * {@link Corpus} through this class. Nothing else is permitted to call
 * {@code CorpusRepository.findById} directly, because that bypasses the
 * ownership check and is the classic route to cross-corpus data leakage.
 */
@Service
public class CorpusAccessService {

    private final CorpusRepository corpora;
    private final UserRepository users;
    private final com.prism.auth.AuthService.AuthenticatedUserProvider currentUser;

    public CorpusAccessService(CorpusRepository corpora, UserRepository users,
                               com.prism.auth.AuthService.AuthenticatedUserProvider currentUser) {
        this.corpora = corpora;
        this.users = users;
        this.currentUser = currentUser;
    }

    /**
     * Resolves the caller's id from the SecurityContext, or throws 401.
     * Controllers use this so no request handler ever trusts a client-supplied user id.
     */
    public Long requireCurrentUserId() {
        return currentUser.requireCurrentUserId();
    }

    /**
     * Resolves an accessible corpus or denies.
     *
     * <p>ADMIN is granted system-wide access by explicit policy. Everyone else
     * needs ownership. A missing corpus returns 404 rather than 403, so the API
     * does not confirm the existence of a corpus the caller cannot see.
     */
    @Transactional(readOnly = true)
    public Corpus requireAccessible(Long corpusId, Long userId) {
        Corpus corpus = corpora.findById(corpusId)
                .orElseThrow(() -> ApiException.notFound("Corpus", corpusId));
        if (userId == null) {
            throw ApiException.accessDenied("Authentication required");
        }
        if (isAdmin(userId) || corpus.getOwner().getId().equals(userId)) {
            return corpus;
        }
        throw ApiException.accessDenied("You do not have access to corpus " + corpusId);
    }

    /** Same as {@link #requireAccessible} but additionally rejects non-ACTIVE corpora. */
    @Transactional(readOnly = true)
    public Corpus requireActiveAccessible(Long corpusId, Long userId) {
        Corpus corpus = requireAccessible(corpusId, userId);
        if (!corpus.isActive()) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "Corpus " + corpusId + " is archived");
        }
        return corpus;
    }

    @Transactional(readOnly = true)
    public List<Corpus> listAccessible(Long userId) {
        return isAdmin(userId) ? corpora.findAll().stream().filter(Corpus::isActive).toList()
                : corpora.findActiveByOwner(userId);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(Long userId) {
        return users.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isVerifier(Long userId) {
        return users.findById(userId)
                .map(u -> u.getRole() == Role.VERIFIER || u.getRole() == Role.ADMIN)
                .orElse(false);
    }

    /** Guards privileged actions (approval, adjudication, chairing) beyond mere read access. */
    @Transactional(readOnly = true)
    public void requireVerifier(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required"));
        if (user.getRole() != Role.VERIFIER && user.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("This action requires the VERIFIER or ADMIN role");
        }
    }

    @Transactional(readOnly = true)
    public void requireAdmin(Long userId) {
        if (!isAdmin(userId)) {
            throw ApiException.forbidden("This action requires the ADMIN role");
        }
    }
}
