package com.prism.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.error.ApiErrorResponse;
import com.prism.common.error.ErrorCode;
import com.prism.common.error.TraceContext;
import com.prism.user.UserPrincipal;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts a bearer token, validates it, and populates the SecurityContext.
 *
 * <p>Never logs the Authorization header. Failures return a consistent JSON
 * body rather than an HTML error page.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserAccountService userAccounts;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, UserAccountService userAccounts, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userAccounts = userAccounts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (header != null && header.startsWith(BEARER)) {
            token = header.substring(BEARER.length()).trim();
        }
        if ((token == null || token.isEmpty()) && isSseStreamRequest(request)) {
            // The browser EventSource API cannot set request headers, so the
            // frontend sends the token as an access_token query parameter on
            // SSE stream endpoints only. Accepted here and nowhere else: the
            // token is short-lived and the endpoint is read-only.
            String param = request.getParameter("access_token");
            if (param != null && !param.isBlank()) {
                token = param.trim();
            }
        }
        if (token == null || token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            var claims = jwtService.parse(token);
            Long userId = Long.valueOf(claims.getSubject());
            // Re-read the account so a disabled or deleted user cannot keep acting on a live token.
            UserPrincipal principal = userAccounts.loadActivePrincipal(userId);
            if (principal == null) {
                writeUnauthorized(response, request, ErrorCode.UNAUTHENTICATED, "Account is not active");
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
        } catch (ExpiredJwtException ex) {
            writeUnauthorized(response, request, ErrorCode.TOKEN_EXPIRED, "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // Do not echo exception text: it can describe the token's structure.
            writeUnauthorized(response, request, ErrorCode.TOKEN_INVALID, "Access token is invalid");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * SSE stream endpoints are the only requests allowed to authenticate via
     * query parameter, because EventSource cannot set headers. Matches
     * {@code GET /api/debates/{id}/stream} exactly so no other endpoint
     * widens its accepted credential surface.
     */
    private static boolean isSseStreamRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.matches("/api/debates/\\d+/stream");
    }

    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request,
                                    ErrorCode code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(), code.name(), message,
                request.getRequestURI(), TraceContext.currentTraceId()));
    }
}
