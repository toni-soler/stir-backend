package org.stir.reference;

import java.sql.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import es.idynamicsax.idax.tenant.TenantContext;
import es.idynamicsax.idax.security.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ReferencePostgresTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc; ReferenceService service;
    UUID tenant=UUID.randomUUID(), userId=UUID.randomUUID(); CurrentUser user;
    @BeforeAll static void migrate() throws Exception {
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) { s.execute("create role idax_app; create role idax_admin"); }
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("set local role idax_app"); jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        TenantContext.set(new TenantContext(tenant,null,userId,"test",TenantContext.DbRole.IDAX_APP));
        user=mock(CurrentUser.class);when(user.getUserId()).thenReturn(userId);service=new ReferenceService(jdbc);
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,UUID.randomUUID(),UUID.randomUUID());
    }
    @AfterEach void close() throws Exception {connection.rollback();connection.close();TenantContext.clear();}
    UUID definition() {return (UUID)service.create(user,new ReferenceController.DefinitionRequest("Bread","500g loaf",Map.of("weight","500g"),BigDecimal.ONE,"loaf")).get("id");}
    ReferenceController.ProposalRequest proposal(String amount) {return new ReferenceController.ProposalRequest("CONVENTION",new BigDecimal(amount),new BigDecimal(amount),"Deliberate convention, not statistics","Community meeting",90);}
    @Test void versionsAreImmutableAndReconstructionPreservesCanonicalBytes() {
        UUID id=definition(); var p=service.propose(user,id,proposal("10")); service.publish(user,(UUID)p.get("id"),"Decision one");
        var first=service.current(id); var evidence=service.snapshot(id);
        var p2=service.propose(user,id,proposal("20"));service.publish(user,(UUID)p2.get("id"),"Decision two");
        assertEquals(2,service.current(id).get("version"));assertEquals(first,service.history(id).get(1));
        assertEquals(evidence,new ReferenceService(jdbc).snapshot(id));
        assertEquals("INSUFFICIENT_DATA",evidence.get("status"));assertNull(evidence.get("median"));
        assertThrows(Exception.class,()->jdbc.update("update stir.community_reference set version=99 where id=?",first.get("id")));
    }
    @Test void definitionAndAllReferenceTablesHaveForcedRlsEvenForAdmin() {
        UUID id=definition(); service.propose(user,id,proposal("10"));service.snapshot(id);
        UUID other=UUID.randomUUID();
        jdbc.execute("set local role idax_admin");jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,other.toString());
        for(String table:List.of("reference_definition","reference_policy","reference_proposal","community_reference","reference_observation","reference_snapshot","reference_context_snapshot")) {
            assertEquals(0,jdbc.queryForObject("select count(*) from stir."+table,Integer.class));
            assertTrue(jdbc.queryForObject("select relforcerowsecurity and relrowsecurity from pg_class where oid=('stir.'||?)::regclass",Boolean.class,table));
        }
        assertThrows(Exception.class,()->service.definition(id));
    }
    @Test void realObservationsAreReproducibleAndPublicResponsesOmitPrivateEvidence() {
        UUID id=definition();var d=service.definition(id);
        for(int i=0;i<5;i++)service.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.valueOf(10+i),BigDecimal.ONE,"loaf",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        var s=service.snapshot(id);assertEquals("SUFFICIENT_DATA",s.get("status"));assertEquals("12.00",s.get("median"));
        assertFalse(s.containsKey("included"));assertFalse(s.containsKey("exclusions"));assertFalse(s.containsKey("participant_a"));
        String canonical=jdbc.queryForObject("select canonical_json from stir.reference_snapshot where tenant_id=? and id=?",String.class,tenant,UUID.fromString((String)s.get("id")));
        assertEquals(s.get("digestSha256"),ReferenceService.digest(canonical));assertEquals(canonical,ReferenceService.canonical(ReferenceService.parse(canonical)));
        assertEquals(s,new ReferenceService(jdbc).snapshot(id));
        var p=service.propose(user,id,new ReferenceController.ProposalRequest("VALUE",new BigDecimal("100"),new BigDecimal("100"),"Community chooses a value different from the median","Assembly",10));
        service.publish(user,(UUID)p.get("id"),"Independent normative decision");assertEquals(new BigDecimal("100.00"),service.current(id).get("lower_value"));
    }
    @Test void finalIntegrityFindingChangesEligibilityWithoutRewritingRawAgreement() {
        UUID id=definition(); var d=service.definition(id); UUID extreme=null;
        for(int i=0;i<6;i++) {
            UUID source=UUID.randomUUID();
            service.record(id,"AGREEMENT",source,UUID.randomUUID(),UUID.randomUUID(),
                BigDecimal.valueOf(i==5?1000:10+i),BigDecimal.ONE,"loaf",(String)d.get("unit_ref"),true,
                Instant.now().minus(Duration.ofDays(2)));
            if(i==5) extreme=jdbc.queryForObject("select id from stir.reference_observation where tenant_id=? and source_id=?",UUID.class,tenant,source);
        }
        UUID caseId=UUID.randomUUID();Instant yesterday=Instant.now().minus(Duration.ofDays(1));
        jdbc.update("insert into stir.market_integrity_case values (?,?,?,?,?,?,?,?,?)",caseId,tenant,id,extreme,
            "RELATED_PARTICIPANT_CLUSTER","Reviewed","{\"refs\":[\"private-1\"]}",userId,Timestamp.from(yesterday));
        jdbc.update("insert into stir.market_integrity_case_event values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,caseId,
            "FINAL","Reviewed",UUID.randomUUID(),Timestamp.from(yesterday),1);
        var result=service.snapshot(id);
        assertEquals(6,jdbc.queryForObject("select count(*) from stir.reference_observation where tenant_id=? and definition_id=?",Integer.class,tenant,id));
        assertEquals(5,result.get("observationCount"));
        assertEquals("12.00",result.get("median"));
        assertFalse(result.toString().contains("private-1"));
        String manifest=jdbc.queryForObject("select evidence_json from stir.reference_snapshot where tenant_id=? and id=?",String.class,
            tenant,UUID.fromString((String)result.get("id")));
        assertTrue(manifest.contains("FINAL_INTEGRITY_FINDING:RELATED_PARTICIPANT_CLUSTER"));
    }
    @Test void anomalyMustBeReviewedAndCanBeDismissedWithoutExcludingTheAgreement() {
        UUID id=definition();var d=service.definition(id);UUID source=UUID.randomUUID();
        service.record(id,"AGREEMENT",source,UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,
            BigDecimal.ONE,"loaf",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        UUID observation=jdbc.queryForObject("select id from stir.reference_observation where tenant_id=? and source_id=?",UUID.class,tenant,source);
        var integrity=new MarketIntegrityService(jdbc);
        var signal=integrity.signal(user,new MarketIntegrityService.SignalRequest(observation,"OUTLIER_PENDING_REVIEW",
            "A high value needs review",List.of("private-case-1")));
        UUID caseId=(UUID)signal.get("id");
        assertEquals("SIGNAL",signal.get("status"));
        assertThrows(Exception.class,()->integrity.decide(user,caseId,new MarketIntegrityService.DecisionRequest("FINAL","Premature")));
        integrity.decide(user,caseId,new MarketIntegrityService.DecisionRequest("UNDER_REVIEW","Examining context"));
        assertThrows(Exception.class,()->integrity.decide(user,caseId,new MarketIntegrityService.DecisionRequest("FINAL","Self approval")));
        CurrentUser reviewer=mock(CurrentUser.class);when(reviewer.getUserId()).thenReturn(UUID.randomUUID());
        assertEquals("DISMISSED",integrity.decide(reviewer,caseId,new MarketIntegrityService.DecisionRequest("DISMISSED","No finding")).get("status"));
        assertEquals(3,integrity.history(caseId).size());
        assertEquals(1,jdbc.queryForObject("select count(*) from stir.reference_observation where tenant_id=? and id=?",Integer.class,tenant,observation));
    }
    @Test void noSampleCannotBePublishedAsStatisticallySupportedValue() {
        UUID id=definition();assertThrows(Exception.class,()->service.propose(user,id,new ReferenceController.ProposalRequest("VALUE",BigDecimal.TEN,BigDecimal.TEN,"Unsupported","Statistics",10)));
    }
    @Test void dailyCutPreventsLiveDifferencing() {
        UUID id=definition();var before=service.snapshot(id);var d=service.definition(id);
        service.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)d.get("unit_ref"),true,Instant.now());
        assertEquals(before,service.snapshot(id));
        assertEquals(1,jdbc.queryForObject("select count(*) from stir.reference_observation where tenant_id=?",Integer.class,tenant));
    }
    @Test void frozenContextIsPartyOnlyAndSurvivesANewPublication() {
        UUID definition=definition(),listing=UUID.randomUUID(),negotiation=UUID.randomUUID(),offer=UUID.randomUUID(),agreement=UUID.randomUUID(),other=UUID.randomUUID();
        var proposal=service.propose(user,definition,proposal("10"));service.publish(user,(UUID)proposal.get("id"),"First decision");
        jdbc.update("insert into stir.listing(id,tenant_id,owner_id,direction,title,description,category,resource_kind,status,created_at,updated_at) values (?,?,?,'OFFER','Bread','500g','home','physical','ACTIVE',now(),now())",listing,tenant,userId);
        jdbc.update("insert into stir.negotiation(id,tenant_id,listing_id,initiator_id,owner_id,status,created_at,updated_at) values (?,?,?,?,?,'ACCEPTED',now(),now())",negotiation,tenant,listing,other,userId);
        jdbc.update("insert into stir.offer(id,tenant_id,negotiation_id,listing_id,sequence_number,author_id,message,status,created_at) values (?,?,?,?,1,?,'Accepted','ACCEPTED',now())",offer,tenant,negotiation,listing,other);
        jdbc.update("insert into stir.agreement(id,tenant_id,negotiation_id,listing_id,offer_id,initiator_id,owner_id,created_at) values (?,?,?,?,?,?,?,now())",agreement,tenant,negotiation,listing,offer,other,userId);
        service.freezeContext(definition,agreement,"a".repeat(64),true);
        var context=service.agreementContext(user,agreement);String json=(String)context.get("canonicalJson");
        assertEquals(context.get("digestSha256"),ReferenceService.digest(json));
        assertEquals(json,ReferenceService.canonical(ReferenceService.parse(json)));
        assertEquals(false,ReferenceService.parse(json).get("contractual"));
        var next=service.propose(user,definition,proposal("50"));service.publish(user,(UUID)next.get("id"),"Second decision");
        assertEquals(context,new ReferenceService(jdbc).agreementContext(user,agreement));
        var stranger=mock(CurrentUser.class);when(stranger.getUserId()).thenReturn(UUID.randomUUID());
        assertThrows(Exception.class,()->service.agreementContext(stranger,agreement));
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());
        assertEquals(0,jdbc.queryForObject("select count(*) from stir.reference_context_snapshot",Integer.class));
    }
    @Test void immutableTriggerAlsoProtectsAgainstAnOwnerUpdate() throws Exception {
        UUID id=definition();jdbc.execute("reset role");
        var savepoint=connection.setSavepoint();
        assertThrows(Exception.class,()->jdbc.update("update stir.reference_definition set name='rewritten' where id=?",id));
        connection.rollback(savepoint);
        assertEquals("Bread",service.definition(id).get("name"));
    }
}
