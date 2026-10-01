package com.prism.auth;

import com.prism.config.SecurityProperties;
import com.prism.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates HMAC-SHA256 JWTs.
 *
 * <p>Carries {@code sub} (user id), {@code username}, {@code role}, and a
 * {@code jti}. The role claim is a convenience only — every authorization
 * decision still hits the database for object-level checks, so a stale role in
 * a token can never grant access the user no longer has.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtService(SecurityProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.issuer = props.jwtIssuer();
        this.ttl = Duration.ofMinutes(props.accessTokenTtlMinutes());
    }

    public IssuedToken issue(Long userId, String username, Role role) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role.name())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new IssuedToken(token, exp, jti);
    }

    /**
     * @throws JwtException if the token is malformed, tampered, or expired.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    public record IssuedToken(String token, Instant expiresAt, String jti) {
    }
}
