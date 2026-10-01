package com.prism.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunable security settings. Nothing here has a usable production default:
 * {@code jwt-secret} must be supplied via {@code JWT_SECRET}.
 */
@ConfigurationProperties(prefix = "prism.security")
public record SecurityProperties(
        String jwtSecret,
        String jwtIssuer,
        int accessTokenTtlMinutes,
        int bcryptCost,
        String corsAllowedOrigins,
        String bootstrapAdminUsername,
        String bootstrapAdminEmail,
        String bootstrapAdminPassword,
        RateLimit rateLimit) {

    public record RateLimit(int authPerMinute, int llmPerMinute) {
    }
}
