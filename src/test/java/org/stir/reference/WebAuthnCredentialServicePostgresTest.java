package org.stir.reference;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The registration-ceremony challenge lifecycle itself (WEBAUTHN_HARDWARE_CUSTODY.md): single-use,
 * expiring, bound to actor/authority/context - separate from WebAuthnSevenKeysPostgresTest, which
 * covers signing/verification against an already-registered credential. */
@Testcontainers
class WebAuthnCredentialServicePostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    static final String RP_ID="localhost", ORIGIN="http://localhost:8089";
    Connection connection; JdbcTemplate jdbc; WebAuthnCredentialService service; CurrentUser user;
    UUID tenant=UUID.randomUUID(), authority=UUID.randomUUID();

    @BeforeAll static void migrate() throws Exception {
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement()) {
            s.execute("create role idax_app; create role idax_admin");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }
    @BeforeEach void setup() {
        connection=uncheckedConnect();
        try { connection.setAutoCommit(false); } catch (SQLException e) { throw new RuntimeException(e); }
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("set local role idax_app");
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        TenantContext.set(new TenantContext(tenant,null,UUID.randomUUID(),"test",TenantContext.DbRole.IDAX_APP));
        user=mock(CurrentUser.class); when(user.getUserId()).thenReturn(UUID.randomUUID());
        service=new WebAuthnCredentialService(jdbc);
        service.rpId=RP_ID; service.rpName="STIR Test"; service.allowedOriginsCsv=ORIGIN;
    }
    static Connection uncheckedConnect() {
        try { return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()); }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    @AfterEach void close() throws Exception { connection.rollback();connection.close();TenantContext.clear(); }

    static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    static byte[] toFixedBytes(BigInteger n,int length) {
        byte[] raw=n.toByteArray(); byte[] fixed=new byte[length];
        int start=Math.max(0,raw.length-length), destOffset=Math.max(0,length-raw.length);
        System.arraycopy(raw,start,fixed,destOffset,raw.length-start);
        return fixed;
    }
    static KeyPair ecKey() throws Exception { var kp=KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1")); return kp.generateKeyPair(); }
    static byte[] coseKeyEs256(ECPublicKey key) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var gen=new CBORFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeFieldId(1); gen.writeNumber(2); gen.writeFieldId(3); gen.writeNumber(-7); gen.writeFieldId(-1); gen.writeNumber(1);
            gen.writeFieldId(-2); gen.writeBinary(toFixedBytes(key.getW().getAffineX(),32));
            gen.writeFieldId(-3); gen.writeBinary(toFixedBytes(key.getW().getAffineY(),32));
            gen.writeEndObject();
        }
        return out.toByteArray();
    }
    record Registration(byte[] attestationObject, byte[] clientDataJson, byte[] credentialId) {}
    static Registration registrationResponse(KeyPair key,byte[] credentialId,byte[] challenge,boolean uv) throws Exception {
        byte[] coseKey=coseKeyEs256((ECPublicKey)key.getPublic());
        var authData=new ByteArrayOutputStream();
        authData.write(sha256(RP_ID.getBytes(StandardCharsets.UTF_8)));
        authData.write(0x01|0x40|(uv?0x04:0)); // UP + AT (+UV)
        authData.write(0);authData.write(0);authData.write(0);authData.write(0); // signCount=0
        authData.write(new byte[16]); // aaguid
        authData.write(credentialId.length>>8); authData.write(credentialId.length&0xFF);
        authData.write(credentialId); authData.write(coseKey);
        var attestation=new ByteArrayOutputStream();
        try(var gen=new CBORFactory().createGenerator(attestation)) {
            gen.writeStartObject();
            gen.writeFieldName("fmt"); gen.writeString("none");
            gen.writeFieldName("attStmt"); gen.writeStartObject(); gen.writeEndObject();
            gen.writeFieldName("authData"); gen.writeBinary(authData.toByteArray());
            gen.writeEndObject();
        }
        String json="{\"type\":\"webauthn.create\",\"challenge\":\""+
            Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)+"\",\"origin\":\""+ORIGIN+"\"}";
        return new Registration(attestation.toByteArray(), json.getBytes(StandardCharsets.UTF_8), credentialId);
    }
    WebAuthnCredentialService.RegistrationResponse finishRequest(byte[] credId,Registration reg,UUID credentialId,boolean uv) {
        return new WebAuthnCredentialService.RegistrationResponse(credentialId,
            WebAuthnCrypto.base64url(reg.attestationObject()), WebAuthnCrypto.base64url(reg.clientDataJson()),
            WebAuthnCrypto.base64url(credId), uv);
    }

    @Test void beginThenFinishRegistrationStoresACredential() throws Exception {
        var options=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null));
        assertEquals(RP_ID,options.rpId());
        var key=ecKey(); byte[] credId="cred-a".getBytes(StandardCharsets.UTF_8);
        var reg=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options.challenge(),"c"),true);
        var result=service.finishRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),
            finishRequest(credId,reg,UUID.randomUUID(),true));
        assertEquals("ES256",result.algorithm());
        assertNotNull(result.publicKeyBase64url());
    }

    @Test void registrationChallengeIsSingleUse() throws Exception {
        var options=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null));
        var key=ecKey(); byte[] credId="cred-b".getBytes(StandardCharsets.UTF_8);
        var reg=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options.challenge(),"c"),true);
        UUID credentialId=UUID.randomUUID();
        service.finishRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg,credentialId,true));
        // Replaying the exact same finish request a second time must fail - the challenge was
        // already consumed, regardless of whether the crypto itself would still verify.
        assertThrows(ResponseStatusException.class,()->service.finishRegistration(user,
            new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg,UUID.randomUUID(),true)));
    }

    @Test void finishWithoutAMatchingChallengeIsRejected() throws Exception {
        var key=ecKey(); byte[] credId="cred-c".getBytes(StandardCharsets.UTF_8);
        byte[] fakeChallenge=new byte[32]; new SecureRandom().nextBytes(fakeChallenge);
        var reg=registrationResponse(key,credId,fakeChallenge,true);
        assertThrows(ResponseStatusException.class,()->service.finishRegistration(user,
            new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg,UUID.randomUUID(),true)));
    }

    @Test void finishWithWrongContextIsRejectedEvenWithAValidChallenge() throws Exception {
        var options=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null));
        var key=ecKey(); byte[] credId="cred-d".getBytes(StandardCharsets.UTF_8);
        var reg=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options.challenge(),"c"),true);
        // The challenge exists, but under context "seat-1" - looking it up under "seat-2" (a
        // different seat's registration ceremony) must not find it.
        assertThrows(ResponseStatusException.class,()->service.finishRegistration(user,
            new WebAuthnCredentialService.RegistrationRequest(authority,"seat-2",null),finishRequest(credId,reg,UUID.randomUUID(),true)));
    }

    @Test void finishWithWrongActorIsRejectedEvenWithAValidChallenge() throws Exception {
        var options=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null));
        var key=ecKey(); byte[] credId="cred-e".getBytes(StandardCharsets.UTF_8);
        var reg=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options.challenge(),"c"),true);
        var otherUser=mock(CurrentUser.class); when(otherUser.getUserId()).thenReturn(UUID.randomUUID());
        assertThrows(ResponseStatusException.class,()->service.finishRegistration(otherUser,
            new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg,UUID.randomUUID(),true)));
    }

    @Test void expiredRegistrationChallengeIsRejected() throws Exception {
        // expires_at has no UPDATE grant at all (only consumed_at does - see V16) - an already-
        // expired challenge is instead inserted directly, bypassing beginRegistration() entirely.
        byte[] challengeBytes=new byte[32]; new SecureRandom().nextBytes(challengeBytes);
        String challenge=WebAuthnCrypto.base64url(challengeBytes);
        jdbc.update("insert into stir.constitutional_webauthn_challenge values (?,?,?,?,'REGISTRATION','seat-1',?,now()-interval '1 minute',null,now()-interval '10 minutes')",
            UUID.randomUUID(),tenant,authority,user.getUserId(),challenge);
        var key=ecKey(); byte[] credId="cred-f".getBytes(StandardCharsets.UTF_8);
        var reg=registrationResponse(key,credId,challengeBytes,true);
        assertThrows(ResponseStatusException.class,()->service.finishRegistration(user,
            new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg,UUID.randomUUID(),true)));
    }

    @Test void duplicateCredentialCannotBeRegisteredTwice() throws Exception {
        var options1=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null));
        var key=ecKey(); byte[] credId="cred-g".getBytes(StandardCharsets.UTF_8);
        var reg1=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options1.challenge(),"c"),true);
        UUID sharedInternalId=UUID.randomUUID();
        service.finishRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg1,sharedInternalId,true));
        var options2=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-2",null));
        var reg2=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options2.challenge(),"c"),true); // SAME physical credentialId
        assertThrows(ResponseStatusException.class,()->service.finishRegistration(user,
            new WebAuthnCredentialService.RegistrationRequest(authority,"seat-2",null),finishRequest(credId,reg2,UUID.randomUUID(),true)));
    }

    @Test void tenantIsolationHoldsForChallengesAndCredentials() throws Exception {
        var options=service.beginRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null));
        var key=ecKey(); byte[] credId="cred-h".getBytes(StandardCharsets.UTF_8);
        var reg=registrationResponse(key,credId,WebAuthnCrypto.decodeBase64url(options.challenge(),"c"),true);
        service.finishRegistration(user,new WebAuthnCredentialService.RegistrationRequest(authority,"seat-1",null),finishRequest(credId,reg,UUID.randomUUID(),true));
        UUID other=UUID.randomUUID();
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,other.toString());
        assertEquals(0,jdbc.queryForObject("select count(*) from stir.constitutional_webauthn_credential",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from stir.constitutional_webauthn_challenge",Integer.class));
    }
}
