package org.stir.cases;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.DbSessionContextService;
import es.idynamicsax.idax.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.ExecutionAttemptRecorder;
import org.stir.economic.OstrisClient;
import org.stir.fulfilment.FulfilmentService;
import org.stir.negotiation.CanonicalJson;
import org.stir.notification.NotificationService;
import org.stir.reference.IdaxCoreMembershipFixture;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agreement cases against real PostgreSQL, as idax_app under RLS (AGREEMENT_CASES.md). osTRIS is a
 * mock here: what is asserted is what STIR asks of it and when. The real adjudicated reversal runs
 * in agreement_cases_e2e.py against a real osTRIS.
 */
@Testcontainers
class CasePostgresTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc; OstrisClient ostris;
    FulfilmentService fulfilment; CaseService cases; CaseReviewService review; CaseRemedyService remedies;
    final UUID tenant = UUID.randomUUID(), community = UUID.randomUUID(), unit = UUID.randomUUID();
    final UUID owner = UUID.randomUUID(), initiator = UUID.randomUUID(), admin = UUID.randomUUID();
    final UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID(), r3 = UUID.randomUUID(), stranger = UUID.randomUUID();
    final UUID payerAccount = UUID.randomUUID(), payeeAccount = UUID.randomUUID(), originalTx = UUID.randomUUID();
    final UUID authority = UUID.randomUUID();
    CurrentUser ownerUser, initiatorUser, adminUser, rev1, rev2, rev3, strangerUser, superuser;
    UUID agreement;

    @BeforeAll static void migrate() throws Exception {
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()); var s = c.createStatement()) {
            s.execute("create role idax_app; create role idax_admin");
        }
        IdaxCoreMembershipFixture.install(db);
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).schemas("stir")
            .locations("classpath:db/migration-stir").load().migrate();
    }

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())", tenant, community, unit);
        IdaxCoreMembershipFixture.member(connection, tenant, admin, "owner");
        for (UUID u : List.of(owner, initiator, r1, r2, r3, stranger)) IdaxCoreMembershipFixture.member(connection, tenant, u, "user");
        jdbc.execute("set local role idax_app");
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)", String.class, tenant.toString());
        TenantContext.set(new TenantContext(tenant, null, owner, "test", TenantContext.DbRole.IDAX_APP));
        for (UUID reviewer : List.of(r1, r2, r3)) binding(reviewer, UUID.randomUUID());
        ostris = mock(OstrisClient.class);
        when(ostris.registerResolutionAuthority(eq(community), anyList(), anyInt()))
            .thenAnswer(i -> new OstrisClient.ResolutionAuthority(authority, 1, i.getArgument(2), i.getArgument(1), 1, null));
        when(ostris.governancePayload(any(), any(), anyInt(), any(), any(), anyList()))
            .thenReturn(new OstrisClient.GovernanceSigningPayload("OSTRIS:TX:GOVAUTH:V1", "{\"payload\":true}", "a".repeat(64)));
        when(ostris.commit(any())).thenReturn(new OstrisClient.CommitReceipt(UUID.randomUUID(), 7, "b".repeat(64), Instant.now()));
        var notifications = mock(NotificationService.class);
        fulfilment = new FulfilmentService(jdbc, notifications, new ExecutionAttemptRecorder(jdbc, mock(DbSessionContextService.class)));
        cases = new CaseService(jdbc, notifications, ostris, fulfilment);
        review = new CaseReviewService(jdbc, ostris);
        remedies = new CaseRemedyService(jdbc, ostris, cases, notifications);
        ownerUser = user(owner, false); initiatorUser = user(initiator, false); adminUser = user(admin, false);
        rev1 = user(r1, false); rev2 = user(r2, false); rev3 = user(r3, false); strangerUser = user(stranger, false);
        superuser = user(UUID.randomUUID(), true);
        agreement = committedAgreement();
    }
    @AfterEach void close() throws Exception { connection.rollback(); connection.close(); TenantContext.clear(); }

    static CurrentUser user(UUID id, boolean superuser) {
        var u = mock(CurrentUser.class); when(u.getUserId()).thenReturn(id); when(u.isSuperuser()).thenReturn(superuser); return u;
    }
    void binding(UUID user, UUID account) {
        jdbc.update("insert into stir.participant_economic_binding(id,tenant_id,user_id,community_id,unit_id,participant_id,account_id,controller_id,"
            + "credential_id,public_key_base64url,created_at) values (?,?,?,?,?,?,?,?,?,?,now())", UUID.randomUUID(), tenant, user, community, unit,
            UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(), "k".repeat(43));
    }

    /** OFFER: the listing owner provides the service and is paid 12 CRD by the initiator; the exchange is committed. */
    UUID committedAgreement() {
        UUID listing = UUID.randomUUID(), negotiation = UUID.randomUUID(), offer = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.update("insert into stir.listing(id,tenant_id,owner_id,direction,title,description,category,resource_kind,status,created_at,updated_at) "
            + "values (?,?,?,'OFFER','Bike repair','Brakes','home','service','ACTIVE',now(),now())", listing, tenant, owner);
        jdbc.update("insert into stir.negotiation(id,tenant_id,listing_id,initiator_id,owner_id,status,created_at,updated_at) values (?,?,?,?,?,'ACCEPTED',now(),now())",
            negotiation, tenant, listing, initiator, owner);
        jdbc.update("insert into stir.offer(id,tenant_id,negotiation_id,listing_id,sequence_number,author_id,message,status,created_at) values (?,?,?,?,1,?,'Deal','ACCEPTED',now())",
            offer, tenant, negotiation, listing, initiator);
        jdbc.update("insert into stir.agreement(id,tenant_id,negotiation_id,listing_id,offer_id,initiator_id,owner_id,economic_phase,payer_user_id,payee_user_id,created_at) "
            + "values (?,?,?,?,?,?,?,'COMMITTED',?,?,now())", id, tenant, negotiation, listing, offer, initiator, owner, initiator, owner);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("listingDirection", "OFFER"); fields.put("scope", "Brakes and gears");
        fields.put("proposedAmount", "12"); fields.put("proposedUnitRef", "CRD");
        byte[] bytes = CanonicalJson.canonicalBytes(fields);
        jdbc.update("insert into stir.agreement_snapshot(id,tenant_id,agreement_id,schema_version,canonical_json,nonce,digest_sha256,created_at) values (?,?,?,4,?,?,?,now())",
            UUID.randomUUID(), tenant, id, new String(bytes, StandardCharsets.UTF_8), "n".repeat(43), CanonicalJson.sha256Hex(bytes));
        jdbc.update("insert into stir.trade(id,tenant_id,agreement_id,community_id,unit_id,transaction_id,payer_account_id,payee_account_id,payer_user_id,payee_user_id,"
            + "amount,contractual_metadata_digest,execution_state,committed_sequence,protocol_digest,committed_at,created_at,updated_at) "
            + "values (?,?,?,?,?,?,?,?,?,?,1200,?,'COMMITTED',3,?,now(),now(),now())",
            UUID.randomUUID(), tenant, id, community, unit, originalTx, payerAccount, payeeAccount, initiator, owner, CanonicalJson.sha256Hex(bytes), "c".repeat(64));
        return id;
    }

    UUID object() {
        return fulfilment.view(ownerUser, agreement).obligations().stream().filter(o -> o.kind().equals("OBJECT")).findFirst().orElseThrow().id();
    }
    void configure() { review.configure(adminUser, new CaseReviewService.ConfigureRequest(List.of(r1, r2, r3), 2, 48)); }
    CaseService.CaseView openNonDelivery() {
        fulfilment.declare(ownerUser, agreement, object(), "PERFORMED", null);
        fulfilment.declare(initiatorUser, agreement, object(), "NOT_RECEIVED", null);
        return cases.open(initiatorUser, agreement, new CaseService.OpenRequest(object(), "NON_DELIVERY", "The bike was never repaired."));
    }
    CaseService.DecisionView decision(CaseService.CaseView view, String type) {
        return view.decisions().stream().filter(d -> d.decisionType().equals(type) && !"OVERTURNED".equals(d.status())).findFirst().orElseThrow();
    }
    static int status(ResponseStatusException ex) { return ex.getStatusCode().value(); }
    void rejected(String sql, Object... args) {
        try {
            var savepoint = connection.setSavepoint();
            var error = assertThrows(Exception.class, () -> jdbc.update(sql, args), sql);
            connection.rollback(savepoint);
            var message = new StringBuilder();
            for (Throwable cause = error; cause != null; cause = cause.getCause()) message.append(cause.getMessage()).append(" | ");
            assertTrue(message.toString().contains("STIR_") || message.toString().contains("permission denied"), "expected a guard or a missing grant, got: " + message);
        } catch (java.sql.SQLException ex) { throw new IllegalStateException(ex); }
    }

    @Test void reviewersAreNamedByCommunityAuthorityAndRegisteredAsTheOsTrisAuthority() {
        assertFalse(review.view(ownerUser).configured());
        assertThrows(AccessDeniedException.class, () -> review.configure(superuser, new CaseReviewService.ConfigureRequest(List.of(r1, r2), 1, 48)));
        assertThrows(AccessDeniedException.class, () -> review.configure(ownerUser, new CaseReviewService.ConfigureRequest(List.of(r1, r2), 1, 48)));
        assertEquals(400, status(assertThrows(ResponseStatusException.class, () -> review.configure(adminUser, new CaseReviewService.ConfigureRequest(List.of(r1), 1, 48)))));
        assertEquals(400, status(assertThrows(ResponseStatusException.class, () -> review.configure(adminUser, new CaseReviewService.ConfigureRequest(List.of(r1, r2), 3, 48)))));
        // A reviewer signs corrections with their own key, so they must have an economic identity.
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> review.configure(adminUser, new CaseReviewService.ConfigureRequest(List.of(r1, stranger), 1, 48)))));
        configure();
        var view = review.view(rev1);
        assertTrue(view.configured());
        assertTrue(view.youReview());
        assertEquals(2, view.signatureThreshold());
        assertEquals(authority, view.ostrisAuthorityId());
        verify(ostris).registerResolutionAuthority(eq(community), argThat(list -> list.size() == 3), eq(2));
        // Replacing the roster records the removal; the history is never deleted.
        review.configure(adminUser, new CaseReviewService.ConfigureRequest(List.of(r1, r2), 1, 48));
        assertEquals(1, jdbc.queryForObject("select count(*) from stir.case_reviewer where user_id=? and removed_at is not null", Integer.class, r3));
        rejected("delete from stir.case_reviewer where user_id=?", r3);
    }

    @Test void aNonDeliveryCaseRunsFromOpeningToAppealAndAnAdjudicatedReversal() {
        assertEquals(409, status(assertThrows(ResponseStatusException.class, this::openNonDelivery)), "no reviewers named yet");
        configure();
        // Only the party the obligation is owed to claims it was not fulfilled.
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.open(ownerUser, agreement, new CaseService.OpenRequest(object(), "NON_DELIVERY", "x")))));
        var opened = openNonDelivery();
        assertEquals("OPEN", opened.status());
        assertEquals("CLAIMANT", opened.yourRole());
        assertEquals(owner, opened.respondentUserId());
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.open(initiatorUser, agreement, new CaseService.OpenRequest(object(), "NON_DELIVERY", "again")))), "one live case per obligation");
        UUID caseId = opened.id();

        // Nobody else sees it: not a community member who is not a reviewer, not a platform superuser.
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> cases.view(strangerUser, caseId))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> cases.view(superuser, caseId))));

        // Restricted evidence: the other party learns it exists, not what it says.
        cases.addEvidence(ownerUser, caseId, new CaseService.EvidenceRequest("Courier receipt with a private address", "REVIEWERS_ONLY", "TRK-123"));
        cases.addEvidence(initiatorUser, caseId, new CaseService.EvidenceRequest("Photo of the bike, unrepaired", null, null));
        var asClaimant = cases.view(initiatorUser, caseId);
        assertEquals(2, asClaimant.evidence().size());
        assertNull(asClaimant.evidence().getFirst().description());
        assertNull(asClaimant.evidence().getFirst().externalReference());
        assertEquals("Courier receipt with a private address", cases.view(rev2, caseId).evidence().getFirst().description());
        assertEquals("Courier receipt with a private address", cases.view(ownerUser, caseId).evidence().getFirst().description());

        // A party is not a reviewer of their own case, even when listed as one.
        assertEquals(403, status(assertThrows(ResponseStatusException.class, () -> cases.take(initiatorUser, caseId))));
        assertEquals("UNDER_REVIEW", cases.take(rev1, caseId).status());

        // Containment pauses the Agreement's amendments and execution, never a person or a balance.
        var contained = cases.contain(rev1, caseId, new CaseService.ContainmentRequest("Hold while the delivery is checked", Instant.now().plus(5, ChronoUnit.DAYS)));
        assertTrue(contained.containments().getFirst().active());
        var head = fulfilment.view(ownerUser, agreement).headDigest();
        var blocked = assertThrows(ResponseStatusException.class, () -> fulfilment.propose(ownerUser, agreement,
            new FulfilmentService.AmendmentRequest(head, List.of(), List.of(new FulfilmentService.NewObligation("OWNER_TASKS", "Call first", null, null)), "x", null)));
        assertTrue(blocked.getReason().startsWith("CASE_CONTAINMENT_ACTIVE"));
        assertNotNull(fulfilment.view(initiatorUser, agreement).containment());

        // Only the reviewer who took the case issues its finding.
        var finding = new CaseService.FindingRequest("ESTABLISHED", "FULL_REVERSAL", "Return the 12 CRD; the repair can be agreed again.", "The receipt shows no delivery.");
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> cases.issueFinding(rev2, caseId, finding))));
        var issued = cases.issueFinding(rev1, caseId, finding);
        assertEquals("FINDING_ISSUED", issued.status());
        var f = decision(issued, "FINDING");
        assertEquals("ISSUED", f.status());
        assertNotNull(f.ostrisBasisId());
        verify(ostris).recordResolutionBasis(community, f.ostrisBasisId(), "FINAL_DISPUTE_RESOLUTION");
        // Time does not finalize it, and neither does one waiver.
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> cases.finalizeDecision(rev2, caseId, f.id()))));
        // A conduct question waits for a final finding; a sanction waits for a final attribution of breach.
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.issueAttribution(rev2, caseId, new CaseService.AttributionRequest("OBLIGOR", "BREACH", null, "x")))));

        // The respondent appeals; the author of the finding cannot decide the appeal.
        var appealed = cases.appeal(ownerUser, caseId, f.id(), new CaseService.AppealRequest("I delivered it to the address given."));
        assertEquals("APPEALED", appealed.status());
        UUID appealId = decision(appealed, "FINDING").appeal().id();
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.decideAppeal(rev1, caseId, appealId, new CaseService.AppealDecisionRequest("UPHELD", "x")))));
        var upheld = cases.decideAppeal(rev2, caseId, appealId, new CaseService.AppealDecisionRequest("UPHELD", "The address on the receipt is not the agreed one."));
        assertEquals("RESOLVED", upheld.status());
        assertEquals("FINAL", decision(upheld, "FINDING").status());
        verify(ostris).resolutionBasisFinality(community, f.ostrisBasisId(), "FINAL");
        assertFalse(upheld.containments().getFirst().active(), "resolving the effect lifts the containment");

        // Conduct: a good-faith third party (the courier). No sanction can follow from it.
        cases.issueAttribution(rev2, caseId, new CaseService.AttributionRequest("THIRD_PARTY", "GOOD_FAITH_THIRD_PARTY", "The courier", "Misdelivered."));
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.issueSanction(rev3, caseId, new CaseService.SanctionRequest("WARNING", "x")))));

        // The repair of the economic effect: an adjudicated REVERSAL, exactly the original negated.
        var prepared = remedies.prepare(rev3, caseId);
        var remedy = prepared.remedies().getFirst();
        assertEquals("AWAITING_AUTHORITY", remedy.status());
        verify(ostris).proposeGoverned(eq(community), eq(unit), eq(remedy.ostrisTransactionId()), eq("REVERSAL"),
            eq(List.of(new OstrisClient.Entry(payerAccount.toString(), "1200"), new OstrisClient.Entry(payeeAccount.toString(), "-1200"))),
            eq(Map.of("reversesTransactionId", originalTx.toString())), eq("FINAL_DISPUTE_RESOLUTION"), eq(f.ostrisBasisId()));
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> remedies.prepare(rev3, caseId))));
        // Parties never sign a correction about themselves.
        assertEquals(403, status(assertThrows(ResponseStatusException.class,
            () -> remedies.sign(ownerUser, caseId, remedy.id(), new CaseRemedyService.SignatureRequest(UUID.randomUUID(), "sig")))));
        remedies.sign(rev1, caseId, remedy.id(), new CaseRemedyService.SignatureRequest(UUID.randomUUID(), "sig1"));
        verify(ostris, never()).commit(any());   // threshold 2 not reached
        var executed = remedies.sign(rev2, caseId, remedy.id(), new CaseRemedyService.SignatureRequest(UUID.randomUUID(), "sig2"));
        assertEquals("EXECUTED", executed.remedies().getFirst().status());
        assertEquals(List.of(r1, r2).stream().sorted().toList(), executed.remedies().getFirst().signedBy().stream().sorted().toList());
        verify(ostris).commit(remedy.ostrisTransactionId());

        // The board shows the case and the correction next to the obligations; the obligation's own status is untouched.
        var board = fulfilment.view(initiatorUser, agreement);
        assertEquals("RESOLVED", board.cases().getFirst().status());
        assertEquals("EXECUTED", board.corrections().getFirst().status());
        assertEquals("IN_DISAGREEMENT", board.obligations().stream().filter(o -> o.kind().equals("OBJECT")).findFirst().orElseThrow().status());
        assertNull(board.containment());
        // The timeline tells each step, in order.
        var types = cases.view(initiatorUser, caseId).events().stream().map(CaseService.EventView::type).toList();
        assertEquals(List.of("OPENED", "EVIDENCE_ADDED", "EVIDENCE_ADDED", "REVIEW_TAKEN", "CONTAINMENT_APPLIED", "FINDING_ISSUED", "APPEAL_FILED",
            "APPEAL_DECIDED", "DECISION_FINAL", "CONTAINMENT_LIFTED", "ATTRIBUTION_ISSUED", "REMEDY_PREPARED", "REMEDY_SIGNED", "REMEDY_SIGNED",
            "REMEDY_EXECUTED"), types);
    }

    @Test void anOverturnedFindingGoesBackToReviewByAnotherReviewer() {
        configure();
        UUID caseId = openNonDelivery().id();
        cases.take(rev1, caseId);
        var f = decision(cases.issueFinding(rev1, caseId, new CaseService.FindingRequest("ESTABLISHED", "FULL_REVERSAL", null, "First look.")), "FINDING");
        var appeal = decision(cases.appeal(ownerUser, caseId, f.id(), new CaseService.AppealRequest("Wrong.")), "FINDING").appeal();
        var overturned = cases.decideAppeal(rev2, caseId, appeal.id(), new CaseService.AppealDecisionRequest("OVERTURNED", "The evidence was not weighed."));
        assertEquals("UNDER_REVIEW", overturned.status());
        assertEquals(r2, overturned.reviewerUserId());
        verify(ostris).resolutionBasisFinality(community, f.ostrisBasisId(), "OVERTURNED");
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.issueFinding(rev1, caseId, new CaseService.FindingRequest("NOT_ESTABLISHED", "NONE", null, "x")))), "the author does not re-decide");
        // An effect that was not established orders no correction.
        assertEquals(400, status(assertThrows(ResponseStatusException.class, () -> cases.issueFinding(rev2, caseId,
            new CaseService.FindingRequest("NOT_ESTABLISHED", "FULL_REVERSAL", null, "x")))));
        var second = cases.issueFinding(rev2, caseId, new CaseService.FindingRequest("NOT_ESTABLISHED", "NONE", null, "No proof either way."));
        assertEquals(2, second.decisions().size());
    }

    @Test void aSanctionOnlyFollowsAnExplicitFinalAttributionOfBreachAndChangesNothingElse() {
        configure();
        UUID caseId = openNonDelivery().id();
        cases.take(rev1, caseId);
        var f = decision(cases.issueFinding(rev1, caseId, new CaseService.FindingRequest("ESTABLISHED", "NONE", "Repair it by Friday.", "Not delivered.")), "FINDING");
        cases.waive(ownerUser, caseId, f.id());
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> cases.finalizeDecision(rev2, caseId, f.id()))), "one waiver is not enough");
        cases.waive(initiatorUser, caseId, f.id());
        assertEquals("RESOLVED", cases.finalizeDecision(rev2, caseId, f.id()).status());
        verify(ostris, never()).recordResolutionBasis(any(), any(), any());   // no economic correction ordered
        var a = decision(cases.issueAttribution(rev2, caseId, new CaseService.AttributionRequest("OBLIGOR", "BREACH", null, "Acknowledged and not done.")), "ATTRIBUTION");
        cases.waive(ownerUser, caseId, a.id());
        cases.waive(initiatorUser, caseId, a.id());
        cases.finalizeDecision(rev3, caseId, a.id());
        String historyBefore = fulfilment.history(ownerUser, owner).status();
        var sanctioned = cases.issueSanction(rev3, caseId, new CaseService.SanctionRequest("WARNING", "A first warning."));
        var s = decision(sanctioned, "SANCTION");
        assertEquals(owner, s.subjectUserId());
        assertEquals("ISSUED", s.status());
        // The sanction is appealable like any decision, and it touches no history, profile or balance.
        assertEquals(historyBefore, fulfilment.history(ownerUser, owner).status());
        assertEquals(0, jdbc.queryForObject("select count(*) from stir.agreement_case_remedy", Integer.class));
        assertEquals("APPEALED", decision(cases.appeal(ownerUser, caseId, s.id(), new CaseService.AppealRequest("Disproportionate.")), "SANCTION").status());
    }

    @Test void historyIsAppendOnlyAndDecisionsAreFrozen() {
        configure();
        UUID caseId = openNonDelivery().id();
        cases.take(rev1, caseId);
        var f = decision(cases.issueFinding(rev1, caseId, new CaseService.FindingRequest("ESTABLISHED", "NONE", null, "x")), "FINDING");
        rejected("update stir.agreement_case_event set note='edited' where case_id=?", caseId);
        rejected("delete from stir.agreement_case_evidence where case_id=?", caseId);
        rejected("update stir.agreement_case_decision set reasoning='rewritten' where id=?", f.id());
        rejected("update stir.agreement_case set claimant_user_id=? where id=?", owner, caseId);
        rejected("delete from stir.agreement_case where id=?", caseId);
        cases.withdraw(initiatorUser, caseId, new CaseService.WithdrawRequest("We settled it between us."));
        rejected("update stir.agreement_case set status='OPEN' where id=?", caseId);
        // Only the claimant withdraws; a withdrawn case is closed for everyone.
        assertEquals(409, status(assertThrows(ResponseStatusException.class,
            () -> cases.addEvidence(ownerUser, caseId, new CaseService.EvidenceRequest("late", null, null)))));
    }

    @Test void casesAreTenantScoped() {
        configure();
        UUID caseId = openNonDelivery().id();
        UUID other = UUID.randomUUID();
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)", String.class, other.toString());
        TenantContext.set(new TenantContext(other, null, owner, "test", TenantContext.DbRole.IDAX_APP));
        assertEquals(0, jdbc.queryForObject("select count(*) from stir.agreement_case", Integer.class));
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> cases.view(initiatorUser, caseId))));
    }
}
