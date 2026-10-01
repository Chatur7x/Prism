package com.prism.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonical SHA-256 hashing.
 *
 * <p>Used for idempotency keys and content fingerprints. SHA-256 is chosen over
 * a faster non-cryptographic hash because these values end up in unique indexes
 * where collisions would merge distinct records.
 */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JRE; its absence is a broken platform.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
