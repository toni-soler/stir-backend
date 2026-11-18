package org.stir.fulfilment;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.DbSessionContextService;
import es.idynamicsax.idax.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.ExecutionAttemptRecorder;
import org.stir.economic.ExecutionOutcome;
import org.stir.negotiation.CanonicalJson;
import org.stir.notification.NotificationService;
import org.stir.reference.IdaxCoreMembershipFixture;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Agreement fulfilment against real PostgreSQL, as idax_app under RLS (AGREEMENT_FULFILMENT.md). */
@Testcontainers
class FulfilmentPostgresTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc; FulfilmentService service; ExecutionAttemptRecorder attempts;
    final UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID(), initiator = UUID.randomUUID();
    CurrentUser ownerUser, initiatorUser;
    UUID agreement, trade; String snapshotDigest;

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
        jdbc.execute("set local role idax_app");
        tenant(tenant);
        TenantContext.set(new TenantContext(tenant, null, owner, "test", TenantContext.DbRole.IDAX_APP));
        attempts = new ExecutionAttemptRecorder(jdbc, mock(DbSessionContextService.class));
        service = new FulfilmentService(jdbc, mock(NotificationService.class), attempts);
        ownerUser = user(owner); initiatorUser = user(initiator);
        agreement = agreement("OFFER", "Fix the brakes", "Bring the bike", "12");
    }
    @AfterEach void close() throws Exception { connection.rollback(); connection.close(); TenantContext.clear(); }

    void tenant(UUID id) { jdbc.queryForObject("select set_config('app.tenant_id',?,true)", String.class, id.toString()); }
    static CurrentUser user(UUID id) { var u = mock(CurrentUser.class); when(u.getUserId()).thenReturn(id); return u; }

    /** A real listing/negotiation/offer/agreement/snapshot chain, owner = listing owner, initiator = the other party. */
    UUID agreement(String direction, String ownerTasks, String initiatorTasks, String amount) {
        UUID listing = UUID.randomUUID(), negotiation = UUID.randomUUID(), offer = UUID.randomUUID(), id = UUID.randomUUID();
        jdbc.update("insert into stir.listing(id,tenant_id,owner_id,direction,title,description,category,resource_kind,status,created_at,updated_at) "
            + "values (?,?,?,?,'Bike repair','Brakes and gears','home','service','ACTIVE',now(),now())", listing, tenant, owner, direction);
        jdbc.update("insert into stir.negotiation(id,tenant_id,listing_id,initiator_id,owner_id,status,created_at,updated_at) values (?,?,?,?,?,'ACCEPTED',now(),now())",
            negotiation, tenant, listing, initiator, owner);
        jdbc.update("insert into stir.offer(id,tenant_id,negotiation_id,listing_id,sequence_number,author_id,message,status,created_at) values (?,?,?,?,1,?,'Deal','ACCEPTED',now())",
            offer, tenant, negotiation, listing, initiator);
        boolean wanted = "WANTED".equals(direction);
        UUID payer = amount == null ? null : wanted ? owner : initiator, payee = amount == null ? null : wanted ? initiator : owner;
        jdbc.update("insert into stir.agreement(id,tenant_id,negotiation_id,listing_id,offer_id,initiator_id,owner_id,economic_phase,payer_user_id,payee_user_id,created_at) "
            + "values (?,?,?,?,?,?,?,?,?,?,now())", id, tenant, negotiation, listing, offer, initiator, owner,
            amount == null ? "NOT_APPLICABLE" : "AWAITING_ECONOMIC_EXECUTION", payer, payee);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("listingDirection", direction); fields.put("scope", "Brakes and gears, parts included");
        fields.put("ownerTasks", ownerTasks); fields.put("initiatorTasks", initiatorTasks);
        fields.put("fulfilmentMode", "ON_SITE"); fields.put("fulfilBy", "2026-12-01");
        fields.put("proposedAmount", amount); fields.put("proposedUnitRef", amount == null ? null : "FFC");
        byte[] bytes = CanonicalJson.canonicalBytes(fields);
        snapshotDigest = CanonicalJson.sha256Hex(bytes);
        jdbc.update("insert into stir.agreement_snapshot(id,tenant_id,agreement_id,schema_version,canonical_json,nonce,digest_sha256,created_at) values (?,?,?,4,?,?,?,now())",
            UUID.randomUUID(), tenant, id, new String(bytes, StandardCharsets.UTF_8), "n".repeat(43), snapshotDigest);
        if (amount != null) {
            trade = UUID.randomUUID();
            jdbc.update("insert into stir.trade(id,tenant_id,agreement_id,community_id,unit_id,transaction_id,payer_account_id,payee_account_id,payer_user_id,payee_user_id,"
                + "amount,contractual_metadata_digest,execution_state,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,12,?,'AWAITING_SIGNATURES',now(),now())",
                trade, tenant, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), payer, payee, snapshotDigest);
        }
        return id;
    }

    FulfilmentService.ObligationView obligation(FulfilmentService.FulfilmentView view, String kind) {
        return view.obligations().stream().filter(o -> o.kind().equals(kind) && !"SUPERSEDED".equals(o.status())).findFirst().orElseThrow();
    }
    static int status(ResponseStatusException ex) { return ex.getStatusCode().value(); }
    /** The database itself refuses the statement; a savepoint keeps the test transaction usable afterwards. */
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

    @Test void obligationsAreDerivedOnceFromTheSnapshotWithTheirParties() {
        var view = service.view(ownerUser, agreement);
        service.view(initiatorUser, agreement);
        assertEquals(4, view.obligations().size());
        assertEquals(4, jdbc.queryForObject("select count(*) from stir.agreement_obligation where agreement_id=?", Integer.class, agreement));
        var object = obligation(view, "OBJECT");
        assertEquals(owner, object.obligorUserId());   // OFFER: the listing owner provides
        assertEquals(initiator, object.beneficiaryUserId());
        assertEquals(LocalDate.parse("2026-12-01"), object.fulfilBy());
        assertEquals("ON_SITE", object.fulfilmentMode());
        assertEquals("OBLIGOR", object.yourRole());
        assertEquals(initiator, obligation(view, "INITIATOR_TASKS").obligorUserId());
        var economic = obligation(view, "ECONOMIC_EXCHANGE");
        assertEquals(initiator, economic.obligorUserId());
        assertEquals("12 FFC", economic.description());
        assertEquals(snapshotDigest, view.headDigest());
        // The snapshot is never touched.
        assertEquals(snapshotDigest, jdbc.queryForObject("select digest_sha256 from stir.agreement_snapshot where agreement_id=?", String.class, agreement));
    }

    @Test void inAWantedListingTheInitiatorProvidesTheObject() {
        UUID wanted = agreement("WANTED", null, null, null);
        var view = service.view(ownerUser, wanted);
        assertEquals(List.of("OBJECT"), view.obligations().stream().map(FulfilmentService.ObligationView::kind).toList());
        assertEquals(initiator, view.obligations().getFirst().obligorUserId());
    }

    @Test void aPartialDeliveryIsRecordedAsPartialWithWhatIsMissing() {
        UUID object = obligation(service.view(ownerUser, agreement), "OBJECT").id();
        var ex = assertThrows(ResponseStatusException.class, () -> service.declare(ownerUser, agreement, object, "PARTIALLY_PERFORMED", " "));
        assertEquals(400, status(ex));
        service.declare(ownerUser, agreement, object, "PARTIALLY_PERFORMED", "Brakes fixed, gears next week");
        assertEquals("DECLARED", obligation(service.view(ownerUser, agreement), "OBJECT").status());
        var view = service.declare(initiatorUser, agreement, object, "PARTIAL_RECEIVED", "Brakes work; gears still slip");
        var partial = obligation(view, "OBJECT");
        assertEquals("PARTIAL", partial.status());
        assertEquals(2, partial.events().size());
        assertEquals("BENEFICIARY", partial.events().get(1).actorRole());
        // The declarations are history: no role can rewrite them.
        rejected("update stir.agreement_obligation_event set note='x' where obligation_id=?", object);
        // Not even the table owner can: the append-only trigger does not depend on grants.
        jdbc.execute("reset role");
        rejected("update stir.agreement_obligation_event set note='x' where obligation_id=?", object);
        rejected("delete from stir.agreement_obligation_event where obligation_id=?", object);
        jdbc.execute("set local role idax_app");
    }

    @Test void oneSidedNonReceiptIsADisagreementAndOnlyAgreementMakesItFailed() {
        UUID tasks = obligation(service.view(ownerUser, agreement), "OWNER_TASKS").id();
        assertEquals("IN_DISAGREEMENT", obligation(service.declare(initiatorUser, agreement, tasks, "NOT_RECEIVED", null), "OWNER_TASKS").status());
        assertEquals("FAILED", obligation(service.declare(ownerUser, agreement, tasks, "NOT_PERFORMED", "Could not get the parts"), "OWNER_TASKS").status());
    }

    @Test void eachPartyDeclaresOnlyItsOwnSideAndStrangersSeeNothing() {
        var view = service.view(ownerUser, agreement);
        UUID object = obligation(view, "OBJECT").id(), economic = obligation(view, "ECONOMIC_EXCHANGE").id();
        assertEquals(400, status(assertThrows(ResponseStatusException.class, () -> service.declare(ownerUser, agreement, object, "CONFORMITY_CONFIRMED", null))));
        assertEquals(400, status(assertThrows(ResponseStatusException.class, () -> service.declare(initiatorUser, agreement, object, "PERFORMED", null))));
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.declare(initiatorUser, agreement, economic, "PERFORMED", null))));
        var stranger = user(UUID.randomUUID());
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.view(stranger, agreement))));
        // A platform superuser who is not a party is a stranger here too.
        var superuser = user(UUID.randomUUID()); when(superuser.isSuperuser()).thenReturn(true);
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.declare(superuser, agreement, object, "PERFORMED", null))));
        // Another tenant cannot see the rows at all.
        tenant(UUID.randomUUID());
        assertEquals(0, jdbc.queryForObject("select count(*) from stir.agreement_obligation", Integer.class));
    }

    @Test void economicObligationFollowsOsTrisCommitNotADeclaration() {
        assertEquals("PENDING", obligation(service.view(ownerUser, agreement), "ECONOMIC_EXCHANGE").status());
        jdbc.update("update stir.agreement set economic_phase='COMMITTED' where id=?", agreement);
        assertEquals("DONE", obligation(service.view(ownerUser, agreement), "ECONOMIC_EXCHANGE").status());
    }

    @Test void anAcceptedAmendmentReplacesNamedObligationsKeepsTheRestAndChains() {
        var view = service.view(ownerUser, agreement);
        UUID tasks = obligation(view, "OWNER_TASKS").id(), object = obligation(view, "OBJECT").id();
        var request = new FulfilmentService.AmendmentRequest(view.headDigest(), List.of(tasks),
            List.of(new FulfilmentService.NewObligation("OWNER_TASKS", "Fix the brakes and adjust the gears", LocalDate.parse("2026-12-08"), "ON_SITE")),
            "Gears need a second visit", Instant.now().plus(2, ChronoUnit.DAYS));
        var proposed = service.propose(ownerUser, agreement, request);
        assertEquals("PROPOSED", proposed.status());
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.accept(ownerUser, agreement, proposed.id()))));
        var accepted = service.accept(initiatorUser, agreement, proposed.id());
        assertEquals("ACCEPTED", accepted.status());
        assertEquals(1, accepted.sequenceNumber());
        assertEquals(snapshotDigest, accepted.content().path("previousDigest").asText());
        assertEquals(FulfilmentService.AMENDMENT_FORMAT, accepted.content().path("format").asText());
        assertEquals(CanonicalJson.sha256Hex(jdbc.queryForObject("select canonical_json from stir.agreement_amendment where id=?", String.class, proposed.id())
            .getBytes(StandardCharsets.UTF_8)), accepted.digestSha256());

        var after = service.view(ownerUser, agreement);
        assertEquals(accepted.digestSha256(), after.headDigest());
        var replaced = after.obligations().stream().filter(o -> o.id().equals(tasks)).findFirst().orElseThrow();
        assertEquals("SUPERSEDED", replaced.status());
        assertEquals(proposed.id(), replaced.supersededByAmendmentId());
        var replacement = obligation(after, "OWNER_TASKS");
        assertEquals("AMENDMENT", replacement.origin());
        assertEquals(owner, replacement.obligorUserId());
        assertEquals(LocalDate.parse("2026-12-08"), replacement.fulfilBy());
        assertEquals("PENDING", obligation(after, "OBJECT").status());
        assertEquals(object, obligation(after, "OBJECT").id());
        // A superseded obligation can no longer be declared on, and stays superseded.
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.declare(ownerUser, agreement, tasks, "PERFORMED", null))));
        rejected("update stir.agreement_obligation set status='PENDING', superseded_by_amendment_id=null where id=?", tasks);
        rejected("update stir.agreement_obligation set description='rewritten' where id=?", object);
        rejected("update stir.agreement_amendment set reason='rewritten' where id=?", proposed.id());
    }

    @Test void amendmentsOnAStaleBaseOrOnSettledOrEconomicObligationsAreRefused() {
        var view = service.view(ownerUser, agreement);
        UUID object = obligation(view, "OBJECT").id(), tasks = obligation(view, "OWNER_TASKS").id(), economic = obligation(view, "ECONOMIC_EXCHANGE").id();
        var add = List.of(new FulfilmentService.NewObligation("INITIATOR_TASKS", "Pick up the bike on Saturday", null, "PICKUP"));
        var first = service.propose(initiatorUser, agreement, new FulfilmentService.AmendmentRequest(view.headDigest(), List.of(), add, "Pickup instead", null));
        var second = service.propose(ownerUser, agreement, new FulfilmentService.AmendmentRequest(view.headDigest(), List.of(), add, "Same idea", null));
        service.accept(ownerUser, agreement, first.id());
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.accept(initiatorUser, agreement, second.id()))));
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.propose(ownerUser, agreement,
            new FulfilmentService.AmendmentRequest(view.headDigest(), List.of(), add, "Stale", null)))));
        String head = service.view(ownerUser, agreement).headDigest();
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.propose(ownerUser, agreement,
            new FulfilmentService.AmendmentRequest(head, List.of(economic), List.of(), "Lower the price", null)))));
        assertEquals(400, status(assertThrows(ResponseStatusException.class, () -> service.propose(ownerUser, agreement,
            new FulfilmentService.AmendmentRequest(head, List.of(), List.of(new FulfilmentService.NewObligation("ECONOMIC_EXCHANGE", "10 FFC", null, null)), "Price", null)))));
        service.declare(initiatorUser, agreement, object, "CONFORMITY_CONFIRMED", null);
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.propose(ownerUser, agreement,
            new FulfilmentService.AmendmentRequest(head, List.of(object), List.of(), "Undo", null)))));
        var rejected = service.reject(initiatorUser, agreement,
            service.propose(ownerUser, agreement, new FulfilmentService.AmendmentRequest(head, List.of(tasks), List.of(), "Drop the tasks", null)).id());
        assertEquals("REJECTED", rejected.status());
        assertEquals("PENDING", obligation(service.view(ownerUser, agreement), "OWNER_TASKS").status());
        // first (accepted), second (left proposed, now inapplicable) and the rejected one; refused proposals were never stored.
        assertEquals(List.of("ACCEPTED", "PROPOSED", "REJECTED"), service.amendments(ownerUser, agreement).stream().map(FulfilmentService.AmendmentView::status).toList());
    }

    @Test void historyIsDescribedFactsOptInAndNeverAScore() {
        jdbc.update("insert into stir.participant_profile(id,tenant_id,user_id,display_name,active,version,created_at,updated_at) values (?,?,?,'Owner',true,0,now(),now())",
            UUID.randomUUID(), tenant, owner);
        assertEquals("NOT_SHARED", service.history(initiatorUser, owner).status());
        assertEquals("NO_HISTORY_YET", service.history(ownerUser, owner).status());
        UUID object = obligation(service.view(ownerUser, agreement), "OBJECT").id();
        service.declare(ownerUser, agreement, object, "PERFORMED", null);
        assertEquals("NO_HISTORY_YET", service.history(ownerUser, owner).status()); // a claim alone is not history
        service.declare(initiatorUser, agreement, object, "CONFORMITY_CONFIRMED", null);
        var own = service.history(ownerUser, owner);
        assertEquals("HISTORY", own.status());
        assertEquals(List.of(new FulfilmentService.HistoryEntry("OBJECT", "ON_SITE", 1)), own.entries());
        assertEquals("NOT_SHARED", service.history(initiatorUser, owner).status());
        assertTrue(service.share(ownerUser, true).shared());
        assertEquals(own.entries(), service.history(initiatorUser, owner).entries());
        assertEquals("NOT_SHARED", service.history(ownerUser, initiator).status()); // no profile, nothing shared
        assertEquals(409, status(assertThrows(ResponseStatusException.class, () -> service.share(initiatorUser, true))));
    }

    @Test void executionAttemptsArePersistedWithTheirTypedOutcomeAndNeverRewritten() {
        attempts.failed(tenant, agreement, trade, initiator, ExecutionOutcome.EXPOSURE_REFUSAL, "CREDIT_FLOOR_EXCEEDED", 422, "Credit floor exceeded");
        var view = service.view(initiatorUser, agreement);
        assertEquals("EXPOSURE_REFUSAL", view.lastExecutionAttempt().outcome());
        assertEquals("CREDIT_FLOOR_EXCEEDED", view.lastExecutionAttempt().ostrisCode());
        assertEquals("PENDING", obligation(view, "ECONOMIC_EXCHANGE").status()); // a refusal is not a failure
        rejected("update stir.economic_execution_attempt set outcome='COMMITTED' where agreement_id=?", agreement);
        rejected("delete from stir.economic_execution_attempt where agreement_id=?", agreement);
    }
}
