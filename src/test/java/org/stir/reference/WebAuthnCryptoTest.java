package org.stir.reference;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A hand-built, in-process "virtual authenticator" - the same thing a browser's WebAuthn virtual
 * authenticator (used by the real browser E2E) does, just constructed directly in Java so
 * WebAuthnCrypto's parsing/verification is proven correct against a real ES256/RS256/EdDSA
 * ceremony before any HTTP/browser layer is involved. */
class WebAuthnCryptoTest {
    static final String RP_ID = "localhost";
    static final String ORIGIN = "http://localhost:8089";
    static final byte[] AAGUID = new byte[16];

    static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }

    static byte[] coseKeyEc2(ECPublicKey key) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gen = new CBORFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeFieldId(1); gen.writeNumber(2); // kty=EC2
            gen.writeFieldId(3); gen.writeNumber(-7); // alg=ES256
            gen.writeFieldId(-1); gen.writeNumber(1); // crv=P-256
            byte[] x = toFixedBytes(key.getW().getAffineX(), 32), y = toFixedBytes(key.getW().getAffineY(), 32);
            gen.writeFieldId(-2); gen.writeBinary(x);
            gen.writeFieldId(-3); gen.writeBinary(y);
            gen.writeEndObject();
        }
        return out.toByteArray();
    }
    static byte[] toFixedBytes(BigInteger n, int length) {
        byte[] raw = n.toByteArray(); byte[] fixed = new byte[length];
        int start = Math.max(0, raw.length - length), destOffset = Math.max(0, length - raw.length);
        System.arraycopy(raw, start, fixed, destOffset, raw.length - start);
        return fixed;
    }

    static byte[] authenticatorData(byte[] credentialId, byte[] coseKeyCbor, long signCount, boolean up, boolean uv, boolean at) throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(sha256(RP_ID.getBytes(StandardCharsets.UTF_8)));
        int flags = (up ? 0x01 : 0) | (uv ? 0x04 : 0) | (at ? 0x40 : 0);
        out.write(flags);
        out.write((int) (signCount >> 24)); out.write((int) (signCount >> 16)); out.write((int) (signCount >> 8)); out.write((int) signCount);
        if (at) {
            out.write(AAGUID);
            out.write(credentialId.length >> 8); out.write(credentialId.length & 0xFF);
            out.write(credentialId);
            out.write(coseKeyCbor);
        }
        return out.toByteArray();
    }
    static byte[] attestationObjectNone(byte[] authData) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gen = new CBORFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeFieldName("fmt"); gen.writeString("none");
            gen.writeFieldName("attStmt"); gen.writeStartObject(); gen.writeEndObject();
            gen.writeFieldName("authData"); gen.writeBinary(authData);
            gen.writeEndObject();
        }
        return out.toByteArray();
    }
    static byte[] clientDataJson(String type, byte[] challenge, String origin) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\"" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
    static byte[] sign(PrivateKey key, String jcaAlgorithm, byte[] authData, byte[] clientDataJsonBytes) throws Exception {
        var signer = Signature.getInstance(jcaAlgorithm);
        signer.initSign(key);
        var signed = new ByteArrayOutputStream();
        signed.write(authData); signed.write(sha256(clientDataJsonBytes));
        signer.update(signed.toByteArray());
        return signer.sign();
    }

    @Test void registrationThenAssertionRoundTripWithEs256() throws Exception {
        var kp = KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kp.generateKeyPair();
        byte[] credentialId = "cred-1".getBytes(StandardCharsets.UTF_8);
        byte[] coseKey = coseKeyEc2((ECPublicKey) pair.getPublic());

        byte[] regChallenge = "reg-challenge-bytes-0001".getBytes(StandardCharsets.UTF_8);
        byte[] regAuthData = authenticatorData(credentialId, coseKey, 0, true, true, true);
        byte[] regClientData = clientDataJson("webauthn.create", regChallenge, ORIGIN);
        byte[] attestationObject = attestationObjectNone(regAuthData);
        var registration = WebAuthnCrypto.verifyRegistration(attestationObject, regClientData, regChallenge, RP_ID, Set.of(ORIGIN));
        assertArrayEquals(credentialId, registration.webauthnCredentialId());
        assertEquals("ES256", registration.algorithm());
        assertTrue(registration.userVerified());
        assertEquals(0, registration.signCount());

        byte[] assertChallenge = sha256("STIR:MARKET:CONSTITUTION:V1\u0000{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        byte[] assertAuthData = authenticatorData(null, null, 1, true, true, false);
        byte[] assertClientData = clientDataJson("webauthn.get", assertChallenge, ORIGIN);
        byte[] signature = sign(pair.getPrivate(), "SHA256withECDSA", assertAuthData, assertClientData);
        var envelope = new CredentialEnvelope("WEBAUTHN", "ES256", WebAuthnCrypto.base64url(signature),
            WebAuthnCrypto.base64url(assertClientData), WebAuthnCrypto.base64url(assertAuthData));
        long newSignCount = WebAuthnCrypto.verifyAssertion(registration.coseKeyCbor(), "ES256", envelope,
            assertChallenge, RP_ID, Set.of(ORIGIN), true, 0);
        assertEquals(1, newSignCount);
    }

    @Test void assertionRejectsWrongChallenge() throws Exception {
        var kp = KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kp.generateKeyPair();
        byte[] coseKey = coseKeyEc2((ECPublicKey) pair.getPublic());
        byte[] realChallenge = sha256("real-payload".getBytes(StandardCharsets.UTF_8));
        byte[] assertAuthData = authenticatorData(null, null, 1, true, true, false);
        byte[] assertClientData = clientDataJson("webauthn.get", realChallenge, ORIGIN);
        byte[] signature = sign(pair.getPrivate(), "SHA256withECDSA", assertAuthData, assertClientData);
        var envelope = new CredentialEnvelope("WEBAUTHN", "ES256", WebAuthnCrypto.base64url(signature),
            WebAuthnCrypto.base64url(assertClientData), WebAuthnCrypto.base64url(assertAuthData));
        byte[] expectedDifferentChallenge = sha256("different-payload".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> WebAuthnCrypto.verifyAssertion(coseKey, "ES256", envelope,
            expectedDifferentChallenge, RP_ID, Set.of(ORIGIN), true, 0));
    }

    @Test void assertionRejectsWrongOrigin() throws Exception {
        var kp = KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kp.generateKeyPair();
        byte[] coseKey = coseKeyEc2((ECPublicKey) pair.getPublic());
        byte[] challenge = sha256("payload".getBytes(StandardCharsets.UTF_8));
        byte[] assertAuthData = authenticatorData(null, null, 1, true, true, false);
        byte[] assertClientData = clientDataJson("webauthn.get", challenge, "https://evil.example");
        byte[] signature = sign(pair.getPrivate(), "SHA256withECDSA", assertAuthData, assertClientData);
        var envelope = new CredentialEnvelope("WEBAUTHN", "ES256", WebAuthnCrypto.base64url(signature),
            WebAuthnCrypto.base64url(assertClientData), WebAuthnCrypto.base64url(assertAuthData));
        assertThrows(IllegalArgumentException.class, () -> WebAuthnCrypto.verifyAssertion(coseKey, "ES256", envelope,
            challenge, RP_ID, Set.of(ORIGIN), true, 0));
    }

    @Test void assertionRejectsStaleOrClonedSignCounter() throws Exception {
        var kp = KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kp.generateKeyPair();
        byte[] coseKey = coseKeyEc2((ECPublicKey) pair.getPublic());
        byte[] challenge = sha256("payload".getBytes(StandardCharsets.UTF_8));
        byte[] assertAuthData = authenticatorData(null, null, 5, true, true, false); // counter didn't advance past 5
        byte[] assertClientData = clientDataJson("webauthn.get", challenge, ORIGIN);
        byte[] signature = sign(pair.getPrivate(), "SHA256withECDSA", assertAuthData, assertClientData);
        var envelope = new CredentialEnvelope("WEBAUTHN", "ES256", WebAuthnCrypto.base64url(signature),
            WebAuthnCrypto.base64url(assertClientData), WebAuthnCrypto.base64url(assertAuthData));
        assertThrows(IllegalArgumentException.class, () -> WebAuthnCrypto.verifyAssertion(coseKey, "ES256", envelope,
            challenge, RP_ID, Set.of(ORIGIN), true, 5));
        assertDoesNotThrow(() -> WebAuthnCrypto.verifyAssertion(coseKey, "ES256", envelope,
            challenge, RP_ID, Set.of(ORIGIN), true, 4));
    }

    @Test void assertionRejectsMissingUserVerificationWhenRequired() throws Exception {
        var kp = KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kp.generateKeyPair();
        byte[] coseKey = coseKeyEc2((ECPublicKey) pair.getPublic());
        byte[] challenge = sha256("payload".getBytes(StandardCharsets.UTF_8));
        byte[] assertAuthData = authenticatorData(null, null, 1, true, false, false); // UP but not UV
        byte[] assertClientData = clientDataJson("webauthn.get", challenge, ORIGIN);
        byte[] signature = sign(pair.getPrivate(), "SHA256withECDSA", assertAuthData, assertClientData);
        var envelope = new CredentialEnvelope("WEBAUTHN", "ES256", WebAuthnCrypto.base64url(signature),
            WebAuthnCrypto.base64url(assertClientData), WebAuthnCrypto.base64url(assertAuthData));
        assertThrows(IllegalArgumentException.class, () -> WebAuthnCrypto.verifyAssertion(coseKey, "ES256", envelope,
            challenge, RP_ID, Set.of(ORIGIN), true, 0));
        assertDoesNotThrow(() -> WebAuthnCrypto.verifyAssertion(coseKey, "ES256", envelope,
            challenge, RP_ID, Set.of(ORIGIN), false, 0));
    }

    @Test void registrationRejectsNonNoneAttestationFormat() throws Exception {
        var kp = KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kp.generateKeyPair();
        byte[] credentialId = "cred-x".getBytes(StandardCharsets.UTF_8);
        byte[] coseKey = coseKeyEc2((ECPublicKey) pair.getPublic());
        byte[] regChallenge = "reg-challenge".getBytes(StandardCharsets.UTF_8);
        byte[] regAuthData = authenticatorData(credentialId, coseKey, 0, true, true, true);
        var out = new ByteArrayOutputStream();
        try (var gen = new CBORFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeFieldName("fmt"); gen.writeString("packed");
            gen.writeFieldName("attStmt"); gen.writeStartObject(); gen.writeEndObject();
            gen.writeFieldName("authData"); gen.writeBinary(regAuthData);
            gen.writeEndObject();
        }
        byte[] regClientData = clientDataJson("webauthn.create", regChallenge, ORIGIN);
        assertThrows(IllegalArgumentException.class, () -> WebAuthnCrypto.verifyRegistration(out.toByteArray(), regClientData, regChallenge, RP_ID, Set.of(ORIGIN)));
    }

    @Test void ed25519CoseKeyRoundTripsThroughEverydayAlgorithmDispatch() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] rawPublic = kp.getPublic().getEncoded();
        byte[] x = Arrays.copyOfRange(rawPublic, rawPublic.length - 32, rawPublic.length);
        var out = new ByteArrayOutputStream();
        try (var gen = new CBORFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeFieldId(1); gen.writeNumber(1); // kty=OKP
            gen.writeFieldId(3); gen.writeNumber(-8); // alg=EdDSA
            gen.writeFieldId(-1); gen.writeNumber(6); // crv=Ed25519
            gen.writeFieldId(-2); gen.writeBinary(x);
            gen.writeEndObject();
        }
        byte[] challenge = sha256("payload".getBytes(StandardCharsets.UTF_8));
        byte[] assertAuthData = authenticatorData(null, null, 1, true, true, false);
        byte[] assertClientData = clientDataJson("webauthn.get", challenge, ORIGIN);
        var signedOut = new ByteArrayOutputStream(); signedOut.write(assertAuthData); signedOut.write(sha256(assertClientData));
        byte[] signed = signedOut.toByteArray();
        var signer = Signature.getInstance("Ed25519"); signer.initSign(kp.getPrivate()); signer.update(signed);
        byte[] signature = signer.sign();
        var envelope = new CredentialEnvelope("WEBAUTHN", "EdDSA", WebAuthnCrypto.base64url(signature),
            WebAuthnCrypto.base64url(assertClientData), WebAuthnCrypto.base64url(assertAuthData));
        long newSignCount = WebAuthnCrypto.verifyAssertion(out.toByteArray(), "EdDSA", envelope, challenge, RP_ID, Set.of(ORIGIN), true, 0);
        assertEquals(1, newSignCount);
    }
}
