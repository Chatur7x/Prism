package com.prism.auth;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import com.prism.user.User;
import com.prism.user.UserPrincipal;
import com.prism.user.UserRepository;
import com.prism.user.UserSummary;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concrete {@link AuthService.AuthenticatedUserProvider}.
 *
 * <p>Spring Security's DaoAuthenticationProvider compares against a dummy hash
 * when the account is absent, so response timing does not disclose whether a
 * username exists.
 */
@Service
public class SpringSecurityAuthProvider implements AuthService.AuthenticatedUserProvider {

    private final UserRepository users;
    private final AuthenticationProvider delegate;

    public SpringSecurityAuthProvider(UserRepository users, AuthenticationProvider delegate) {
        this.users = users;
        this.delegate = delegate;
    }

    @Override
    @Transactional(readOnly = true)
    public UserPrincipal authenticate(String identifier, String rawPassword) {
        try {
            Authentication auth = delegate.authenticate(
                    new UsernamePasswordAuthenticationToken(identifier, rawPassword));
            if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
                throw new BadCredentialsException("Authentication failed");
            }
            return principal;
        } catch (DisabledException ex) {
            // Distinct from bad credentials so the client can explain reactivation.
            throw new ApiException(ErrorCode.FORBIDDEN, "Account is disabled");
        } catch (UsernameNotFoundException | BadCredentialsException ex) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password");
        }
    }

    @Override
    public Long requireCurrentUserId() {
        return requireCurrentPrincipal().getId();
    }

    @Override
    public UserPrincipal requireCurrentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        return principal;
    }

    @Override
    @Transactional(readOnly = true)
    public UserSummary requireCurrentUserSummary() {
        return UserSummary.from(findById(requireCurrentUserId()));
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
    }
}
