package org.stir.reference;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

/** Minimal, purpose-built WebAuthn registration/assertion verification
 * (WEBAUTHN_HARDWARE_CUSTODY.md) - parses exactly the CBOR/binary structures STIR actually checks
 * (clientDataJSON, authenticatorData, a COSE_Key public key) and verifies a signature over them.
 * Deliberately not a general WebAuthn relying-party library: no attestation statement/certificate
 * chain verification (attestation is "none" by policy - a clear security benefit was never shown
 * for requiring it here), no metadata service, no FIDO conformance surface. Mirrors
 * SevenKeysCrypto's style: small static utilities, IllegalArgumentException on any mismatch. */
final class WebAuthnCrypto {
    private WebAuthnCrypto() {}
    private static final CBORFactory CBOR = new CBORFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    // COSE algorithm identifiers (RFC 8152 §8) this codebase verifies. EdDSA is included so a
    // security key that happens to support Ed25519 assertions is not artificially excluded, even
    // though most platform/roaming authenticators today default to ES256.
    private static final Map<Long, String> COSE_ALGORITHMS = Map.of(-7L, "ES256", -257L, "RS256", -8L, "EdDSA");

    record ParsedAuthenticatorData(byte[] rpIdHash, boolean userPresent, boolean userVerified, long signCount,
                                    byte[] credentialId, byte[] coseKeyCbor) {}

    /** COSE_Key values are always either an integer (kty/alg/crv) or a byte string (x/y/n/e) - no
     * nested maps, arrays or booleans ever appear in the keys this codebase supports, so a
     * round-trip only needs to handle those two value shapes. */
    private static byte[] reencodeCoseKeyCanonically(Map<Object, Object> map) {
        try {
            var out = new java.io.ByteArrayOutputStream();
            try (var gen = CBOR.createGenerator(out)) {
                gen.writeStartObject();
                for (var entry : map.entrySet()) {
                    if (entry.getKey() instanceof Long l) gen.writeFieldId(l); else gen.writeFieldName((String) entry.getKey());
                    Object v = entry.getValue();
                    if (v instanceof byte[] b) gen.writeBinary(b);
                    else if (v instanceof Number n) gen.writeNumber(n.longValue());
                    else throw new IllegalArgumentException("Unsupported COSE_Key value type: " + v);
                }
                gen.writeEndObject();
            }
            return out.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalArgumentException("Could not re-encode COSE_Key", e); }
    }

    static String base64url(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    static byte[] decodeBase64url(String value, String field) {
        if (value == null || value.isEmpty() || value.contains("=") || !value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Non-canonical base64url in " + field);
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid base64url in " + field, e); }
    }

    static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Parses the fixed binary layout: rpIdHash[32] | flags[1] | signCount[4] | attestedCredentialData?
     * (aaguid[16] | credIdLen[2] | credId[credIdLen] | COSE_Key CBOR | extensions CBOR?). Extension
     * data, when present, is never interpreted - nothing this codebase checks lives there. */
    static ParsedAuthenticatorData parseAuthenticatorData(byte[] authData) {
        if (authData == null || authData.length < 37) throw new IllegalArgumentException("authenticatorData too short");
        byte[] rpIdHash = Arrays.copyOfRange(authData, 0, 32);
        int flags = authData[32] & 0xFF;
        boolean userPresent = (flags & 0x01) != 0, userVerified = (flags & 0x04) != 0;
        boolean attestedDataIncluded = (flags & 0x40) != 0;
        long signCount = ((authData[33] & 0xFFL) << 24) | ((authData[34] & 0xFFL) << 16)
            | ((authData[35] & 0xFFL) << 8) | (authData[36] & 0xFFL);
        byte[] credentialId = null, coseKeyCbor = null;
        if (attestedDataIncluded) {
            int offset = 37 + 16; // skip aaguid
            if (authData.length < offset + 2) throw new IllegalArgumentException("Truncated attestedCredentialData");
            int credIdLength = ((authData[offset] & 0xFF) << 8) | (authData[offset + 1] & 0xFF);
            offset += 2;
            if (authData.length < offset + credIdLength) throw new IllegalArgumentException("Truncated credentialId");
            credentialId = Arrays.copyOfRange(authData, offset, offset + credIdLength);
            offset += credIdLength;
            // The COSE_Key is decoded then canonically re-encoded (never sliced by inferred byte
            // offset - Jackson's streaming parser does not reliably expose "bytes consumed" for a
            // sub-buffer read) so the exact bytes stored/returned are always self-consistent CBOR.
            try (var parser = CBOR.createParser(Arrays.copyOfRange(authData, offset, authData.length))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("Expected COSE_Key map");
                coseKeyCbor = reencodeCoseKeyCanonically(readCborObject(parser));
            } catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid COSE_Key CBOR", e); }
        }
        return new ParsedAuthenticatorData(rpIdHash, userPresent, userVerified, signCount, credentialId, coseKeyCbor);
    }

    /** Decodes a COSE_Key CBOR map into a plain key->value map: integer-valued map keys (every
     * field COSE_Key uses) come back as Long, so callers can match RFC 8152's registered labels
     * directly (1=kty, 3=alg, -1/-2/-3=type-specific fields) without a bespoke CBOR object model. */
    private static Map<Object, Object> decodeCborMap(byte[] cbor) {
        try (var parser = CBOR.createParser(cbor)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("Expected a CBOR map");
            return readCborObject(parser);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid CBOR", e); }
    }
    private static Map<Object, Object> readCborObject(com.fasterxml.jackson.dataformat.cbor.CBORParser p) throws java.io.IOException {
        Map<Object, Object> map = new LinkedHashMap<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String name = p.currentName();
            Object key; try { key = Long.parseLong(name); } catch (NumberFormatException e) { key = name; }
            map.put(key, readCborValue(p, p.nextToken()));
        }
        return map;
    }
    private static Object readCborValue(com.fasterxml.jackson.dataformat.cbor.CBORParser p, JsonToken t) throws java.io.IOException {
        return switch (t) {
            case START_OBJECT -> readCborObject(p);
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_EMBEDDED_OBJECT -> p.getBinaryValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Unsupported CBOR token in COSE_Key: " + t);
        };
    }

    record CoseKey(String algorithm, PublicKey publicKey) {}

    static CoseKey publicKeyFromCose(byte[] coseKeyCbor) {
        var map = decodeCborMap(coseKeyCbor);
        long kty = ((Number) map.get(1L)).longValue();
        long algId = ((Number) map.get(3L)).longValue();
        String algorithm = COSE_ALGORITHMS.get(algId);
        if (algorithm == null) throw new IllegalArgumentException("Unsupported COSE algorithm: " + algId);
        try {
            if (kty == 2) { // EC2
                long crv = ((Number) map.get(-1L)).longValue();
                if (crv != 1) throw new IllegalArgumentException("Unsupported EC2 curve: " + crv); // 1 = P-256
                byte[] x = (byte[]) map.get(-2L), y = (byte[]) map.get(-3L);
                var params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec("secp256r1"));
                var ecParams = params.getParameterSpec(ECParameterSpec.class);
                var point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
                var key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecParams));
                return new CoseKey(algorithm, key);
            } else if (kty == 3) { // RSA
                byte[] n = (byte[]) map.get(-1L), e = (byte[]) map.get(-2L);
                var key = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
                return new CoseKey(algorithm, key);
            } else if (kty == 1) { // OKP (Ed25519)
                long crv = ((Number) map.get(-1L)).longValue();
                if (crv != 6) throw new IllegalArgumentException("Unsupported OKP curve: " + crv); // 6 = Ed25519
                byte[] x = (byte[]) map.get(-2L);
                byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
                byte[] encoded = Arrays.copyOf(prefix, prefix.length + x.length);
                System.arraycopy(x, 0, encoded, prefix.length, x.length);
                var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
                return new CoseKey(algorithm, key);
            }
            throw new IllegalArgumentException("Unsupported COSE key type: " + kty);
        } catch (GeneralSecurityException e) { throw new IllegalArgumentException("Invalid COSE public key", e); }
    }

    private static String jcaSignatureAlgorithm(String coseAlgorithm) {
        return switch (coseAlgorithm) {
            case "ES256" -> "SHA256withECDSA";
            case "RS256" -> "SHA256withRSA";
            case "EdDSA" -> "Ed25519";
            default -> throw new IllegalArgumentException("Unsupported algorithm: " + coseAlgorithm);
        };
    }

    record ClientData(String type, byte[] challenge, String origin, Boolean crossOrigin) {}

    static ClientData parseClientDataJson(byte[] clientDataJsonBytes) {
        try {
            var node = JSON.readTree(clientDataJsonBytes);
            String type = node.path("type").asText(null);
            String challengeB64 = node.path("challenge").asText(null);
            String origin = node.path("origin").asText(null);
            if (type == null || challengeB64 == null || origin == null)
                throw new IllegalArgumentException("Incomplete clientDataJSON");
            Boolean crossOrigin = node.has("crossOrigin") ? node.path("crossOrigin").asBoolean() : null;
            return new ClientData(type, decodeBase64url(challengeB64, "clientDataJSON.challenge"), origin, crossOrigin);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid clientDataJSON", e); }
    }

    /** Verifies a WebAuthn assertion (navigator.credentials.get()) proves possession of the exact
     * credential over the exact domain-separated STIR message, and returns the authenticator's
     * reported signCount so the caller can enforce/store monotonic advancement. Binding to "this
     * exact action, never another" comes from `expectedChallenge` being
     * base64url(SHA-256(domain || 0x00 || JCS payload)) of that exact governance payload - a
     * different proposal, community, tenant, constitution version or seat produces a different
     * payload, hence a different digest, hence a clientDataJSON.challenge mismatch here. */
    static long verifyAssertion(byte[] coseKeyCbor, String algorithm, CredentialEnvelope envelope,
            byte[] expectedChallenge, String expectedRpId, Set<String> allowedOrigins,
            boolean requireUserVerification, long minSignCount) {
        if (!"WEBAUTHN".equals(envelope.credentialType()))
            throw new IllegalArgumentException("Expected a WebAuthn credential envelope");
        byte[] clientDataJsonBytes = decodeBase64url(envelope.clientDataJson(), "clientDataJson");
        byte[] authenticatorData = decodeBase64url(envelope.authenticatorData(), "authenticatorData");
        byte[] signature = decodeBase64url(envelope.signature(), "signature");
        var clientData = parseClientDataJson(clientDataJsonBytes);
        if (!"webauthn.get".equals(clientData.type())) throw new IllegalArgumentException("Not an assertion response");
        if (!Arrays.equals(expectedChallenge, clientData.challenge())) throw new IllegalArgumentException("Challenge mismatch");
        if (!allowedOrigins.contains(clientData.origin())) throw new IllegalArgumentException("Origin not allowed");
        if (Boolean.TRUE.equals(clientData.crossOrigin())) throw new IllegalArgumentException("Cross-origin assertion rejected");
        var parsed = parseAuthenticatorData(authenticatorData);
        if (!Arrays.equals(parsed.rpIdHash(), sha256(expectedRpId.getBytes(StandardCharsets.UTF_8))))
            throw new IllegalArgumentException("RP ID hash mismatch");
        if (!parsed.userPresent()) throw new IllegalArgumentException("User presence flag not set");
        if (requireUserVerification && !parsed.userVerified()) throw new IllegalArgumentException("User verification required");
        // Per the WebAuthn spec: once a counter has ever been nonzero, every later assertion must
        // report a strictly greater value (including rejecting a drop back to 0) - a stalled or
        // non-advancing counter is the spec's own signal of a possibly cloned authenticator. An
        // authenticator whose counter has never left 0 (many platform authenticators with resident
        // keys) is exempt from this check by construction, since minSignCount starts at 0.
        if (minSignCount > 0 && parsed.signCount() <= minSignCount)
            throw new IllegalArgumentException("Signature counter did not advance - possible cloned authenticator");
        var cose = publicKeyFromCose(coseKeyCbor);
        if (!cose.algorithm().equals(algorithm)) throw new IllegalArgumentException("Algorithm mismatch");
        byte[] signedBytes = new byte[authenticatorData.length + 32];
        System.arraycopy(authenticatorData, 0, signedBytes, 0, authenticatorData.length);
        System.arraycopy(sha256(clientDataJsonBytes), 0, signedBytes, authenticatorData.length, 32);
        try {
            var verifier = Signature.getInstance(jcaSignatureAlgorithm(algorithm));
            verifier.initVerify(cose.publicKey());
            verifier.update(signedBytes);
            if (!verifier.verify(signature)) throw new IllegalArgumentException("Invalid WebAuthn assertion signature");
        } catch (GeneralSecurityException e) { throw new IllegalArgumentException("Invalid WebAuthn signature", e); }
        return parsed.signCount();
    }

    record RegistrationResult(byte[] webauthnCredentialId, byte[] coseKeyCbor, String algorithm,
                               boolean userVerified, long signCount) {}

    /** Verifies a WebAuthn registration (navigator.credentials.create()) response with attestation
     * format "none" - the only format this deployment requests (WEBAUTHN_HARDWARE_CUSTODY.md: no
     * attestation trust chain, no metadata service, no AAGUID allowlist). "attStmt" is intentionally
     * never parsed: with fmt=none there is nothing in it to verify, and other formats are rejected
     * outright rather than silently accepted with an unverified statement. */
    static RegistrationResult verifyRegistration(byte[] attestationObjectCbor, byte[] clientDataJsonBytes,
            byte[] expectedChallenge, String expectedRpId, Set<String> allowedOrigins) {
        var clientData = parseClientDataJson(clientDataJsonBytes);
        if (!"webauthn.create".equals(clientData.type())) throw new IllegalArgumentException("Not a registration response");
        if (!Arrays.equals(expectedChallenge, clientData.challenge())) throw new IllegalArgumentException("Challenge mismatch");
        if (!allowedOrigins.contains(clientData.origin())) throw new IllegalArgumentException("Origin not allowed");
        var attestation = decodeCborMap(attestationObjectCbor);
        String fmt = (String) attestation.get("fmt");
        if (!"none".equals(fmt)) throw new IllegalArgumentException("Only attestation format 'none' is accepted");
        byte[] authData = (byte[]) attestation.get("authData");
        if (authData == null) throw new IllegalArgumentException("Missing authData");
        var parsed = parseAuthenticatorData(authData);
        if (!Arrays.equals(parsed.rpIdHash(), sha256(expectedRpId.getBytes(StandardCharsets.UTF_8))))
            throw new IllegalArgumentException("RP ID hash mismatch");
        if (!parsed.userPresent()) throw new IllegalArgumentException("User presence flag not set");
        if (parsed.credentialId() == null || parsed.coseKeyCbor() == null)
            throw new IllegalArgumentException("No attested credential data present");
        var cose = publicKeyFromCose(parsed.coseKeyCbor()); // validates the COSE key parses/is a supported algorithm
        return new RegistrationResult(parsed.credentialId(), parsed.coseKeyCbor(), cose.algorithm(),
            parsed.userVerified(), parsed.signCount());
    }
}
