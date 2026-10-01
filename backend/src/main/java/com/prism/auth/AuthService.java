package com.prism.auth;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import com.prism.user.Role;
import com.prism.user.User;
import com.prism.user.UserPrincipal;
import com.prism.user.UserService;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login and registration orchestration.
 *
 * <p>Password verification is delegated to Spring Security's
 * {@code DaoAuthenticationProvider} so BCrypt cost and timing behaviour stay
 * framework-managed.
 */
@Service
public class AuthService {

    private final UserService users;
    private final JwtService jwt;
    private final AuthenticatedUserProvider authenticatedUsers;

    public AuthService(UserService users, JwtService jwt, AuthenticatedUserProvider authenticatedUsers) {
        this.users = users;
        this.jwt = jwt;
        this.authenticatedUsers = authenticatedUsers;
    }

    @Transactional
    public AuthResponse register(String username, String email, String password) {
        User user = users.register(username, email, password, Role.ANALYST);
        return issueFor(user);
    }

    public AuthResponse login(String identifier, String rawPassword) {
        UserPrincipal principal = authenticatedUsers.authenticate(identifier, rawPassword);
        User user = users.getById(principal.getId());
        return issueFor(user);
    }

    /** Issues a fresh token for the currently authenticated user. */
    @Transactional(readOnly = true)
    public AuthResponse refreshCurrentUser() {
        Long id = authenticatedUsers.requireCurrentUserId();
        return issueFor(users.getById(id));
    }

    private AuthResponse issueFor(User user) {
        JwtService.IssuedToken issued = jwt.issue(user.getId(), user.getUsername(), user.getRole());
        return new AuthResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt(),
                jwt.ttlSeconds(),
                new AuthUser(user.getId(), user.getUsername(), user.getEmail(), user.getRole()));
    }

    public record AuthResponse(String accessToken, String tokenType, java.time.Instant expiresAt,
                               long expiresInSeconds, AuthUser user) {
    }

    public record AuthUser(Long id, String username, String email, Role role) {
    }

    /**
     * Bridges Spring Security authentication into the domain. Implemented by
     * {@code SecurityConfig}'s provider chain plus a small adapter.
     */
    public interface AuthenticatedUserProvider {
        UserPrincipal authenticate(String identifier, String rawPassword);

        Long requireCurrentUserId();

        UserPrincipal requireCurrentPrincipal();

        com.prism.user.UserSummary requireCurrentUserSummary();
    }

    static AuthException disabled() {
        return new AuthException(new DisabledException("Account is disabled"));
    }

    static AuthException locked() {
        return new AuthException(new LockedException("Account is locked"));
    }

    static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password");
    }

    /** Marker so the provider chain can signal a failure without leaking the reason. */
    public static final class AuthException extends RuntimeException {
        public AuthException(RuntimeException cause) {
            super(cause);
        }
    }
}
