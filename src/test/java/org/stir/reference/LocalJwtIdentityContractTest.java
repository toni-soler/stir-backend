package org.stir.reference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.idynamicsax.idax.security.LocalTokenValidator;
import es.idynamicsax.idax.security.TokenValidationResult;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.junit.jupiter.api.Assertions.*;

/** The verifier's identity boundary uses idax-core's own LocalTokenValidator, configured with the same
 * recipe as idax-core's ResourceServerJwtDecoderConfig (RS256, issuer "idax-local", default
 * validators). Every case below must be rejected or accepted by that exact contract. */
class LocalJwtIdentityContractTest {
    static KeyPair trusted, attacker;
    static LocalTokenValidator validator;
    static final UUID USER = UUID.randomUUID(), TENANT = UUID.randomUUID();

    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        trusted = gen.generateKeyPair();
        attacker = gen.generateKeyPair();
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        ((NimbusJwtDecoder) decoder).setJwtValidator(JwtValidators.createDefaultWithIssuer("idax-local"));
        validator = new LocalTokenValidator(decoder);
    }

    private static String sign(KeyPair key, Date expires, String issuer, String subject, UUID userId, UUID tenantId) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuer).issueTime(new Date()).expirationTime(expires)
            .claim("userId", userId.toString()).claim("tenantId", tenantId.toString())
            .claim("superuser", false).claim("roles", List.of("user")).claim("type", "access");
        if (subject != null) claims.subject(subject);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static String valid() throws Exception {
        return sign(trusted, new Date(System.currentTimeMillis() + 600_000), "idax-local", "alice", USER, TENANT);
    }

    private static TokenValidationResult validate(String token) {
        try {
            return validator.validateAndExtract(token);
        } catch (RuntimeException rejected) {
            return null;
        }
    }

    private static void assertRejected(String token) {
        TokenValidationResult result = validate(token);
        assertTrue(result == null || !result.isValid(), "token must be rejected");
    }

    @Test void validTokenYieldsTheVerifiedIdentityAndTenant() throws Exception {
        TokenValidationResult result = validator.validateAndExtract(valid());
        assertTrue(result.isValid());
        assertEquals(USER, result.getCurrentUser().getUserId());
        assertEquals(TENANT, result.getCurrentUser().getTenantId());
        assertFalse(result.getCurrentUser().isSuperuser());
    }

    @Test void unsignedTokenIsRejected() throws Exception {
        String unsigned = new com.nimbusds.jwt.PlainJWT(new JWTClaimsSet.Builder().issuer("idax-local")
            .subject("alice").expirationTime(new Date(System.currentTimeMillis() + 600_000))
            .claim("userId", USER.toString()).claim("tenantId", TENANT.toString()).build()).serialize();
        assertRejected(unsigned);
    }

    @Test void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        assertRejected(sign(attacker, new Date(System.currentTimeMillis() + 600_000), "idax-local", "alice", USER, TENANT));
    }

    @Test void expiredTokenIsRejected() throws Exception {
        assertRejected(sign(trusted, new Date(System.currentTimeMillis() - 600_000), "idax-local", "alice", USER, TENANT));
    }

    @Test void malformedTokenIsRejected() {
        assertRejected("not-a-jwt");
        assertRejected("eyJhbGciOiJSUzI1NiJ9.e30.");
    }

    @Test void wrongIssuerIsRejected() throws Exception {
        assertRejected(sign(trusted, new Date(System.currentTimeMillis() + 600_000), "some-other-issuer", "alice", USER, TENANT));
    }

    @Test void tokenWithoutSubjectIsRejected() throws Exception {
        assertRejected(sign(trusted, new Date(System.currentTimeMillis() + 600_000), "idax-local", null, USER, TENANT));
    }

    /** idax-core's validator does not require a tenant claim; it yields an identity without a tenant.
     * The verifier cannot act on such an identity: TenantContext is then absent and every governed
     * write fails at ReferenceService.tenant(). The test pins that exact contract. */
    @Test void tokenWithoutTenantClaimCarriesNoTenantAndCannotAct() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("idax-local").subject("alice")
            .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 600_000))
            .claim("userId", USER.toString()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(trusted.getPrivate()));
        TokenValidationResult result = validate(jwt.serialize());
        assertTrue(result == null || !result.isValid() || result.getCurrentUser().getTenantId() == null);
    }
}
