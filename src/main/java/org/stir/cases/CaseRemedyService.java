package org.stir.cases;

import es.idynamicsax.idax.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.OstrisClient;
import org.stir.economic.StirOstrisException;
import org.stir.notification.NotificationService;
import static org.springframework.http.HttpStatus.*;
import static org.stir.cases.CaseReviewService.actor;
import static org.stir.cases.CaseReviewService.tenant;
import static org.stir.cases.CaseService.instant;
import static org.stir.cases.CaseService.uuid;

/**
 * Repairing the economic effect of a final finding (AGREEMENT_CASES.md): an adjudicated osTRIS
 * REVERSAL of the committed exchange, with FINAL_DISPUTE_RESOLUTION as its basis. Never an
 * ordinary EXCHANGE, never signed by STIR: each reviewer signs the governed payload with their own
 * device key, STIR relays the signature, and osTRIS's commit gate alone decides whether the
 * authority's threshold, the basis finality and every signature hold at the commit sequence.
 * The original exchange stays COMMITTED; the reversal is a second, linked journal entry.
 */
@Service @Transactional
public class CaseRemedyService {
    private final JdbcTemplate jdbc;
    private final OstrisClient ostris;
    private final CaseService cases;
    private final NotificationService notifications;

    public CaseRemedyService(JdbcTemplate jdbc, OstrisClient ostris, CaseService cases, NotificationService notifications) {
        this.jdbc = jdbc; this.ostris = ostris; this.cases = cases; this.notifications = notifications;
    }

    public CaseService.CaseView prepare(CurrentUser user, UUID caseId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = cases.load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var findings = jdbc.queryForList("select id, ostris_basis_id from stir.agreement_case_decision where tenant_id=? and case_id=? "
            + "and decision_type='FINDING' and status='FINAL' and economic_correction='FULL_REVERSAL'", tenant, caseId);
        if (findings.isEmpty()) throw new ResponseStatusException(CONFLICT, "Only a final finding that orders a full reversal can be executed");
        var finding = findings.getFirst();
        if (!jdbc.queryForList("select 1 from stir.agreement_case_remedy where tenant_id=? and decision_id=?", tenant, finding.get("id")).isEmpty())
            throw new ResponseStatusException(CONFLICT, "This correction was already prepared");
        var trades = jdbc.queryForList("select community_id,unit_id,transaction_id,payer_account_id,payee_account_id,amount,execution_state "
            + "from stir.trade where tenant_id=? and agreement_id=?", tenant, c.agreementId());
        if (trades.isEmpty() || !"COMMITTED".equals(trades.getFirst().get("execution_state")))
            throw new ResponseStatusException(CONFLICT, "There is no committed economic exchange on this agreement to reverse");
        var trade = trades.getFirst();
        var settings = jdbc.queryForMap("select ostris_authority_id, ostris_authority_version from stir.case_review_settings where tenant_id=?", tenant);
        UUID payer = (UUID) trade.get("payer_account_id"), payee = (UUID) trade.get("payee_account_id");
        String amount = ((java.math.BigDecimal) trade.get("amount")).toBigInteger().toString();
        UUID transactionId = CaseService.uuidV7();
        UUID original = (UUID) trade.get("transaction_id"), basis = (UUID) finding.get("ostris_basis_id");
        UUID remedyId = UUID.randomUUID();
        List<UUID> covered = List.of(payer, payee).stream().sorted().toList();
        try {
            jdbc.update("insert into stir.agreement_case_remedy(id,tenant_id,case_id,decision_id,agreement_id,kind,status,ostris_transaction_id,"
                    + "reverses_transaction_id,ostris_basis_id,ostris_authority_id,ostris_authority_version,covered_account_ids,prepared_by_user_id,"
                    + "created_at,updated_at) values (?,?,?,?,?,'ECONOMIC_REVERSAL','AWAITING_AUTHORITY',?,?,?,?,?,?,?,clock_timestamp(),clock_timestamp())",
                remedyId, tenant, caseId, finding.get("id"), c.agreementId(), transactionId, original, basis,
                settings.get("ostris_authority_id"), settings.get("ostris_authority_version"),
                String.join(",", covered.stream().map(UUID::toString).toList()), actor);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "This correction was already prepared", ex);
        }
        // Exactly the original's entries negated, in the original order (payer then payee).
        ostris.proposeGoverned((UUID) trade.get("community_id"), (UUID) trade.get("unit_id"), transactionId, "REVERSAL",
            List.of(new OstrisClient.Entry(payer.toString(), amount), new OstrisClient.Entry(payee.toString(), "-" + amount)),
            Map.of("reversesTransactionId", original.toString()), CaseService.DISPUTE_BASIS, basis);
        cases.event(tenant, caseId, actor, "REVIEWER", "REMEDY_PREPARED", "PARTIES", remedyId, null);
        cases.notifyParties(tenant, c, actor);
        return cases.view(user, caseId);
    }

    @Transactional(readOnly = true)
    public OstrisClient.GovernanceSigningPayload signingPayload(CurrentUser user, UUID caseId, UUID remedyId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = cases.load(tenant, caseId, false);
        requireReviewer(tenant, c, actor);
        var r = remedy(tenant, caseId, remedyId, false);
        return ostris.governancePayload(r.ostrisTransactionId(), r.authorityId(), r.authorityVersion(), CaseService.DISPUTE_BASIS,
            r.basisId(), r.coveredAccountIds());
    }

    public CaseService.CaseView sign(CurrentUser user, UUID caseId, UUID remedyId, SignatureRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = cases.load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var r = remedy(tenant, caseId, remedyId, true);
        if ("EXECUTED".equals(r.status())) throw new ResponseStatusException(CONFLICT, "This correction was already executed");
        if (request == null || request.credentialId() == null || request.signatureBase64url() == null || request.signatureBase64url().isBlank())
            throw new ResponseStatusException(BAD_REQUEST, "Your device credential and signature are required");
        ostris.governanceAuthorize(r.ostrisTransactionId(), r.authorityId(), r.authorityVersion(), CaseService.DISPUTE_BASIS, r.basisId(),
            r.coveredAccountIds(), request.credentialId(), request.signatureBase64url());
        cases.event(tenant, caseId, actor, "REVIEWER", "REMEDY_SIGNED", "PARTIES", remedyId, null);
        var settings = jdbc.queryForMap("select authority_threshold, ostris_authority_id from stir.case_review_settings where tenant_id=?", tenant);
        Integer signers = jdbc.queryForObject("select count(distinct actor_user_id) from stir.agreement_case_event where tenant_id=? and case_id=? "
            + "and type='REMEDY_SIGNED' and ref_id=?", Integer.class, tenant, caseId, remedyId);
        boolean thresholdKnown = r.authorityId().equals(settings.get("ostris_authority_id"));
        if (!thresholdKnown || signers != null && signers >= (Integer) settings.get("authority_threshold")) tryCommit(tenant, c, r, actor);
        return cases.view(user, caseId);
    }

    /** Retries the commit (e.g. after an unknown outcome). osTRIS's commit is idempotent by transaction id. */
    public CaseService.CaseView commit(CurrentUser user, UUID caseId, UUID remedyId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = cases.load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var r = remedy(tenant, caseId, remedyId, true);
        if (!"EXECUTED".equals(r.status())) tryCommit(tenant, c, r, actor);
        return cases.view(user, caseId);
    }

    private void tryCommit(UUID tenant, CaseService.CaseRow c, RemedyView r, UUID actor) {
        try {
            var receipt = ostris.commit(r.ostrisTransactionId());
            jdbc.update("update stir.agreement_case_remedy set status='EXECUTED', committed_sequence=?, protocol_digest=?, committed_at=?, "
                    + "last_error_code=null, updated_at=clock_timestamp() where tenant_id=? and id=?",
                receipt.communitySequence(), receipt.protocolDigest(), Timestamp.from(receipt.committedAt()), tenant, r.id());
            cases.event(tenant, c.id(), actor, "REVIEWER", "REMEDY_EXECUTED", "PARTIES", r.id(), null);
            for (UUID party : List.of(c.claimant(), c.respondent()))
                notifications.create(tenant, party, "CASE_UPDATED", "AGREEMENT", c.agreementId());
        } catch (StirOstrisException ex) {
            // Recorded, never hidden: the correction stays prepared and can be completed or retried.
            jdbc.update("update stir.agreement_case_remedy set last_error_code=?, updated_at=clock_timestamp() where tenant_id=? and id=?",
                ex.code == null ? "UNKNOWN" : ex.code.length() > 80 ? ex.code.substring(0, 80) : ex.code, tenant, r.id());
            cases.event(tenant, c.id(), actor, "REVIEWER", "REMEDY_ATTEMPT_FAILED", "PARTIES", r.id(), ex.code);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            jdbc.update("update stir.agreement_case_remedy set last_error_code='OSTRIS_UNREACHABLE', updated_at=clock_timestamp() where tenant_id=? and id=?",
                tenant, r.id());
            cases.event(tenant, c.id(), actor, "REVIEWER", "REMEDY_ATTEMPT_FAILED", "PARTIES", r.id(), "OSTRIS_UNREACHABLE");
        }
    }

    private void requireReviewer(UUID tenant, CaseService.CaseRow c, UUID actor) {
        if (!"REVIEWER".equals(cases.role(tenant, c, actor)))
            throw new ResponseStatusException(FORBIDDEN, "Only a community reviewer who is not a party can do this");
    }

    private RemedyView remedy(UUID tenant, UUID caseId, UUID remedyId, boolean lock) {
        var rows = remedies(jdbc, tenant, caseId).stream().filter(r -> r.id().equals(remedyId)).toList();
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Correction not found");
        if (lock) jdbc.queryForList("select id from stir.agreement_case_remedy where tenant_id=? and id=? for update", tenant, remedyId);
        return lock ? remedies(jdbc, tenant, caseId).stream().filter(r -> r.id().equals(remedyId)).findFirst().orElseThrow() : rows.getFirst();
    }

    static List<RemedyView> remedies(JdbcTemplate jdbc, UUID tenant, UUID caseId) {
        return jdbc.query("select id,decision_id,kind,status,ostris_transaction_id,reverses_transaction_id,ostris_basis_id,ostris_authority_id,"
                + "ostris_authority_version,covered_account_ids,committed_sequence,protocol_digest,committed_at,last_error_code,prepared_by_user_id,created_at "
                + "from stir.agreement_case_remedy where tenant_id=? and case_id=? order by created_at",
            (rs, i) -> new RemedyView(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(4), uuid(rs, 5), uuid(rs, 6), uuid(rs, 7), uuid(rs, 8),
                rs.getInt(9), Arrays.stream(rs.getString(10).split(",")).map(UUID::fromString).toList(), (Long) rs.getObject(11), rs.getString(12),
                instant(rs, 13), rs.getString(14), uuid(rs, 15), instant(rs, 16),
                jdbc.queryForList("select distinct actor_user_id from stir.agreement_case_event where tenant_id=? and case_id=? and type='REMEDY_SIGNED' and ref_id=?",
                    UUID.class, tenant, caseId, uuid(rs, 1))),
            tenant, caseId);
    }

    public record SignatureRequest(UUID credentialId, String signatureBase64url) {}
    public record RemedyView(UUID id, UUID decisionId, String kind, String status, UUID ostrisTransactionId, UUID reversesTransactionId,
                             UUID basisId, UUID authorityId, int authorityVersion, List<UUID> coveredAccountIds, Long committedSequence,
                             String protocolDigest, Instant committedAt, String lastErrorCode, UUID preparedByUserId, Instant createdAt,
                             List<UUID> signedBy) {}
}
