package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.*;
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

@Testcontainers
class SevenKeysPostgresTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    static final String PREFIX="302a300506032b6570032100";
    Connection connection; JdbcTemplate jdbc; SevenKeysService service; CurrentUser user;
    UUID tenant=UUID.randomUUID(), community=UUID.randomUUID(), authority=UUID.randomUUID();
    KeyPair[] keys=new KeyPair[7]; KeyPair guardian; UUID[] credentials=new UUID[7],controllers=new UUID[7];
    UUID guardianCredential=UUID.randomUUID();
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
        service=new SevenKeysService(jdbc);
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,community,UUID.randomUUID());
        for(int i=0;i<7;i++) { keys[i]=key();credentials[i]=UUID.randomUUID();controllers[i]=UUID.randomUUID(); }
        guardian=key();
    }
    @AfterEach void close() throws Exception { connection.rollback();connection.close();TenantContext.clear(); }
    static KeyPair key() throws Exception {return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
    static String publicKey(KeyPair pair) {
        byte[] encoded=pair.getPublic().getEncoded();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded,encoded.length-32,encoded.length));
    }
    static String sign(KeyPair pair,byte[] bytes) throws Exception {
        Signature s=Signature.getInstance("Ed25519");s.initSign(pair.getPrivate());s.update(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }
    void bootstrap() throws Exception {
        var seats=new ArrayList<SevenKeysService.SeatInput>(); var pub=new ArrayList<Map<String,Object>>();
        for(int i=0;i<7;i++) pub.add(Map.of("ordinal",i+1,"controllerId",controllers[i].toString(),
            "credentialId",credentials[i].toString(),"publicKey",publicKey(keys[i])));
        var payload=new LinkedHashMap<String,Object>();
        payload.put("format","STIR-SEVEN-KEYS-BOOTSTRAP-1");payload.put("tenantId",tenant.toString());
        payload.put("communityId",community.toString());payload.put("authorityId",authority.toString());
        payload.put("seats",pub);payload.put("guardianCredentialId",guardianCredential.toString());
        payload.put("guardianPublicKey",publicKey(guardian));
        payload.put("constitutionDigest",ReferenceService.digest(ReferenceService.canonical(SevenKeysService.initialConstitution())));
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.BOOTSTRAP_DOMAIN,payload);
        for(int i=0;i<7;i++) seats.add(new SevenKeysService.SeatInput(i+1,controllers[i],credentials[i],publicKey(keys[i]),sign(keys[i],message)));
        var result=service.bootstrap(user,new SevenKeysService.Bootstrap(authority,community,seats,guardianCredential,
            publicKey(guardian),sign(guardian,message)));
        assertEquals(7,result.get("threshold"));
    }
    UUID proposal(String field,Object value) {
        var after=new LinkedHashMap<>(SevenKeysService.initialConstitution()); after.put(field,value);
        UUID id=UUID.randomUUID();
        service.propose(user,community,new SevenKeysService.ProposalInput(id,"AMEND_CONSTITUTION",after,List.of(field),
            "Explicit constitutional decision",List.of("assembly-record-1")));
        return id;
    }
    void signProposal(UUID id,int seat) throws Exception {
        var p=service.proposal(id);
        @SuppressWarnings("unchecked") var payload=ReferenceService.parse((String)p.get("payloadJson"));
        service.sign(id,new SevenKeysService.SignatureInput(seat,credentials[seat-1],
            sign(keys[seat-1],SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload))));
    }
    SevenKeysService.ExecutionInput noExecution() {return new SevenKeysService.ExecutionInput(null,null);}
    @Test void constitutionNeedsSevenAndRetainsFailedProposalAndSignatures() throws Exception {
        bootstrap();UUID p=proposal("independenceChecksRequired",false);
        for(int i=1;i<=6;i++) signProposal(p,i);
        assertEquals("PARTIALLY_SIGNED",service.proposal(p).get("state"));
        assertThrows(Exception.class,()->service.activate(p,noExecution()));
        assertEquals(1,service.view(community).get("constitutionVersion"));
        assertEquals(6,service.proposal(p).get("signatureCount"));
        signProposal(p,7); assertEquals("ACTIVATED",service.activate(p,noExecution()).get("state"));
        assertEquals(2,service.view(community).get("constitutionVersion"));
        assertEquals(false,((Map<?,?>)service.view(community).get("constitution")).get("independenceChecksRequired"));
        assertEquals(true,service.audit(community).get("valid"));
        assertTrue(((Number)service.audit(community).get("eventCount")).intValue()>=10);
        assertEquals(service.audit(community),new SevenKeysService(jdbc).audit(community));
        assertThrows(Exception.class,()->service.activate(p,noExecution()));
        assertThrows(Exception.class,()->jdbc.update("delete from stir.market_governance_event where tenant_id=?",tenant));
    }
    @Test void signaturesAreSeatBoundAndCannotReplayOrChangeProposal() throws Exception {
        bootstrap(); UUID p=proposal("independenceChecksRequired",false);
        var payload=ReferenceService.parse((String)service.proposal(p).get("payloadJson"));
        String signature=sign(keys[0],SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload));
        service.sign(p,new SevenKeysService.SignatureInput(1,credentials[0],signature));
        assertThrows(Exception.class,()->service.sign(p,new SevenKeysService.SignatureInput(1,credentials[0],signature)));
        UUID another=proposal("concentrationChecksRequired",false);
        assertThrows(Exception.class,()->service.sign(another,new SevenKeysService.SignatureInput(1,credentials[0],signature)));
        assertThrows(Exception.class,()->service.sign(p,new SevenKeysService.SignatureInput(2,credentials[1],signature)));
        assertThrows(Exception.class,()->jdbc.update("update stir.constitutional_proposal set after_digest=? where id=?","0".repeat(64),p));
        assertThrows(Exception.class,()->service.sign(p,new SevenKeysService.SignatureInput(7,guardianCredential,
            sign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload)))));
    }
    @Test void guardianSuspendsOnlyOneAndConstitutionFreezesUntilSixOfSixRecovery() throws Exception {
        bootstrap(); var before=service.view(community);
        long sequence=((Number)before.get("nextSequence")).longValue();java.time.Instant declaredAt=java.time.Instant.now();
        var suspension=Map.<String,Object>of("format","STIR-KEY-SUSPENSION-1","tenantId",tenant.toString(),
            "communityId",community.toString(),"authorityId",authority.toString(),"seat",4,
            "credentialId",credentials[3].toString(),"reasonCode","KEY_COMPROMISED",
            "evidenceRefs",List.of("incident-1"),"sequence",sequence,"declaredAt",declaredAt.toString());
        service.suspend(community,new SevenKeysService.SuspensionInput(4,credentials[3],sequence,declaredAt,"KEY_COMPROMISED",
            List.of("incident-1"),sign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,suspension))));
        assertThrows(Exception.class,()->service.suspend(community,new SevenKeysService.SuspensionInput(5,credentials[4],sequence,declaredAt,
            "KEY_COMPROMISED",List.of(),"bad")));
        UUID change=proposal("independenceChecksRequired",false);
        for(int i=1;i<=7;i++) if(i!=4) signProposal(change,i);
        assertThrows(Exception.class,()->service.sign(change,new SevenKeysService.SignatureInput(4,credentials[3],"bad")));
        assertThrows(Exception.class,()->service.activate(change,noExecution()));
        KeyPair replacement=key(); UUID newCredential=UUID.randomUUID();
        var after=Map.<String,Object>of("affectedSeat",4,"oldCredentialId",credentials[3].toString(),
            "newCredentialId",newCredential.toString(),"newPublicKey",publicKey(replacement),
            "controllerId",controllers[3].toString(),"continuityEvidenceRefs",List.of("continuity-1"),"reason","Key replacement");
        UUID recovery=UUID.randomUUID();
        service.propose(user,community,new SevenKeysService.ProposalInput(recovery,"ROTATE_CREDENTIAL",after,
            List.of("credentialId"),"Same controller recovery",List.of("incident-1")));
        for(int i=1;i<=5;i++) if(i!=4) signProposal(recovery,i);
        var recoveryPayload=ReferenceService.parse((String)service.proposal(recovery).get("payloadJson"));
        var execute=new SevenKeysService.ExecutionInput(
            sign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,recoveryPayload)),
            sign(replacement,SevenKeysCrypto.message(SevenKeysCrypto.POSSESSION_DOMAIN,recoveryPayload)));
        assertThrows(Exception.class,()->service.activate(recovery,execute));
        signProposal(recovery,6);signProposal(recovery,7);
        assertEquals("ACTIVATED",service.activate(recovery,execute).get("state"));
        assertEquals(newCredential,((List<Map<String,Object>>)service.view(community).get("seats")).get(3).get("credential_id"));
        assertTrue(service.credentialHistory(community).stream().anyMatch(x->"REVOKED".equals(x.get("status"))));
        assertThrows(Exception.class,()->service.sign(change,new SevenKeysService.SignatureInput(4,credentials[3],"bad")));
    }
    @Test void fiveSeatsCanRemoveGuardianWithoutGuardianSignature() throws Exception {
        bootstrap();UUID id=UUID.randomUUID();
        service.propose(user,community,new SevenKeysService.ProposalInput(id,"REMOVE_GUARDIAN",
            Map.of("guardianCredentialId",guardianCredential.toString(),"status","REMOVED"),
            List.of("guardianStatus"),"Compromised guardian",List.of("case-1")));
        for(int i=1;i<=5;i++) signProposal(id,i);
        assertEquals("ACTIVATED",service.activate(id,noExecution()).get("state"));
        assertEquals("REMOVED",service.view(community).get("guardianStatus"));
        long sequence=((Number)service.view(community).get("nextSequence")).longValue();
        assertThrows(Exception.class,()->service.suspend(community,new SevenKeysService.SuspensionInput(1,credentials[0],sequence,java.time.Instant.now(),
            "KEY_LOST",List.of(),"anything")));
    }
    @Test void ordinaryPolicyCannotCrossNewConstitutionalFloor() throws Exception {
        bootstrap();UUID p=proposal("minimumParticipantFloor",10);
        for(int i=1;i<=7;i++) signProposal(p,i);
        service.activate(p,noExecution());
        var refs=new ReferenceService(jdbc);
        UUID definition=(UUID)refs.create(user,new ReferenceController.DefinitionRequest("Service","one hour",Map.of(),
            java.math.BigDecimal.ONE,"hour")).get("id");
        assertEquals(10,refs.currentPolicy(definition).get("minimum_participants"));
        assertThrows(Exception.class,()->refs.policy(user,definition,new ReferenceController.PolicyRequest(90,5,6,
            new java.math.BigDecimal("0.40"),30,"Try lowering bound")));
        assertThrows(Exception.class,()->refs.policy(user,definition,new ReferenceController.PolicyRequest(90,8,10,
            new java.math.BigDecimal("0.40"),30,"Attempt protected bypass",false,null,null,null)));
        assertEquals(1,refs.currentPolicy(definition).get("version"));
    }
    @Test void controllerReplacementNeverTrustsClientClaimOfFinality() throws Exception {
        bootstrap();
        long sequence=((Number)service.view(community).get("nextSequence")).longValue();
        var at=java.time.Instant.now();
        var suspension=Map.<String,Object>of("format","STIR-KEY-SUSPENSION-1","tenantId",tenant.toString(),
            "communityId",community.toString(),"authorityId",authority.toString(),"seat",4,
            "credentialId",credentials[3].toString(),"reasonCode","CONTROLLER_REVIEW",
            "evidenceRefs",List.of("case-1"),"sequence",sequence,"declaredAt",at.toString());
        service.suspend(community,new SevenKeysService.SuspensionInput(4,credentials[3],sequence,at,"CONTROLLER_REVIEW",
            List.of("case-1"),sign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,suspension))));
        KeyPair replacement=key();UUID id=UUID.randomUUID();
        var after=Map.<String,Object>of("affectedSeat",4,"oldCredentialId",credentials[3].toString(),
            "newCredentialId",UUID.randomUUID().toString(),"newPublicKey",publicKey(replacement),
            "controllerId",UUID.randomUUID().toString(),"finalResolutionId",UUID.randomUUID().toString(),
            "finalResolutionDigest","0".repeat(64),"reason","Supposed final result");
        service.propose(user,community,new SevenKeysService.ProposalInput(id,"REPLACE_CONTROLLER",after,
            List.of("controllerId","credentialId"),"Claimed final resolution",List.of("case-1")));
        for(int seat:List.of(1,2,3,5,6,7)) signProposal(id,seat);
        var payload=ReferenceService.parse((String)service.proposal(id).get("payloadJson"));
        var execution=new SevenKeysService.ExecutionInput(
            sign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,payload)),
            sign(replacement,SevenKeysCrypto.message(SevenKeysCrypto.POSSESSION_DOMAIN,payload)));
        assertTrue(assertThrows(Exception.class,()->service.activate(id,execution)).getMessage()
            .contains("FINAL_RESOLUTION_VERIFICATION_UNAVAILABLE"));
        assertEquals("EMERGENCY_SUSPENDED",((List<Map<String,Object>>)service.view(community).get("seats")).get(3).get("status"));
        assertEquals("PARTIALLY_SIGNED",service.proposal(id).get("state"));
    }
    @Test void fiveOfSixRemainingSeatsCanRemoveGuardianDuringFreeze() throws Exception {
        bootstrap();long sequence=((Number)service.view(community).get("nextSequence")).longValue();
        var at=java.time.Instant.now();
        var payload=Map.<String,Object>of("format","STIR-KEY-SUSPENSION-1","tenantId",tenant.toString(),
            "communityId",community.toString(),"authorityId",authority.toString(),"seat",4,
            "credentialId",credentials[3].toString(),"reasonCode","KEY_LOST",
            "evidenceRefs",List.of("incident"),"sequence",sequence,"declaredAt",at.toString());
        service.suspend(community,new SevenKeysService.SuspensionInput(4,credentials[3],sequence,at,"KEY_LOST",
            List.of("incident"),sign(guardian,SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,payload))));
        UUID id=UUID.randomUUID();
        service.propose(user,community,new SevenKeysService.ProposalInput(id,"REMOVE_GUARDIAN",
            Map.of("guardianCredentialId",guardianCredential.toString(),"status","REMOVED"),
            List.of("guardianStatus"),"Guardian abuse",List.of("incident")));
        for(int seat:List.of(1,2,3,5,6))signProposal(id,seat);
        assertEquals("ACTIVATED",service.activate(id,noExecution()).get("state"));
        assertEquals("REMOVED",service.view(community).get("guardianStatus"));
    }
    @Test void forcedRlsAndHistoryPrivacyAcrossTenants() throws Exception {
        bootstrap();UUID other=UUID.randomUUID();
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,other.toString());
        for(String table:List.of("constitutional_authority","constitutional_seat","constitutional_proposal","constitutional_signature",
            "market_governance_event","market_constitution","constitutional_credential_history",
            "market_integrity_case","market_integrity_case_event")) {
            assertEquals(0,jdbc.queryForObject("select count(*) from stir."+table,Integer.class));
            assertTrue(jdbc.queryForObject("select relforcerowsecurity from pg_class where oid=('stir.'||?)::regclass",Boolean.class,table));
        }
    }
    @Test void databaseRejectsAuthorityWithFewerThanSevenSeatsAtCommit() {
        jdbc.update("insert into stir.constitutional_authority values (?,?,?,?,?,?,'ACTIVE',1,now())",
            authority,tenant,community,7,guardianCredential,publicKey(guardian));
        assertThrows(SQLException.class,()->connection.commit());
    }
    @Test void adminDatabaseRoleCannotRotateOrUnsuspendConstitutionalSeat() throws Exception {
        bootstrap();
        jdbc.execute("set local role idax_admin");
        assertThrows(Exception.class,()->jdbc.update("update stir.constitutional_seat set status='ACTIVE' where tenant_id=? and authority_id=? and ordinal=1",
            tenant,authority));
    }
}
