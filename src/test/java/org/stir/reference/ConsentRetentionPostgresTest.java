package org.stir.reference;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import es.idynamicsax.idax.tenant.TenantContext;
import es.idynamicsax.idax.security.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.OstrisClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Consent/Retention: distinguishes having a datum, having permission to use it, still being
 * allowed to retain it, and still being eligible as current evidence (CONSENT_RETENTION.md).
 * Consent withdrawal and retention expiry never rewrite Agreement/observation history; the one
 * real deletion-lifecycle action (anonymization) never touches amount/quantity/observed_at, and
 * is blocked while a market-integrity case still needs the identifiers. */
@Testcontainers
class ConsentRetentionPostgresTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc;
    ReferenceService reference; ConsentService consent; RetentionService retention; MarketIntegrityService integrity; OrdinaryGovernanceService governance;
    UUID tenant=UUID.randomUUID(), userId=UUID.randomUUID(), community;
    CurrentUser publisher, superadmin;
    @BeforeAll static void migrate() throws Exception {
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) { s.execute("create role idax_app; create role idax_admin"); }
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("set local role idax_app"); jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        TenantContext.set(new TenantContext(tenant,null,userId,"test",TenantContext.DbRole.IDAX_APP));
        var independence=new ParticipantIndependenceService(jdbc,mock(OstrisClient.class));
        reference=new ReferenceService(jdbc,independence); consent=new ConsentService(jdbc); retention=new RetentionService(jdbc,reference);
        integrity=new MarketIntegrityService(jdbc); governance=new OrdinaryGovernanceService(jdbc,reference,retention);
        publisher=mock(CurrentUser.class); when(publisher.getUserId()).thenReturn(userId);
        superadmin=mock(CurrentUser.class); when(superadmin.getUserId()).thenReturn(UUID.randomUUID()); when(superadmin.isSuperuser()).thenReturn(true);
        community=UUID.randomUUID();
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,community,UUID.randomUUID());
    }
    @AfterEach void close() throws Exception {connection.rollback();connection.close();TenantContext.clear();}
    CurrentUser as(UUID id) { var u=mock(CurrentUser.class); when(u.getUserId()).thenReturn(id); return u; }
    UUID definition() {return (UUID)reference.create(publisher,new ReferenceController.DefinitionRequest("Bread","500g loaf",Map.of(),BigDecimal.ONE,"loaf")).get("id");}
    /** Fills the minimums (5 observations/6 participants) with clean, always-eligible filler
     * observations so a single test observation's own eligibility is what's actually being tested. */
    void filler(UUID id,String unitRef,Instant at) {
        for(int i=0;i<5;i++) reference.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",unitRef,true,at);
    }
    /** Records one AGREEMENT observation plus real per-party consent, exactly as
     * ReferenceAcceptanceAdapter does at Agreement acceptance time. */
    UUID observationWithConsent(UUID definitionId,UUID a,UUID b,boolean aGrants,boolean bGrants,Instant at) {
        var d=reference.definition(definitionId);
        UUID obs=reference.record(definitionId,"AGREEMENT",UUID.randomUUID(),a,b,BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)d.get("unit_ref"),aGrants&&bGrants,at);
        consent.capture(obs,definitionId,a,aGrants,ConsentService.AGREEMENT_PURPOSE); consent.capture(obs,definitionId,b,bGrants,ConsentService.AGREEMENT_PURPOSE);
        return obs;
    }
    UUID consentIdFor(UUID observationId,UUID party) {
        return jdbc.queryForObject("select id from stir.reference_consent where tenant_id=? and observation_id=? and party_user_id=?",UUID.class,tenant,observationId,party);
    }

    @Test void bilateralConsentGrantedContributesToEvidence() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        filler(id,(String)reference.definition(id).get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        var snapshot=reference.snapshot(id);
        assertEquals("SUFFICIENT_DATA",snapshot.get("status"));
        assertEquals(6,snapshot.get("observationCount"));
        assertTrue(reference.evidenceManifest(id).get("included").toString().contains(obs.toString()));
    }
    @Test void oneDeclinedPartyExcludesTheObservationAndCannotBeWithdrawn() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,false,at); // b declines
        var manifest=reference.evidenceManifest(id);
        assertEquals("NO_BILATERAL_CONSENT",((Map<?,?>)manifest.get("exclusions")).get(obs.toString()));
        UUID bConsentId=consentIdFor(obs,b);
        var ex=assertThrows(ResponseStatusException.class,()->consent.withdraw(as(b),bConsentId,"changed my mind"));
        assertTrue(ex.getReason().contains("declined"));
    }
    @Test void withdrawalAfterAcceptanceExcludesFromFutureSnapshotsWithDistinctReason() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        UUID aConsentId=consentIdFor(obs,a);
        var withdrawn=consent.withdraw(as(a),aConsentId,"no longer comfortable sharing this");
        assertEquals("WITHDRAW",withdrawn.get("status"));
        var manifest=reference.evidenceManifest(id);
        assertEquals("CONSENT_WITHDRAWN",((Map<?,?>)manifest.get("exclusions")).get(obs.toString()));
        assertFalse(manifest.get("included").toString().contains(obs.toString()));
    }
    @Test void cachedSnapshotSurvivesALaterWithdrawalUnchanged() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        var before=reference.snapshot(id); // caches today's cutoff with obs INCLUDED
        assertTrue(reference.evidenceManifest(id).get("included").toString().contains(obs.toString()));
        consent.withdraw(as(a),consentIdFor(obs,a),"withdrawing now");
        var after=reference.snapshot(id); // same day -> returns the SAME cached row, unaffected
        assertEquals(before.get("digestSha256"),after.get("digestSha256"));
        assertEquals(before,after);
    }
    @Test void finalIntegrityFindingTakesPriorityOverWithdrawalReason() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        // Same technique as ReferencePostgresTest.finalIntegrityFindingChangesEligibilityWithout
        // RewritingRawAgreement: a FINAL event only changes eligibility once its recorded_at is
        // before today's UTC cutoff, so it is seeded directly rather than via signal()/decide()
        // (which would record it at Instant.now(), too late for today's snapshot to see).
        UUID caseId=UUID.randomUUID(); Instant yesterday=Instant.now().minus(Duration.ofDays(1));
        jdbc.update("insert into stir.market_integrity_case values (?,?,?,?,?,?,?,?,?)",caseId,tenant,id,obs,
            "OUTLIER_PENDING_REVIEW","Reviewed","{\"refs\":[\"ref-1\"]}",UUID.randomUUID(),Timestamp.from(yesterday));
        jdbc.update("insert into stir.market_integrity_case_event values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,caseId,
            "FINAL","Confirmed manipulation",UUID.randomUUID(),Timestamp.from(yesterday),1);
        consent.withdraw(as(a),consentIdFor(obs,a),"trying to hide it");
        var manifest=reference.evidenceManifest(id);
        assertTrue(((String)((Map<?,?>)manifest.get("exclusions")).get(obs.toString())).startsWith("FINAL_INTEGRITY_FINDING"));
    }
    @Test void onlyTheConsentingPartyCanWithdrawTheirOwnConsent() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        UUID aConsentId=consentIdFor(obs,a);
        assertThrows(AccessDeniedException.class,()->consent.withdraw(as(b),aConsentId,"not mine to withdraw"));
        assertThrows(AccessDeniedException.class,()->consent.view(as(b),aConsentId));
    }
    @Test void withdrawIsIdempotentUnderConcurrentReplay() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        UUID aConsentId=consentIdFor(obs,a);
        consent.withdraw(as(a),aConsentId,"first"); consent.withdraw(as(a),aConsentId,"replayed"); consent.withdraw(as(a),aConsentId,"replayed again");
        int events=jdbc.queryForObject("select count(*) from stir.reference_consent_event where tenant_id=? and consent_id=?",Integer.class,tenant,aConsentId);
        assertEquals(2,events); // GRANT (at capture) + exactly one WITHDRAW, never three
    }
    @Test void retentionBeforeWindowElapsedIsRetainedNotEligible() {
        UUID id=definition();
        UUID obs=reference.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)reference.definition(id).get("unit_ref"),true,Instant.now().minus(Duration.ofDays(10)));
        var status=retention.statusFor(obs);
        assertEquals("RETAINED",status.status());
        assertThrows(ResponseStatusException.class,()->retention.anonymize(publisher,obs,"too early"));
    }
    @Test void retentionEligibleAfterWindowElapsedWithNoHoldCanBeAnonymized() {
        UUID id=definition();
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=reference.record(id,"AGREEMENT",UUID.randomUUID(),a,b,BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)reference.definition(id).get("unit_ref"),true,Instant.now().minus(Duration.ofDays(200)));
        assertEquals("ELIGIBLE_FOR_ANONYMIZATION",retention.statusFor(obs).status());
        var due=retention.dueForAnonymization(id);
        assertEquals(1,due.size());
        var anonymized=retention.anonymize(publisher,obs,"retention window elapsed, no case ever opened");
        assertNull(anonymized.get("participant_a")); assertNull(anonymized.get("participant_b"));
        assertNotNull(anonymized.get("anonymized_at"));
        assertEquals(BigDecimal.TEN.setScale(2),anonymized.get("amount")); // amount/history preserved
        assertEquals("ANONYMIZED",retention.statusFor(obs).status());
        assertEquals(1,jdbc.queryForObject("select count(*) from stir.retention_lifecycle_event where tenant_id=? and observation_id=?",Integer.class,tenant,obs));
    }
    @Test void marketIntegrityCaseForcesRetentionHoldEvenPastWindow() {
        UUID id=definition();
        UUID obs=reference.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)reference.definition(id).get("unit_ref"),true,Instant.now().minus(Duration.ofDays(200)));
        integrity.signal(publisher,new MarketIntegrityService.SignalRequest(obs,"OUTLIER_PENDING_REVIEW","still under review",List.of()));
        var status=retention.statusFor(obs);
        assertEquals("MUST_BE_RETAINED",status.status()); assertTrue(status.hold());
        assertEquals("MARKET_INTEGRITY_CASE_LINKED",status.holdReason());
        assertTrue(retention.dueForAnonymization(id).isEmpty());
        var ex=assertThrows(ResponseStatusException.class,()->retention.anonymize(publisher,obs,"trying to erase evidence"));
        assertTrue(ex.getReason().contains("MUST_BE_RETAINED"));
        // Raw participant identifiers are exactly as recorded - a signal never rewrote the Agreement.
        assertNotNull(jdbc.queryForObject("select participant_a from stir.reference_observation where tenant_id=? and id=?",UUID.class,tenant,obs));
    }
    @Test void anonymizeIsNotReversibleAndCannotRepeat() {
        UUID id=definition();
        UUID obs=reference.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)reference.definition(id).get("unit_ref"),true,Instant.now().minus(Duration.ofDays(200)));
        retention.anonymize(publisher,obs,"first anonymization");
        var ex=assertThrows(ResponseStatusException.class,()->retention.anonymize(publisher,obs,"second attempt"));
        assertTrue(ex.getReason().contains("ANONYMIZED"));
    }
    @Test void rawSqlCannotBypassTheAnonymizationOnlyTrigger() throws Exception {
        UUID id=definition();
        UUID obs=reference.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)reference.definition(id).get("unit_ref"),true,Instant.now().minus(Duration.ofDays(200)));
        jdbc.execute("reset role"); // superuser bypass, same probing technique as ReferencePostgresTest
        var savepoint1=connection.setSavepoint();
        assertThrows(Exception.class,()->jdbc.update("update stir.reference_observation set amount=? where tenant_id=? and id=?",BigDecimal.valueOf(999999),tenant,obs));
        connection.rollback(savepoint1);
        var savepoint2=connection.setSavepoint();
        assertThrows(Exception.class,()->jdbc.update("delete from stir.reference_observation where tenant_id=? and id=?",tenant,obs));
        connection.rollback(savepoint2);
        jdbc.execute("set local role idax_app");
        retention.anonymize(publisher,obs,"legit anonymization");
        jdbc.execute("reset role");
        var savepoint3=connection.setSavepoint();
        assertThrows(Exception.class,()->jdbc.update("update stir.reference_observation set participant_a=? where tenant_id=? and id=?",UUID.randomUUID(),tenant,obs));
        connection.rollback(savepoint3);
        jdbc.execute("set local role idax_app");
        assertEquals(BigDecimal.TEN.setScale(2),jdbc.queryForObject("select amount from stir.reference_observation where tenant_id=? and id=?",BigDecimal.class,tenant,obs));
    }
    @Test void retentionPolicyBelowFloorIsRejected() {
        var ex=assertThrows(ResponseStatusException.class,()->retention.setPolicy(publisher,community,new RetentionService.PolicyRequest(10,"too short")));
        assertTrue(ex.getReason().contains("floor"));
    }
    @Test void platformSuperAdminCannotSetRetentionPolicyOrAnonymize() {
        UUID id=definition();
        UUID obs=reference.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,BigDecimal.ONE,"loaf",(String)reference.definition(id).get("unit_ref"),true,Instant.now().minus(Duration.ofDays(200)));
        assertThrows(AccessDeniedException.class,()->retention.setPolicy(superadmin,community,new RetentionService.PolicyRequest(120,"superadmin trying")));
        assertThrows(AccessDeniedException.class,()->retention.anonymize(superadmin,obs,"superadmin trying"));
    }
    @Test void tenantIsolationConsentAndRetentionNeverCrossTenants() throws Exception {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        var d=reference.definition(id); filler(id,(String)d.get("unit_ref"),at);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        UUID obs=observationWithConsent(id,a,b,true,true,at);
        UUID aConsentId=consentIdFor(obs,a);
        retention.setPolicy(publisher,community,new RetentionService.PolicyRequest(120,"tenant A policy"));
        TenantContext.clear();
        UUID otherTenant=UUID.randomUUID();
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,otherTenant.toString());
        TenantContext.set(new TenantContext(otherTenant,null,UUID.randomUUID(),"test",TenantContext.DbRole.IDAX_APP));
        assertTrue(jdbc.queryForList("select 1 from stir.reference_consent where id=?",aConsentId).isEmpty());
        assertTrue(jdbc.queryForList("select 1 from stir.retention_policy where community_id=?",community).isEmpty());
        assertThrows(Exception.class,()->consent.withdraw(as(a),aConsentId,"cross tenant attempt"));
    }
    @Test void ordinaryGovernanceCanChangeRetentionPolicyAndDirectChangeIsBlockedOnceEnabled() throws Exception {
        UUID member=UUID.randomUUID();
        governance.addMember(publisher,community,userId); governance.addMember(publisher,community,member);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,2,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1 initial policy"));
        governance.setEnabled(publisher,community,true);
        var ex=assertThrows(ResponseStatusException.class,()->retention.setPolicy(publisher,community,new RetentionService.PolicyRequest(150,"direct attempt while governance active")));
        assertTrue(ex.getReason().contains("proposal"));
        var proposal=governance.proposeRetentionPolicyChange(publisher,community,new RetentionService.PolicyRequest(150,"raise the retention window"));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); governance.vote(as(member),proposalId,"APPROVE");
        Timestamp opensAt=jdbc.queryForObject("select voting_opens_at from stir.ordinary_proposal where tenant_id=? and id=?",Timestamp.class,tenant,proposalId);
        jdbc.execute("reset role");
        jdbc.update("update stir.ordinary_proposal set voting_closes_at=? where tenant_id=? and id=?",new Timestamp(opensAt.getTime()+1),tenant,proposalId);
        jdbc.execute("set local role idax_app");
        assertEquals("APPROVED",governance.close(proposalId).get("status"));
        var executed=governance.execute(publisher,proposalId);
        assertEquals("EXECUTED",executed.get("status"));
        assertEquals(150,((Number)retention.currentPolicy(community).get("retention_period_days")).intValue());
    }
}
