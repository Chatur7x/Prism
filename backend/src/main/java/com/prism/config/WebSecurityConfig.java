package com.prism.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.auth.JwtAuthenticationFilter;
import com.prism.auth.JwtService;
import com.prism.auth.UserAccountService;
import com.prism.common.error.ApiErrorResponse;
import com.prism.common.error.ErrorCode;
import com.prism.common.error.TraceContext;
import com.prism.security.RateLimitFilter;
import com.prism.user.Role;
import com.prism.user.UserPrincipal;
import com.prism.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stateless JWT security.
 *
 * <p>Route rules here are only a coarse first gate. Object-level authorization
 * — does this user own or share <em>this</em> corpus / document / debate — is
 * enforced separately in the service layer, because a valid token must never
 * by itself grant access to a specific resource.
 */
@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    private final SecurityProperties props;
    private final ObjectMapper objectMapper;

    public WebSecurityConfig(SecurityProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(props.bcryptCost());
    }

    /**
     * Username lookup for password authentication. Returning an empty Optional
     * lets DaoAuthenticationProvider apply its own dummy-hash comparison, which
     * keeps response timing uniform for unknown users.
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return identifier -> users
                .findByUsernameIgnoreCase(identifier.trim())
                .or(() -> users.findByEmailIgnoreCase(identifier.trim().toLowerCase(Locale.ROOT)))
                .filter(user -> user.isEnabled())
                .map(UserPrincipal::new)
                .orElse(null);
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
                                                               PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(encoder);
        // Collapse "no such user" into "bad credentials" so accounts cannot be enumerated.
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return provider::authenticate;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                           UserAccountService accounts,
                                                           ObjectMapper mapper) {
        return new JwtAuthenticationFilter(jwtService, accounts, mapper);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(SecurityProperties props, ObjectMapper objectMapper) {
        return new RateLimitFilter(props, objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   DaoAuthenticationProvider authProvider) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicy(p -> p.policy("geolocation=(), microphone=(), camera=()")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/v3/api-docs/**", "/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .authenticationProvider(authProvider)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, request, HttpStatus.UNAUTHORIZED,
                                        ErrorCode.UNAUTHENTICATED, "Authentication required"))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, request, HttpStatus.FORBIDDEN,
                                        ErrorCode.ACCESS_DENIED, "Access denied for this resource")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // After authentication so per-account buckets are keyed on the real user.
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, HttpStatus status,
                            ErrorCode code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(
                status.value(), code.name(), message, request.getRequestURI(), TraceContext.currentTraceId()));
    }

    /**
     * CORS for the API.
     *
     * <p><b>An empty allow-list is valid and means "no cross-origin access".</b>
     * Requiring at least one origin was wrong, and it broke the Docker stack
     * outright: under nginx the frontend and the API are served from the same
     * origin and the browser makes same-origin requests, so no CORS origin is
     * needed — and the backend refused to start because none was configured.
     * The configuration that is correct for the deployment everyone actually runs
     * was rejected by a check that was supposed to protect them.
     *
     * <p>An empty list is safe, not permissive: with no allowed origins, Spring
     * permits no cross-origin request at all, which is strictly tighter than
     * allowing some. The thing that must never be permitted is {@code *}, and
     * that is still refused — it is the one genuinely dangerous value, because it
     * grants every origin access to a bearer-token API.
     *
     * <p>Setting origins is still the right move for a split deployment, where a
     * separately-served frontend calls this API cross-origin. The Docker stack
     * leaves it empty because nginx makes that unnecessary.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(props.corsAllowedOrigins().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "Wildcard CORS origin is not permitted; list the origins explicitly, "
                            + "or leave the list empty for a same-origin deployment");
        }
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id", "Accept"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
