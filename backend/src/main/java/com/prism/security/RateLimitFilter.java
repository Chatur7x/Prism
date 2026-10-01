package com.prism.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.error.ApiErrorResponse;
import com.prism.common.error.ErrorCode;
import com.prism.common.error.TraceContext;
import com.prism.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.prism.user.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Two independent buckets: a tight one for credential endpoints and a looser
 * one for LLM-heavy operations, so a login flood cannot exhaust the budget that
 * document verification depends on.
 *
 * <p>Installed inside the Spring Security filter chain <em>after</em> the JWT
 * filter, so the authenticated user id is available for per-account limiting.
 * Installed as a plain servlet filter it would always see an anonymous context.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter authLimiter;
    private final FixedWindowRateLimiter llmLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(SecurityProperties props, ObjectMapper objectMapper) {
        this.authLimiter = new FixedWindowRateLimiter(
                props.rateLimit().authPerMinute(), Duration.ofMinutes(1));
        this.llmLimiter = new FixedWindowRateLimiter(
                props.rateLimit().llmPerMinute(), Duration.ofMinutes(1));
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")
                || path.startsWith("/api/claims") || path.startsWith("/api/debates")
                || path.startsWith("/api/chat"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caller = clientKey(request);
        boolean isAuth = request.getRequestURI().startsWith("/api/auth/");
        FixedWindowRateLimiter limiter = isAuth ? authLimiter : llmLimiter;

        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(caller);
        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    ErrorCode.RATE_LIMITED.name(),
                    "Too many requests. Retry after the rate-limit window resets.",
                    request.getRequestURI(), TraceContext.currentTraceId()));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Prefers the authenticated user so a shared NAT does not exhaust one
     * account's budget. Falls back to client address for unauthenticated calls.
     *
     * <p>Reads the SecurityContext rather than {@code request.getPrincipal()},
     * because this filter runs before the authentication filter populates the
     * servlet request's principal.
     */
    private String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return "user:" + principal.getId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
