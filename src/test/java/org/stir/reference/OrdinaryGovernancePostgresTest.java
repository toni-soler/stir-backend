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

/** Ordinary community governance: a quorum-based, non-constitutional decision process, explicitly
 * separate from Seven Keys (ORDINARY_GOVERNANCE.md). No approveProposal(id) exists anywhere in
 * this service - the only path to APPROVED is the deterministic tally in lazyClose(), reproducible
 * from the frozen electorate and recorded votes alone. */
@Testcontainers
class OrdinaryGovernancePostgresTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc; ReferenceService reference; OrdinaryGovernanceService governance;
    UUID tenant=UUID.randomUUID(), userId=UUID.randomUUID(), community;
    UUID m1=UUID.randomUUID(), m2=UUID.randomUUID(), m3=UUID.randomUUID(), m4=UUID.randomUUID();
    CurrentUser proposer, superadmin;
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
        reference=new ReferenceService(jdbc,independence); governance=new OrdinaryGovernanceService(jdbc,reference);
        proposer=mock(CurrentUser.class); when(proposer.getUserId()).thenReturn(userId);
        superadmin=mock(CurrentUser.class); when(superadmin.getUserId()).thenReturn(UUID.randomUUID()); when(superadmin.isSuperuser()).thenReturn(true);
        community=UUID.randomUUID();
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,community,UUID.randomUUID());
        for(UUID m:List.of(userId,m1,m2,m3,m4)) governance.addMember(proposer,community,m);
        governance.setPolicy(proposer,community,new OrdinaryGovernanceService.PolicyRequest(1,2,2,3,1,"COUNTS_TOWARD_QUORUM_NOT_APPROVAL","v0.1 initial policy"));
    }
    @AfterEach void close() throws Exception {connection.rollback();connection.close();TenantContext.clear();}
    UUID definition() { return (UUID)reference.create(proposer,new ReferenceController.DefinitionRequest("Wool","1kg raw wool",Map.of(),BigDecimal.ONE,"kg")).get("id"); }
    UUID referenceProposal(UUID definitionId) {
        var p=reference.propose(proposer,definitionId,new ReferenceController.ProposalRequest("CONVENTION",new BigDecimal("10"),new BigDecimal("10"),"Community convention, not statistics","Assembly",90));
        return (UUID)p.get("id");
    }
    void expireVotingWindow(UUID proposalId) throws Exception {
        // Simulate the voting window elapsing: reset to the real Postgres superuser (bypasses both
        // RLS and the narrow column grant on voting_closes_at) purely to backdate this one test
        // fixture's timer - the same "reset role" technique ReferencePostgresTest already uses to
        // probe privilege boundaries, here used to fast-forward past a real time-based deadline
        // without a Clock abstraction anywhere in this codebase.
        Timestamp opensAt=jdbc.queryForObject("select voting_opens_at from stir.ordinary_proposal where tenant_id=? and id=?",Timestamp.class,tenant,proposalId);
        jdbc.execute("reset role");
        // Must stay > voting_opens_at (CHECK constraint) while already being in the past by the
        // time lazyClose() next reads Instant.now() - a few millis after opens_at always qualifies.
        jdbc.update("update stir.ordinary_proposal set voting_closes_at=? where tenant_id=? and id=?",new Timestamp(opensAt.getTime()+1),tenant,proposalId);
        jdbc.execute("set local role idax_app");
    }
    CurrentUser as(UUID id) { var u=mock(CurrentUser.class); when(u.getUserId()).thenReturn(id); return u; }

    @Test void fullLifecycleApprovedProposalPublishesExactlyTheVotedReference() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        var proposal=governance.proposePublishReference(proposer,id,rp);
        UUID proposalId=UUID.fromString(proposal.get("id").toString());
        governance.vote(as(m1),proposalId,"APPROVE"); governance.vote(as(m2),proposalId,"APPROVE"); governance.vote(as(m3),proposalId,"REJECT");
        expireVotingWindow(proposalId);
        assertEquals("APPROVED",governance.close(proposalId).get("status"));
        var result=governance.execute(proposer,proposalId);
        assertEquals("EXECUTED",result.get("status"));
        var published=reference.current(id);
        assertEquals(rp.toString(),published.get("proposal_id").toString());
        assertEquals(1,jdbc.queryForObject("select count(*) from stir.ordinary_proposal_execution where tenant_id=? and proposal_id=?",Integer.class,tenant,proposalId));
    }
    @Test void quorumNotMetExpiresWithoutActivation() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        governance.vote(as(m1),proposalId,"APPROVE"); // 1 of 5 electors - quorum needs 2/5*... quorum=1/2 of 5 -> need participants*2>=1*5 => need >=3 (integer ceil via cross-mult: p*2>=5 means p>=2.5 -> p>=3)
        expireVotingWindow(proposalId);
        assertEquals("EXPIRED",governance.close(proposalId).get("status"));
        assertThrows(ResponseStatusException.class,()->governance.execute(proposer,proposalId));
        assertNull(reference.current(id));
    }
    @Test void quorumMetButNoMajorityIsRejected() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); governance.vote(as(m1),proposalId,"REJECT"); governance.vote(as(m2),proposalId,"REJECT");
        expireVotingWindow(proposalId);
        assertEquals("REJECTED",governance.close(proposalId).get("status"));
        assertThrows(ResponseStatusException.class,()->governance.execute(proposer,proposalId));
    }
    @Test void doubleVoteIsRejected() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        governance.vote(as(m1),proposalId,"APPROVE");
        assertThrows(ResponseStatusException.class,()->governance.vote(as(m1),proposalId,"APPROVE"));
        assertThrows(ResponseStatusException.class,()->governance.vote(as(m1),proposalId,"REJECT"));
    }
    @Test void userRemovedAfterVotingOpensCanStillVoteFromTheFrozenElectorate() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        governance.removeMember(proposer,community,m1);
        assertDoesNotThrow(()->governance.vote(as(m1),proposalId,"APPROVE"));
    }
    @Test void userAddedAfterVotingOpensCannotVoteOnAnAlreadyFrozenElectorate() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        UUID newcomer=UUID.randomUUID();
        governance.addMember(proposer,community,newcomer);
        assertThrows(ResponseStatusException.class,()->governance.vote(as(newcomer),proposalId,"APPROVE"));
    }
    @Test void nonElectorCannotProposeOrVote() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID stranger=UUID.randomUUID();
        assertThrows(ResponseStatusException.class,()->governance.proposePublishReference(as(stranger),id,rp));
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        assertThrows(ResponseStatusException.class,()->governance.vote(as(stranger),proposalId,"APPROVE"));
    }
    @Test void crossTenantVoteIsInvisibleUnderRls() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        UUID otherTenant=UUID.randomUUID();
        jdbc.execute("set local role idax_admin"); jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,otherTenant.toString());
        assertEquals(0,jdbc.queryForObject("select count(*) from stir.ordinary_proposal where id=?",Integer.class,proposalId));
        assertEquals(0,jdbc.queryForObject("select count(*) from stir.ordinary_proposal_electorate where proposal_id=?",Integer.class,proposalId));
    }
    @Test void doubleExecutionIsRejected() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        governance.vote(as(m1),proposalId,"APPROVE"); governance.vote(as(m2),proposalId,"APPROVE"); governance.vote(as(m3),proposalId,"APPROVE");
        expireVotingWindow(proposalId);
        governance.execute(proposer,proposalId);
        assertThrows(ResponseStatusException.class,()->governance.execute(proposer,proposalId));
    }
    @Test void twoOpenProposalsCannotTargetTheSameReferenceProposal() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        governance.proposePublishReference(proposer,id,rp);
        assertThrows(ResponseStatusException.class,()->governance.proposePublishReference(proposer,id,rp));
    }
    @Test void publisherCannotBypassAnEnabledGovernanceGate() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        assertThrows(ResponseStatusException.class,()->reference.publish(proposer,rp,"Direct bypass attempt"));
    }
    @Test void withoutGovernanceEnabledDirectPublishStillWorksExactlyAsBefore() {
        UUID id=definition(); UUID rp=referenceProposal(id);
        assertDoesNotThrow(()->reference.publish(proposer,rp,"Ordinary delegated publisher, unaffected"));
    }
    @Test void platformSuperAdminCannotVoteOrExecuteRegardlessOfPermissionBypass() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        assertThrows(AccessDeniedException.class,()->governance.vote(superadmin,proposalId,"APPROVE"));
        governance.vote(as(m1),proposalId,"APPROVE"); governance.vote(as(m2),proposalId,"APPROVE"); governance.vote(as(m3),proposalId,"APPROVE");
        assertThrows(AccessDeniedException.class,()->governance.execute(superadmin,proposalId));
    }
    @Test void aProposalCrossingTheConstitutionalFloorCannotBeExecutedEvenUnanimouslyApproved() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition();
        // minimumParticipants=3 is below the constitutional floor (6) - unreachable via the real
        // controller's own @Min(6) bean validation, but the SAME governance execute() path any
        // future caller could reach must still refuse it once it arrives as a Java object, exactly
        // like the ordinary direct-policy path already does (SevenKeysPostgresTest.ordinaryPolicyCannotCrossNewConstitutionalFloor).
        var below=new ReferenceController.PolicyRequest(90,5,3,new BigDecimal("0.40"),30,"Attempt to lower below constitutional floor");
        UUID proposalId=UUID.fromString(governance.proposePolicyChange(proposer,id,below).get("id").toString());
        governance.vote(as(userId),proposalId,"APPROVE"); governance.vote(as(m1),proposalId,"APPROVE");
        governance.vote(as(m2),proposalId,"APPROVE"); governance.vote(as(m3),proposalId,"APPROVE"); governance.vote(as(m4),proposalId,"APPROVE");
        expireVotingWindow(proposalId);
        assertEquals("APPROVED",governance.close(proposalId).get("status"));
        assertThrows(ResponseStatusException.class,()->governance.execute(proposer,proposalId));
        assertEquals(6,reference.currentPolicy(id).get("minimum_participants"));
    }
    @Test void policyChangedAfterProposalCreationVoidsExecutionAsStale() throws Exception {
        governance.setEnabled(proposer,community,true);
        UUID id=definition();
        var change=new ReferenceController.PolicyRequest(90,5,6,new BigDecimal("0.40"),30,"Tighten freshness");
        UUID proposalId=UUID.fromString(governance.proposePolicyChange(proposer,id,change).get("id").toString());
        // A direct (non-governance) policy edit happens in between - still legal since governance
        // gating in this test only wraps publish()/policy() by convention of always routing through
        // it; simulate the underlying state moving by disabling governance briefly and editing directly.
        governance.setEnabled(proposer,community,false);
        reference.policy(proposer,id,new ReferenceController.PolicyRequest(90,5,6,new BigDecimal("0.35"),30,"Someone else's direct edit"));
        governance.setEnabled(proposer,community,true);
        governance.vote(as(m1),proposalId,"APPROVE"); governance.vote(as(m2),proposalId,"APPROVE"); governance.vote(as(m3),proposalId,"APPROVE");
        expireVotingWindow(proposalId);
        assertEquals("APPROVED",governance.close(proposalId).get("status"));
        assertThrows(ResponseStatusException.class,()->governance.execute(proposer,proposalId));
        // Status stays APPROVED - the vote outcome itself is never rewritten - but staleness is
        // now visibly true, so a caller/UI knows this approval can no longer be executed as-is.
        var reread=governance.proposal(proposalId);
        assertEquals("APPROVED",reread.get("status"));
        assertEquals(true,reread.get("stale"));
    }
    @Test void electorateAndVotesReconstructExactlyWhoCouldVoteAndWhoDid() {
        governance.setEnabled(proposer,community,true);
        UUID id=definition(); UUID rp=referenceProposal(id);
        UUID proposalId=UUID.fromString(governance.proposePublishReference(proposer,id,rp).get("id").toString());
        governance.vote(as(m1),proposalId,"APPROVE"); governance.vote(as(m2),proposalId,"ABSTAIN");
        assertEquals(5,governance.electorate(proposalId).size());
        var votes=governance.votes(proposalId);
        assertEquals(2,votes.size());
        assertTrue(votes.stream().anyMatch(v->m1.equals(v.get("voter_id")) && "APPROVE".equals(v.get("choice"))));
    }
}
