package com.prism.config;

import com.prism.common.error.ApiException;
import com.prism.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Guards the JWT secret and CORS configuration.
 *
 * <p>Fails closed: an unset or placeholder secret aborts startup rather than
 * silently falling back to a well-known key.
 */
@Component
public class SecurityConfigValidator {

    /** Obvious placeholders that must never reach a real deployment. */
    private static final List<String> FORBIDDEN_SECRET_FRAGMENTS = List.of(
            "change-me", "changeme", "your-secret", "placeholder", "example-secret");

    private static final int MIN_SECRET_BYTES = 32;

    public SecurityConfigValidator(SecurityProperties props) {
        String secret = props.jwtSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. PRISM refuses to start without an explicit signing key. "
                            + "Generate one with: openssl rand -base64 48");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes; got "
                            + secret.length());
        }
        String lower = secret.toLowerCase();
        for (String fragment : FORBIDDEN_SECRET_FRAGMENTS) {
            if (lower.contains(fragment)) {
                throw new IllegalStateException("JWT_SECRET looks like a placeholder. Refusing to start.");
            }
        }
        if (props.corsAllowedOrigins() != null && props.corsAllowedOrigins().contains("*")) {
            throw new IllegalStateException(
                    "prism.security.cors-allowed-origins must not be '*'. List explicit origins instead.");
        }
    }

    public static SecretKey deriveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "JWT secret not configured");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
