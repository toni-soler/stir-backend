package org.stir.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal canonical JSON for STIR's own fixed, code-controlled AgreementSnapshot schema: sorted
 * keys, compact separators, UTF-8, no floating-point ambiguity (all numeric fields are pre-
 * formatted as decimal strings by the caller). This is not a general-purpose JCS/RFC 8785
 * implementation for arbitrary third-party JSON; STIR does not need one for a flat, versioned,
 * internally-defined object. Same field values always produce the same bytes and the same digest.
 */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private CanonicalJson() {}

    public static byte[] canonicalBytes(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsBytes(new TreeMap<>(fields));
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
