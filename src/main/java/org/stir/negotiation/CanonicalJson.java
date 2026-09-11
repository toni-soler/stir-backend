package org.stir.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) for STIR's AgreementSnapshot, format
 * "STIR-AGREEMENT-JCS-1". Uses the same public {@code io.github.erdtman:java-json-canonicalization}
 * library osTRIS itself uses for its own normative AuthorizationPayload canonical bytes
 * (es.idynamicsax.ostris.core.OstrisWireCodec) - both are proven RFC 8785, not two independent
 * hand-rolled implementations that might silently disagree on locale, whitespace, number
 * formatting or Jackson-specific behavior. See CanonicalJsonTest for the shared Java/JavaScript
 * test vectors (js counterpart: stir-frontend's "canonicalize" package, also cross-verified
 * against this same erdtman library by osTRIS's own reference/node vector verifier).
 */
public final class CanonicalJson {
    public static final String FORMAT = "STIR-AGREEMENT-JCS-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private CanonicalJson() {}

    public static byte[] canonicalBytes(Map<String, Object> fields) {
        try {
            String json = MAPPER.writeValueAsString(fields);
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (Exception e) {
            throw new IllegalArgumentException("Snapshot fields are not valid I-JSON: " + e.getMessage(), e);
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
