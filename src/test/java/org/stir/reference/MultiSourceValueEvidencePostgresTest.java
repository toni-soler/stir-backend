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
import org.stir.listing.ListingRevision;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LISTING/WANTED/COMMUNITY_SEED (MULTI_SOURCE_VALUE_EVIDENCE.md). Exercises ListingEvidenceAdapter
 * directly against real Postgres, at the same level ReferenceAcceptanceAdapter's own tests already
 * do - the JPA-level wiring (ListingController -> ListingService -> ListingRevisionService) is
 * proved by the real HTTP/browser E2E instead, since this harness has no JPA/EntityManager
 * bootstrap (only plain JdbcTemplate), matching every other Postgres test in this suite. */
@Testcontainers
class MultiSourceValueEvidencePostgresTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc;
    ReferenceService reference; ConsentService consent; ListingEvidenceAdapter listingEvidence; MarketIntegrityService integrity; OrdinaryGovernanceService governance;
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
        reference=new ReferenceService(jdbc,independence); consent=new ConsentService(jdbc);
        listingEvidence=new ListingEvidenceAdapter(reference,consent);
        integrity=new MarketIntegrityService(jdbc);
        var retention=new RetentionService(jdbc,reference); governance=new OrdinaryGovernanceService(jdbc,reference,retention);
        publisher=mock(CurrentUser.class); when(publisher.getUserId()).thenReturn(userId);
        superadmin=mock(CurrentUser.class); when(superadmin.getUserId()).thenReturn(UUID.randomUUID()); when(superadmin.isSuperuser()).thenReturn(true);
        community=UUID.randomUUID();
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,community,UUID.randomUUID());
    }
    @AfterEach void close() throws Exception {connection.rollback();connection.close();TenantContext.clear();}
    CurrentUser as(UUID id) { var u=mock(CurrentUser.class); when(u.getUserId()).thenReturn(id); return u; }
    UUID definition() {return (UUID)reference.create(publisher,new ReferenceController.DefinitionRequest("Firewood","10kg bundle",Map.of(),BigDecimal.ONE,"kg")).get("id");}
    void enableSources(UUID id,boolean listing,boolean wanted) {
        var current=reference.currentPolicy(id);
        reference.policyDirect(publisher,id,new ReferenceController.PolicyRequest((int)current.get("window_days"),(int)current.get("minimum_observations"),
            (int)current.get("minimum_participants"),(BigDecimal)current.get("maximum_participant_share"),(int)current.get("freshness_days"),
            "enable sources",null,null,null,null,null,listing,wanted));
    }
    /** Hand-builds the revision ListingRevisionService would have frozen - this is the boundary
     * this harness can reach without a JPA bootstrap; ListingEvidenceAdapter itself is real. */
    ListingRevision revision(UUID listingId,UUID owner,String direction,UUID definitionId,BigDecimal amount,BigDecimal quantity,boolean share,Instant at) {
        var r=new ListingRevision(); r.id=UUID.randomUUID(); r.tenantId=tenant; r.listingId=listingId; r.revisionNumber=1;
        r.ownerId=owner; r.direction=direction; r.title="Firewood bundle";
        r.indicativeAmount=amount; r.indicativeQuantity=quantity; r.indicativeUnitLabel="kg";
        r.indicativeUnitRef=(String)reference.definition(definitionId).get("unit_ref");
        r.referenceDefinitionId=definitionId; r.shareReferenceObservation=share; r.canonicalJson="{}"; r.digestSha256="x".repeat(64); r.createdAt=at;
        return r;
    }

    @Test void validListingContributesOnlyWhenPolicyEnabledAndConsentGiven() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        UUID owner=UUID.randomUUID(), listingId=UUID.randomUUID();
        listingEvidence.recorded(revision(listingId,owner,"OFFER",id,new BigDecimal("40"),BigDecimal.ONE,true,at));
        var obs=jdbc.queryForList("select * from stir.reference_observation where tenant_id=? and definition_id=?",tenant,id);
        assertEquals(1,obs.size()); assertEquals("LISTING",obs.getFirst().get("source"));
        assertEquals(listingId,obs.getFirst().get("economic_lineage_id"));
        // Recorded as a datum either way, but only counted once the policy explicitly opts in.
        enableSources(id,true,false);
        var snapshot=reference.snapshot(id);
        @SuppressWarnings("unchecked") var listingEvidenceSummary=(Map<String,Object>)snapshot.get("listingEvidence");
        assertNotNull(listingEvidenceSummary);
        assertEquals("LISTING",listingEvidenceSummary.get("source"));
    }
    @Test void validWantedContributesSeparatelyFromListing() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        enableSources(id,false,true);
        listingEvidence.recorded(revision(UUID.randomUUID(),UUID.randomUUID(),"WANTED",id,new BigDecimal("38"),BigDecimal.ONE,true,at));
        var snapshot=reference.snapshot(id);
        assertNull(snapshot.get("listingEvidence"));
        assertNotNull(snapshot.get("wantedEvidence"));
        @SuppressWarnings("unchecked") var sourceBreakdown=(Map<String,Object>)snapshot.get("sourceBreakdown");
        assertEquals(1,sourceBreakdown.get("WANTED"));
    }
    @Test void editingAListingNeverRewritesAnEarlierObservation() {
        UUID id=definition(); Instant t1=Instant.now().minus(Duration.ofDays(3)), t2=Instant.now().minus(Duration.ofDays(1));
        UUID owner=UUID.randomUUID(), listingId=UUID.randomUUID();
        listingEvidence.recorded(revision(listingId,owner,"OFFER",id,new BigDecimal("40"),BigDecimal.ONE,true,t1));
        UUID firstObsId=(UUID)jdbc.queryForList("select id from stir.reference_observation where tenant_id=? and definition_id=?",tenant,id).getFirst().get("id");
        // A second revision (the edit) is a NEW row and a NEW observation, never an UPDATE of the first.
        var edited=revision(listingId,owner,"OFFER",id,new BigDecimal("55"),BigDecimal.ONE,true,t2); edited.revisionNumber=2;
        listingEvidence.recorded(edited);
        var rows=jdbc.queryForList("select id,amount from stir.reference_observation where tenant_id=? and definition_id=? order by observed_at",tenant,id);
        assertEquals(2,rows.size());
        assertEquals(firstObsId,rows.getFirst().get("id"));
        assertEquals(new BigDecimal("40.00"),rows.getFirst().get("amount"));
        assertEquals(new BigDecimal("55.00"),rows.get(1).get("amount"));
    }
    @Test void listingProposalAgreementChainSharesOneLineageNotThreeVoices() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        UUID listingId=UUID.randomUUID(), a=UUID.randomUUID(), b=UUID.randomUUID();
        listingEvidence.recorded(revision(listingId,a,"OFFER",id,new BigDecimal("40"),BigDecimal.ONE,true,at));
        reference.record(id,"PROPOSAL",UUID.randomUUID(),a,b,new BigDecimal("38"),BigDecimal.ONE,"kg",(String)reference.definition(id).get("unit_ref"),false,at,listingId);
        reference.record(id,"AGREEMENT",UUID.randomUUID(),a,b,new BigDecimal("39"),BigDecimal.ONE,"kg",(String)reference.definition(id).get("unit_ref"),true,at,listingId);
        // The raw provenance fact: LISTING, PROPOSAL and AGREEMENT all trace back to the SAME
        // economic_lineage_id (the originating listing) - one economic process, not three unrelated
        // ones, regardless of how many separate observation rows it produced.
        var lineages=jdbc.queryForList("select distinct economic_lineage_id from stir.reference_observation where tenant_id=? and definition_id=?",tenant,id);
        assertEquals(1,lineages.size()); assertEquals(listingId,lineages.getFirst().get("economic_lineage_id"));
        assertEquals(3,jdbc.queryForObject("select count(*) from stir.reference_observation where tenant_id=? and definition_id=?",Integer.class,tenant,id));
    }
    @Test void multipleAccountsOfTheSameClusterDoNotInflateListingDiversity() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        enableSources(id,true,false);
        UUID clusterA1=UUID.randomUUID(), clusterA2=UUID.randomUUID();
        // Both accounts confirmed related to the same cluster.
        jdbc.update("insert into stir.participant_independence_projection values (?,?,?,?,?,?,?,?)",tenant,clusterA1,community,"RELATED_CONTINUITY","same-cluster",1L,Timestamp.from(Instant.now()),UUID.randomUUID());
        jdbc.update("insert into stir.participant_independence_projection values (?,?,?,?,?,?,?,?)",tenant,clusterA2,community,"RELATED_CONTINUITY","same-cluster",1L,Timestamp.from(Instant.now()),UUID.randomUUID());
        listingEvidence.recorded(revision(UUID.randomUUID(),clusterA1,"OFFER",id,new BigDecimal("40"),BigDecimal.ONE,true,at));
        listingEvidence.recorded(revision(UUID.randomUUID(),clusterA2,"OFFER",id,new BigDecimal("41"),BigDecimal.ONE,true,at));
        // Constitutional floors (>=5 observations, >=6 independent participants) apply to LISTING
        // exactly like AGREEMENT - widening the input surface never lowers the bar. Five more
        // unrelated owners reach that floor without diluting the point: 7 raw accounts collapse to
        // 6 independent units once the confirmed cluster is accounted for.
        for(int i=0;i<5;i++) listingEvidence.recorded(revision(UUID.randomUUID(),UUID.randomUUID(),"OFFER",id,new BigDecimal("42"),BigDecimal.ONE,true,at));
        var snapshot=reference.snapshot(id);
        @SuppressWarnings("unchecked") var listingSummary=(Map<String,Object>)snapshot.get("listingEvidence");
        assertEquals("SUFFICIENT_DATA",listingSummary.get("status"));
        assertEquals(7,listingSummary.get("participantCount")); // raw accounts
        assertEquals(6,listingSummary.get("adjustedIndependentParticipantCount")); // UNKNOWN != INDEPENDENT, but confirmed-related DOES collapse
    }
    @Test void withdrawalRemovesFutureEligibilityWithoutDeletingHistory() {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        UUID owner=UUID.randomUUID();
        var rev=revision(UUID.randomUUID(),owner,"OFFER",id,new BigDecimal("40"),BigDecimal.ONE,true,at);
        listingEvidence.recorded(rev);
        var obsId=(UUID)jdbc.queryForList("select id from stir.reference_observation where tenant_id=? and definition_id=?",tenant,id).getFirst().get("id");
        var consentId=jdbc.queryForObject("select id from stir.reference_consent where tenant_id=? and observation_id=? and purpose=?",UUID.class,tenant,obsId,ConsentService.LISTING_PURPOSE);
        var withdrawn=consent.withdraw(as(owner),consentId,"changed my mind");
        assertEquals("WITHDRAW",withdrawn.get("status"));
        assertNotNull(jdbc.queryForObject("select participant_a from stir.reference_observation where tenant_id=? and id=?",UUID.class,tenant,obsId));
    }
    @Test void seedApprovedThroughOrdinaryGovernance() throws Exception {
        UUID id=definition(); UUID member=UUID.randomUUID();
        governance.addMember(publisher,community,userId); governance.addMember(publisher,community,member);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,2,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        var proposal=governance.proposeCommunitySeed(publisher,id,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal("30"),new BigDecimal("30"),
            "Initial orientation pending real evidence","Founding assembly decision",90));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); governance.vote(as(member),proposalId,"APPROVE");
        expireVotingWindow(proposalId);
        assertEquals("APPROVED",governance.close(proposalId).get("status"));
        var executed=governance.execute(publisher,proposalId);
        assertEquals("EXECUTED",executed.get("status"));
        var seed=reference.currentSeed(id);
        assertNotNull(seed); assertEquals(1,seed.get("version"));
        assertEquals(proposalId,seed.get("proposal_id"));
    }
    @Test void seedRejectedForLackOfQuorumNeverAppliesAndCannotBeExecuted() throws Exception {
        UUID id=definition(); UUID member=UUID.randomUUID(), m2=UUID.randomUUID(), m3=UUID.randomUUID();
        for(UUID m:List.of(userId,member,m2,m3)) governance.addMember(publisher,community,m);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,2,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        var proposal=governance.proposeCommunitySeed(publisher,id,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal("30"),new BigDecimal("30"),"x","x",90));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); // 1 of 4 - quorum needs participants*2>=1*4 -> need >=2
        expireVotingWindow(proposalId);
        assertEquals("EXPIRED",governance.close(proposalId).get("status"));
        assertThrows(ResponseStatusException.class,()->governance.execute(publisher,proposalId));
        assertNull(reference.currentSeed(id));
    }
    @Test void seedQuorumMetButNoMajorityIsRejected() throws Exception {
        UUID id=definition(); UUID member=UUID.randomUUID(), m2=UUID.randomUUID();
        for(UUID m:List.of(userId,member,m2)) governance.addMember(publisher,community,m);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,1,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        var proposal=governance.proposeCommunitySeed(publisher,id,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal("30"),new BigDecimal("30"),"x","x",90));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); governance.vote(as(member),proposalId,"REJECT"); governance.vote(as(m2),proposalId,"REJECT");
        expireVotingWindow(proposalId);
        assertEquals("REJECTED",governance.close(proposalId).get("status"));
        assertNull(reference.currentSeed(id));
    }
    @Test void platformSuperAdminCannotProposeVoteOrExecuteASeed() throws Exception {
        UUID id=definition();
        governance.addMember(publisher,community,userId);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,1,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        assertThrows(AccessDeniedException.class,()->governance.proposeCommunitySeed(superadmin,id,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal("30"),new BigDecimal("30"),"x","x",90)));
        var proposal=governance.proposeCommunitySeed(publisher,id,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal("30"),new BigDecimal("30"),"x","x",90));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        assertThrows(AccessDeniedException.class,()->governance.vote(superadmin,proposalId,"APPROVE"));
        assertThrows(AccessDeniedException.class,()->governance.execute(superadmin,proposalId));
    }
    @Test void aNewSeedIsANewVersionNeverARewriteOfTheOlderOne() throws Exception {
        UUID id=definition();
        governance.addMember(publisher,community,userId);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,1,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        UUID first=approveAndExecuteSeed(id,"30");
        UUID second=approveAndExecuteSeed(id,"45");
        var history=reference.seedHistory(id);
        assertEquals(2,history.size());
        assertEquals(2,history.getFirst().get("version")); // latest first
        assertEquals(new BigDecimal("30.00"),jdbc.queryForObject("select lower_value from stir.community_seed where tenant_id=? and proposal_id=?",BigDecimal.class,tenant,first));
        assertEquals(new BigDecimal("45.00"),jdbc.queryForObject("select lower_value from stir.community_seed where tenant_id=? and proposal_id=?",BigDecimal.class,tenant,second));
    }
    UUID approveAndExecuteSeed(UUID definitionId,String value) throws Exception {
        var proposal=governance.proposeCommunitySeed(publisher,definitionId,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal(value),new BigDecimal(value),"x","x",90));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE");
        expireVotingWindow(proposalId);
        governance.close(proposalId); governance.execute(publisher,proposalId);
        return proposalId;
    }
    @Test void doubleExecutionOfAnApprovedSeedIsBlocked() throws Exception {
        UUID id=definition();
        governance.addMember(publisher,community,userId);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,1,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        var proposal=governance.proposeCommunitySeed(publisher,id,new OrdinaryGovernanceService.SeedRequest("VALUE",new BigDecimal("30"),new BigDecimal("30"),"x","x",90));
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); expireVotingWindow(proposalId); governance.close(proposalId);
        governance.execute(publisher,proposalId);
        assertThrows(ResponseStatusException.class,()->governance.execute(publisher,proposalId));
        assertEquals(1,reference.seedHistory(id).size());
    }
    @Test void tenantIsolationListingSeedAndLineageNeverCrossTenants() throws Exception {
        UUID id=definition(); Instant at=Instant.now().minus(Duration.ofDays(1));
        UUID listingId=UUID.randomUUID();
        listingEvidence.recorded(revision(listingId,UUID.randomUUID(),"OFFER",id,new BigDecimal("40"),BigDecimal.ONE,true,at));
        governance.addMember(publisher,community,userId);
        governance.setPolicy(publisher,community,new OrdinaryGovernanceService.PolicyRequest(1,1,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1"));
        governance.setEnabled(publisher,community,true);
        approveAndExecuteSeed(id,"30");
        TenantContext.clear(); UUID otherTenant=UUID.randomUUID();
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,otherTenant.toString());
        TenantContext.set(new TenantContext(otherTenant,null,UUID.randomUUID(),"test",TenantContext.DbRole.IDAX_APP));
        assertTrue(jdbc.queryForList("select 1 from stir.reference_observation where economic_lineage_id=?",listingId).isEmpty());
        assertTrue(jdbc.queryForList("select 1 from stir.community_seed where definition_id=?",id).isEmpty());
    }
    void expireVotingWindow(UUID proposalId) throws Exception {
        Timestamp opensAt=jdbc.queryForObject("select voting_opens_at from stir.ordinary_proposal where tenant_id=? and id=?",Timestamp.class,tenant,proposalId);
        jdbc.execute("reset role");
        jdbc.update("update stir.ordinary_proposal set voting_closes_at=? where tenant_id=? and id=?",new Timestamp(opensAt.getTime()+1),tenant,proposalId);
        jdbc.execute("set local role idax_app");
    }
}
