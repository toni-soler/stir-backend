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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** WebAuthn-specific Seven Keys scenarios (WEBAUTHN_HARDWARE_CUSTODY.md), on top of the existing
 * Ed25519 mechanics SevenKeysPostgresTest already proves: mixed-credential bootstrap, a WebAuthn
 * seat signing and its envelope surviving activation's re-verification, challenge/replay binding
 * to the exact payload (cross-proposal/community/tenant), same-controller Ed25519->WebAuthn
 * rotation, a suspended/revoked WebAuthn credential unable to sign, and native sign-counter replay
 * detection enforced through the real service (not just the crypto unit layer WebAuthnCryptoTest
 * already covers in isolation). */
@Testcontainers
class WebAuthnSevenKeysPostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    static final String RP_ID="localhost", ORIGIN="http://localhost:8089";
    Connection connection; JdbcTemplate jdbc; SevenKeysService service; WebAuthnCredentialService webauthn; CurrentUser user;
    UUID tenant=UUID.randomUUID(), community=UUID.randomUUID(), authority=UUID.randomUUID();
    KeyPair[] edKeys=new KeyPair[6]; KeyPair guardian; UUID[] credentials=new UUID[7],controllers=new UUID[7];
    UUID guardianCredential=UUID.randomUUID();
    KeyPair webauthnSeat7Key; byte[] webauthnSeat7CredentialId;

    @BeforeAll static void migrate() throws Exception {
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement()) {
            s.execute("create role idax_app; create role idax_admin");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        connection.setAutoCommit(false); jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("set local role idax_app");
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        TenantContext.set(new TenantContext(tenant,null,UUID.randomUUID(),"test",TenantContext.DbRole.IDAX_APP));
        user=mock(CurrentUser.class); when(user.getUserId()).thenReturn(UUID.randomUUID());
        webauthn=new WebAuthnCredentialService(jdbc);
        webauthn.rpId=RP_ID; webauthn.rpName="STIR Test"; webauthn.allowedOriginsCsv=ORIGIN;
        service=new SevenKeysService(jdbc,webauthn);
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,community,UUID.randomUUID());
        for(int i=0;i<6;i++) { edKeys[i]=edKey();credentials[i]=UUID.randomUUID();controllers[i]=UUID.randomUUID(); }
        credentials[6]=UUID.randomUUID(); controllers[6]=UUID.randomUUID();
        guardian=edKey();
        webauthnSeat7Key=ecKey(); webauthnSeat7CredentialId="seat7-cred".getBytes(StandardCharsets.UTF_8);
    }
    @AfterEach void close() throws Exception { connection.rollback();connection.close();TenantContext.clear(); }

    static KeyPair edKey() throws Exception {return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
    static KeyPair ecKey() throws Exception { var kp=KeyPairGenerator.getInstance("EC"); kp.initialize(new ECGenParameterSpec("secp256r1")); return kp.generateKeyPair(); }
    static String edPublicKey(KeyPair pair) {
        byte[] encoded=pair.getPublic().getEncoded();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded,encoded.length-32,encoded.length));
    }
    static String edSign(KeyPair pair,byte[] bytes) throws Exception {
        Signature s=Signature.getInstance("Ed25519");s.initSign(pair.getPrivate());s.update(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }
    static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    static byte[] toFixedBytes(BigInteger n,int length) {
        byte[] raw=n.toByteArray(); byte[] fixed=new byte[length];
        int start=Math.max(0,raw.length-length), destOffset=Math.max(0,length-raw.length);
        System.arraycopy(raw,start,fixed,destOffset,raw.length-start);
        return fixed;
    }
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
    static byte[] authenticatorData(long signCount,boolean uv) throws Exception {
        var out=new ByteArrayOutputStream();
        out.write(sha256(RP_ID.getBytes(StandardCharsets.UTF_8)));
        out.write(0x01|(uv?0x04:0));
        out.write((int)(signCount>>24)); out.write((int)(signCount>>16)); out.write((int)(signCount>>8)); out.write((int)signCount);
        return out.toByteArray();
    }
    static String clientDataJsonB64(byte[] challenge) {
        String json="{\"type\":\"webauthn.get\",\"challenge\":\""+
            Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)+"\",\"origin\":\""+ORIGIN+"\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    /** Builds a real WebAuthn assertion envelope proving possession, for domain-separated `message`,
     * with the given (already-registered) authenticator's private key and reported sign count. */
    static CredentialEnvelope webauthnEnvelope(PrivateKey key,long signCount,byte[] message) throws Exception {
        byte[] challenge=sha256(message);
        byte[] authData=authenticatorData(signCount,true);
        String clientDataJsonB64=clientDataJsonB64(challenge);
        byte[] clientDataJsonBytes=Base64.getUrlDecoder().decode(clientDataJsonB64);
        var signed=new ByteArrayOutputStream(); signed.write(authData); signed.write(sha256(clientDataJsonBytes));
        var signer=Signature.getInstance("SHA256withECDSA"); signer.initSign(key); signer.update(signed.toByteArray());
        String signatureB64=Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        return new CredentialEnvelope("WEBAUTHN","ES256",signatureB64,clientDataJsonB64,
            Base64.getUrlEncoder().withoutPadding().encodeToString(authData));
    }
    void registerWebauthnCredential(UUID credentialId,byte[] webauthnCredId,long initialSignCount) {
        jdbc.update("insert into stir.constitutional_webauthn_credential values (?,?,?,?,?,?,?,?,now())",
            UUID.randomUUID(),tenant,authority,credentialId,Base64.getUrlEncoder().withoutPadding().encodeToString(webauthnCredId),
            RP_ID,initialSignCount,true);
    }

    SevenKeysService.SeatInput seatInput(int ordinal,byte[] message) throws Exception {
        if(ordinal==7) {
            byte[] coseKey=coseKeyEs256((ECPublicKey)webauthnSeat7Key.getPublic());
            String publicKeyB64=Base64.getUrlEncoder().withoutPadding().encodeToString(coseKey);
            registerWebauthnCredential(credentials[6],webauthnSeat7CredentialId,0);
            var envelope=webauthnEnvelope(webauthnSeat7Key.getPrivate(),0,message);
            return new SevenKeysService.SeatInput(7,controllers[6],credentials[6],publicKeyB64,null,"WEBAUTHN","ES256",envelope);
        }
        return new SevenKeysService.SeatInput(ordinal,controllers[ordinal-1],credentials[ordinal-1],edPublicKey(edKeys[ordinal-1]),
            edSign(edKeys[ordinal-1],message),null,null,null);
    }
    void bootstrap() throws Exception {
        var pub=new ArrayList<Map<String,Object>>();
        for(int i=1;i<=6;i++) pub.add(Map.of("ordinal",i,"controllerId",controllers[i-1].toString(),
            "credentialId",credentials[i-1].toString(),"publicKey",edPublicKey(edKeys[i-1])));
        byte[] webauthnPublicKeyBytes=coseKeyEs256((ECPublicKey)webauthnSeat7Key.getPublic());
        String webauthnPublicKeyB64=Base64.getUrlEncoder().withoutPadding().encodeToString(webauthnPublicKeyBytes);
        pub.add(Map.of("ordinal",7,"controllerId",controllers[6].toString(),"credentialId",credentials[6].toString(),"publicKey",webauthnPublicKeyB64));
        var payload=new LinkedHashMap<String,Object>();
        payload.put("format","STIR-SEVEN-KEYS-BOOTSTRAP-1");payload.put("tenantId",tenant.toString());
        payload.put("communityId",community.toString());payload.put("authorityId",authority.toString());
        payload.put("seats",pub);payload.put("guardianCredentialId",guardianCredential.toString());
        payload.put("guardianPublicKey",edPublicKey(guardian));
        payload.put("constitutionDigest",ReferenceService.digest(ReferenceService.canonical(SevenKeysService.initialConstitution())));
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.BOOTSTRAP_DOMAIN,payload);
        var seats=new ArrayList<SevenKeysService.SeatInput>();
        for(int i=1;i<=6;i++) seats.add(seatInput(i,message));
        seats.add(seatInput(7,message));
        var result=service.bootstrap(user,new SevenKeysService.Bootstrap(authority,community,seats,guardianCredential,
            edPublicKey(guardian),edSign(guardian,message)));
        assertEquals(7,result.get("threshold"));
        assertEquals("WEBAUTHN",((List<Map<String,Object>>)result.get("seats")).get(6).get("credential_type"));
    }
    UUID proposal(String field,Object value) {
        // Built from the CURRENT live constitution, not the fixed initial one - this test chains
        // multiple different-field amendments after one bootstrap, unlike SevenKeysPostgresTest's
        // own proposal() helper which only ever proposes a single field change per bootstrap.
        var current=(Map<String,Object>)service.view(community).get("constitution");
        var after=new LinkedHashMap<>(current); after.put(field,value);
        UUID id=UUID.randomUUID();
        service.propose(user,community,new SevenKeysService.ProposalInput(id,"AMEND_CONSTITUTION",after,List.of(field),
            "Explicit constitutional decision",List.of("assembly-record-1")));
        return id;
    }
    void signEd(UUID id,int seat) throws Exception {
        var p=service.proposal(id);
        var payload=ReferenceService.parse((String)p.get("payloadJson"));
        service.sign(id,new SevenKeysService.SignatureInput(seat,credentials[seat-1],
            edSign(edKeys[seat-1],SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload))));
    }
    void signWebauthn(UUID id,long signCount) throws Exception {
        var p=service.proposal(id);
        var payload=ReferenceService.parse((String)p.get("payloadJson"));
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload);
        var envelope=webauthnEnvelope(webauthnSeat7Key.getPrivate(),signCount,message);
        service.sign(id,new SevenKeysService.SignatureInput(7,credentials[6],null,envelope));
    }

    @Test void mixedCredentialBootstrapAndWebauthnSeatSignsAndActivatesConstitutionalAmendment() throws Exception {
        bootstrap();
        UUID p=proposal("independenceChecksRequired",false);
        for(int i=1;i<=6;i++) signEd(p,i);
        signWebauthn(p,1); // authenticator's own counter after its bootstrap-time use (signCount=0)
        assertEquals("ACTIVATED",service.activate(p,new SevenKeysService.ExecutionInput(null,null)).get("state"));
        assertEquals(false,((Map<?,?>)service.view(community).get("constitution")).get("independenceChecksRequired"));
        var history=service.credentialHistory(community);
        assertTrue(history.stream().anyMatch(h->"WEBAUTHN".equals(h.get("credential_type"))));
    }

    @Test void reusedOrStaleSignCounterIsRejectedByTheRealService() throws Exception {
        bootstrap(); // stored sign_count starts at 0 (the envelope used during bootstrap reported 0)
        UUID first=proposal("independenceChecksRequired",false);
        for(int i=1;i<=6;i++) signEd(first,i);
        signWebauthn(first,1); // the counter's first real advance - stored becomes 1
        assertEquals("ACTIVATED",service.activate(first,new SevenKeysService.ExecutionInput(null,null)).get("state"));
        UUID p=proposal("concentrationChecksRequired",false);
        for(int i=1;i<=6;i++) signEd(p,i);
        // The authenticator has now genuinely reported a real (nonzero) counter once - presenting
        // that same value (or anything not strictly greater) again must be rejected.
        assertThrows(Exception.class,()->signWebauthn(p,1));
        signWebauthn(p,2); // a real, advancing counter succeeds
    }

    @Test void capturedAssertionCannotBeReplayedForADifferentProposal() throws Exception {
        bootstrap();
        UUID p1=proposal("independenceChecksRequired",false);
        UUID p2=proposal("concentrationChecksRequired",false);
        var payload1=ReferenceService.parse((String)service.proposal(p1).get("payloadJson"));
        var envelopeForP1=webauthnEnvelope(webauthnSeat7Key.getPrivate(),1,SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload1));
        // A real assertion computed for p1's exact payload must fail verification against p2 - the
        // challenge (SHA-256 of domain+payload) differs, so clientDataJSON.challenge cannot match.
        assertThrows(Exception.class,()->service.sign(p2,new SevenKeysService.SignatureInput(7,credentials[6],null,envelopeForP1)));
    }

    @Test void capturedAssertionCannotBeReplayedAcrossCommunitiesOrTenants() throws Exception {
        bootstrap();
        UUID p=proposal("independenceChecksRequired",false);
        var payload=ReferenceService.parse((String)service.proposal(p).get("payloadJson"));
        // The domain-separated message already bakes in tenantId/communityId/authorityId - a
        // different community's or tenant's otherwise-identical action produces a different
        // message, hence a different challenge, hence this exact envelope cannot verify there.
        var forgedPayload=new LinkedHashMap<>(payload);
        forgedPayload.put("communityId",UUID.randomUUID().toString());
        byte[] differentMessage=SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,forgedPayload);
        var envelope=webauthnEnvelope(webauthnSeat7Key.getPrivate(),1,differentMessage);
        assertThrows(Exception.class,()->service.sign(p,new SevenKeysService.SignatureInput(7,credentials[6],null,envelope)));
    }

    @Test void suspendedWebauthnSeatCannotSign() throws Exception {
        bootstrap();
        long sequence=((Number)service.view(community).get("nextSequence")).longValue();
        var declaredAt=java.time.Instant.now();
        var suspension=Map.<String,Object>of("format","STIR-KEY-SUSPENSION-1","tenantId",tenant.toString(),
            "communityId",community.toString(),"authorityId",authority.toString(),"seat",7,
            "credentialId",credentials[6].toString(),"reasonCode","KEY_LOST",
            "evidenceRefs",List.of("incident-1"),"sequence",sequence,"declaredAt",declaredAt.toString());
        service.suspend(community,new SevenKeysService.SuspensionInput(7,credentials[6],sequence,declaredAt,"KEY_LOST",
            List.of("incident-1"),edSign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,suspension))));
        UUID p=proposal("independenceChecksRequired",false);
        assertThrows(Exception.class,()->signWebauthn(p,1));
    }

    @Test void sameControllerRotationFromEd25519ToWebauthnPreservesControllerAndEndsFreeze() throws Exception {
        bootstrap();
        long sequence=((Number)service.view(community).get("nextSequence")).longValue();
        var declaredAt=java.time.Instant.now();
        var suspension=Map.<String,Object>of("format","STIR-KEY-SUSPENSION-1","tenantId",tenant.toString(),
            "communityId",community.toString(),"authorityId",authority.toString(),"seat",1,
            "credentialId",credentials[0].toString(),"reasonCode","KEY_COMPROMISED",
            "evidenceRefs",List.of("incident-1"),"sequence",sequence,"declaredAt",declaredAt.toString());
        service.suspend(community,new SevenKeysService.SuspensionInput(1,credentials[0],sequence,declaredAt,"KEY_COMPROMISED",
            List.of("incident-1"),edSign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,suspension))));
        KeyPair newWebauthnKey=ecKey(); UUID newCredential=UUID.randomUUID();
        byte[] newCoseKey=coseKeyEs256((ECPublicKey)newWebauthnKey.getPublic());
        String newPublicKeyB64=Base64.getUrlEncoder().withoutPadding().encodeToString(newCoseKey);
        registerWebauthnCredential(newCredential,"seat1-new-cred".getBytes(StandardCharsets.UTF_8),0);
        var after=Map.<String,Object>of("affectedSeat",1,"oldCredentialId",credentials[0].toString(),
            "newCredentialId",newCredential.toString(),"newPublicKey",newPublicKeyB64,
            "controllerId",controllers[0].toString(),"continuityEvidenceRefs",List.of("continuity-1"),"reason","Move to hardware key",
            "credentialType","WEBAUTHN","algorithm","ES256");
        UUID recovery=UUID.randomUUID();
        service.propose(user,community,new SevenKeysService.ProposalInput(recovery,"ROTATE_CREDENTIAL",after,
            List.of("credentialId"),"Same controller recovery to WebAuthn",List.of("incident-1")));
        for(int i=2;i<=6;i++) signEd(recovery,i);
        signWebauthn(recovery,1); // seat 7 (unaffected) co-signs
        var recoveryPayload=ReferenceService.parse((String)service.proposal(recovery).get("payloadJson"));
        var guardianEnvelope=CredentialEnvelope.ed25519(edSign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,recoveryPayload)));
        var newKeyEnvelope=webauthnEnvelope(newWebauthnKey.getPrivate(),0,SevenKeysCrypto.message(SevenKeysCrypto.POSSESSION_DOMAIN,recoveryPayload));
        var execute=new SevenKeysService.ExecutionInput(null,null,guardianEnvelope,newKeyEnvelope);
        assertEquals("ACTIVATED",service.activate(recovery,execute).get("state"));
        var seats=(List<Map<String,Object>>)service.view(community).get("seats");
        assertEquals(newCredential,seats.get(0).get("credential_id"));
        assertEquals("WEBAUTHN",seats.get(0).get("credential_type"));
        assertEquals(controllers[0],seats.get(0).get("controller_id")); // same controller, only the credential changed
        var history=service.credentialHistory(community);
        assertTrue(history.stream().anyMatch(h->"REVOKED".equals(h.get("status"))&&"SOFTWARE_ED25519".equals(h.get("credential_type"))));
        assertTrue(history.stream().anyMatch(h->"ACTIVE".equals(h.get("status"))&&"WEBAUTHN".equals(h.get("credential_type"))&&h.get("credential_id").equals(newCredential)));
        // The freeze is over: an ordinary amendment now needs all seven active seats again, seat 1
        // signing with its brand-new WebAuthn credential.
        UUID p=proposal("independenceChecksRequired",false);
        for(int i=2;i<=6;i++) signEd(p,i);
        signWebauthn(p,2); // seat 7's authenticator already advanced to 1 co-signing the rotation above
        var p1payload=ReferenceService.parse((String)service.proposal(p).get("payloadJson"));
        var seat1Envelope=webauthnEnvelope(newWebauthnKey.getPrivate(),1,SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,p1payload));
        service.sign(p,new SevenKeysService.SignatureInput(1,newCredential,null,seat1Envelope));
        assertEquals("ACTIVATED",service.activate(p,new SevenKeysService.ExecutionInput(null,null)).get("state"));
    }

    @Test void platformSuperAdminGrantNeverBypassesWebauthnVerification() throws Exception {
        // Being a platform-level role never substitutes for a real signature - forging any part of
        // a WebAuthn envelope (wrong signature bytes here) is rejected exactly like a forged
        // Ed25519 signature already is, regardless of caller identity/permissions above this layer.
        bootstrap();
        UUID p=proposal("independenceChecksRequired",false);
        var payload=ReferenceService.parse((String)service.proposal(p).get("payloadJson"));
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload);
        var real=webauthnEnvelope(webauthnSeat7Key.getPrivate(),1,message);
        var forged=new CredentialEnvelope("WEBAUTHN","ES256","AAAA"+real.signature().substring(4),real.clientDataJson(),real.authenticatorData());
        assertThrows(Exception.class,()->service.sign(p,new SevenKeysService.SignatureInput(7,credentials[6],null,forged)));
    }
}
