package org.stir.fulfilment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.ExecutionAttemptRecorder;
import org.stir.negotiation.CanonicalJson;
import org.stir.notification.NotificationService;
import static org.springframework.http.HttpStatus.*;

/**
 * Agreement fulfilment (AGREEMENT_FULFILMENT.md): obligations derived from the frozen snapshot,
 * typed append-only declarations, amendments that name what they replace and chain to the
 * snapshot, and the opt-in contextual history. The snapshot itself is never touched. Only the two
 * parties of an Agreement can see or act on any of it; everyone else, platform superuser
 * included, gets the same 404 as for the Agreement itself.
 */
@Service @Transactional
public class FulfilmentService {
    public static final String AMENDMENT_FORMAT = "STIR-AGREEMENT-AMENDMENT-JCS-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NEW_OBLIGATIONS = 10;

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final ExecutionAttemptRecorder attempts;

    public FulfilmentService(JdbcTemplate jdbc, NotificationService notifications, ExecutionAttemptRecorder attempts) {
        this.jdbc = jdbc; this.notifications = notifications; this.attempts = attempts;
    }

    // ----- context -------------------------------------------------------------------------------

    UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    static UUID actor(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    record AgreementRow(UUID id, UUID initiatorId, UUID ownerId, UUID payerUserId, UUID payeeUserId, String economicPhase) {
        boolean party(UUID user) { return initiatorId.equals(user) || ownerId.equals(user); }
        UUID other(UUID user) { return initiatorId.equals(user) ? ownerId : initiatorId; }
    }

    private AgreementRow partyAgreement(UUID tenant, UUID agreementId, UUID actor, boolean lock) {
        var rows = jdbc.query("select id,initiator_id,owner_id,payer_user_id,payee_user_id,economic_phase from stir.agreement "
                + "where tenant_id=? and id=?" + (lock ? " for update" : ""),
            (rs, i) -> new AgreementRow(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5), rs.getString(6)),
            tenant, agreementId);
        if (rows.isEmpty() || !rows.getFirst().party(actor)) throw new ResponseStatusException(NOT_FOUND, "Agreement not found");
        return rows.getFirst();
    }

    record SnapshotRow(String digest, JsonNode fields) {}
    private SnapshotRow snapshot(UUID tenant, UUID agreementId) {
        var rows = jdbc.query("select digest_sha256, canonical_json from stir.agreement_snapshot where tenant_id=? and agreement_id=?",
            (rs, i) -> new SnapshotRow(rs.getString(1), readTree(rs.getString(2))), tenant, agreementId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Agreement not found");
        return rows.getFirst();
    }

    /** The digest every new amendment must build on: the last accepted amendment's, else the snapshot's. */
    private String headDigest(UUID tenant, UUID agreementId, String snapshotDigest) {
        var rows = jdbc.queryForList("select digest_sha256 from stir.agreement_amendment where tenant_id=? and agreement_id=? "
            + "and status='ACCEPTED' order by sequence_number desc limit 1", String.class, tenant, agreementId);
        return rows.isEmpty() ? snapshotDigest : rows.getFirst();
    }

    // ----- obligations ---------------------------------------------------------------------------

    /**
     * Derives the snapshot's obligations once, deterministically. Agreements accepted before this
     * capability existed get theirs the first time they are read. Concurrent first readers are
     * resolved by the database (one row per kind), never by a check-then-insert race.
     */
    void ensureObligations(UUID tenant, AgreementRow agreement) {
        Integer existing = jdbc.queryForObject("select count(*) from stir.agreement_obligation where tenant_id=? and agreement_id=? "
            + "and origin='SNAPSHOT'", Integer.class, tenant, agreement.id());
        if (existing != null && existing > 0) return;
        JsonNode s = snapshot(tenant, agreement.id()).fields();
        boolean wanted = "WANTED".equals(text(s, "listingDirection"));
        UUID provider = wanted ? agreement.initiatorId() : agreement.ownerId();
        UUID receiver = wanted ? agreement.ownerId() : agreement.initiatorId();
        LocalDate fulfilBy = text(s, "fulfilBy") == null ? null : LocalDate.parse(text(s, "fulfilBy"));
        String mode = text(s, "fulfilmentMode");
        insertSnapshotObligation(tenant, agreement.id(), "OBJECT", provider, receiver, text(s, "scope"), fulfilBy, mode);
        if (text(s, "ownerTasks") != null)
            insertSnapshotObligation(tenant, agreement.id(), "OWNER_TASKS", agreement.ownerId(), agreement.initiatorId(), text(s, "ownerTasks"), fulfilBy, mode);
        if (text(s, "initiatorTasks") != null)
            insertSnapshotObligation(tenant, agreement.id(), "INITIATOR_TASKS", agreement.initiatorId(), agreement.ownerId(), text(s, "initiatorTasks"), fulfilBy, mode);
        if (agreement.payerUserId() != null && agreement.payeeUserId() != null) {
            String amount = text(s, "proposedAmount"), unit = text(s, "proposedUnitRef");
            insertSnapshotObligation(tenant, agreement.id(), "ECONOMIC_EXCHANGE", agreement.payerUserId(), agreement.payeeUserId(),
                amount == null ? null : unit == null ? amount : amount + " " + unit, null, null);
        }
    }
    private void insertSnapshotObligation(UUID tenant, UUID agreementId, String kind, UUID obligor, UUID beneficiary,
                                          String description, LocalDate fulfilBy, String mode) {
        jdbc.update("insert into stir.agreement_obligation(id,tenant_id,agreement_id,kind,origin,obligor_user_id,beneficiary_user_id,"
                + "description,fulfil_by,fulfilment_mode,status,created_at,updated_at) values (?,?,?,?,'SNAPSHOT',?,?,?,?,?,'PENDING',now(),now()) "
                + "on conflict (agreement_id, kind) where origin='SNAPSHOT' do nothing",
            UUID.randomUUID(), tenant, agreementId, kind, obligor, beneficiary, description,
            fulfilBy == null ? null : Date.valueOf(fulfilBy), mode);
    }

    /** The economic obligation is never declared by hand: osTRIS's commit is its only evidence. */
    private void syncEconomicStatus(UUID tenant, AgreementRow agreement) {
        String derived = "COMMITTED".equals(agreement.economicPhase()) ? "DONE" : "PENDING";
        jdbc.update("update stir.agreement_obligation set status=?, updated_at=now() where tenant_id=? and agreement_id=? "
            + "and kind='ECONOMIC_EXCHANGE' and status<>?", derived, tenant, agreement.id(), derived);
    }

    public FulfilmentView view(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant(), actor = actor(user);
        var agreement = partyAgreement(tenant, agreementId, actor, false);
        ensureObligations(tenant, agreement);
        syncEconomicStatus(tenant, agreement);
        var snapshot = snapshot(tenant, agreementId);
        var events = new HashMap<UUID, List<EventView>>();
        jdbc.query("select id,obligation_id,actor_user_id,actor_role,type,note,created_at from stir.agreement_obligation_event "
                + "where tenant_id=? and agreement_id=? order by created_at, id",
            rs -> { events.computeIfAbsent(uuid(rs, 2), k -> new ArrayList<>()).add(new EventView(uuid(rs, 1), uuid(rs, 3),
                rs.getString(4), rs.getString(5), rs.getString(6), instant(rs, 7))); },
            tenant, agreementId);
        var obligations = jdbc.query("select id,kind,origin,amendment_id,obligor_user_id,beneficiary_user_id,description,fulfil_by,"
                + "fulfilment_mode,status,superseded_by_amendment_id,created_at from stir.agreement_obligation "
                + "where tenant_id=? and agreement_id=? order by created_at, case kind when 'OBJECT' then 0 when 'OWNER_TASKS' then 1 "
                + "when 'INITIATOR_TASKS' then 2 else 3 end, id",
            (rs, i) -> obligation(rs, actor, events.getOrDefault(uuid(rs, 1), List.of())), tenant, agreementId);
        var attemptList = attempts.list(tenant, agreementId);
        // Cases about this Agreement and the corrections they produced (AGREEMENT_CASES.md), so a board can
        // show each next to its obligation. Never folded into an obligation's own status.
        var cases = jdbc.query("select id,obligation_id,claim,status from stir.agreement_case where tenant_id=? and agreement_id=? order by created_at",
            (rs, i) -> new CaseRef(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(4)), tenant, agreementId);
        var corrections = jdbc.query("select r.id,r.case_id,r.kind,r.status,r.ostris_transaction_id,r.committed_sequence,r.committed_at "
                + "from stir.agreement_case_remedy r where r.tenant_id=? and r.agreement_id=? order by r.created_at",
            (rs, i) -> new CorrectionRef(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(4), uuid(rs, 5), (Long) rs.getObject(6), instant(rs, 7)),
            tenant, agreementId);
        var containment = jdbc.query("select case_id, until_at from stir.agreement_case_containment where tenant_id=? and agreement_id=? "
                + "and lifted_at is null and until_at > now() order by applied_at limit 1",
            (rs, i) -> new ContainmentRef(uuid(rs, 1), instant(rs, 2)), tenant, agreementId);
        return new FulfilmentView(agreementId, snapshot.digest(), headDigest(tenant, agreementId, snapshot.digest()),
            agreement.economicPhase(), obligations, attemptList.isEmpty() ? null : attemptList.getLast(),
            cases, corrections, containment.isEmpty() ? null : containment.getFirst());
    }

    private static ObligationView obligation(ResultSet rs, UUID actor, List<EventView> events) throws SQLException {
        UUID obligor = uuid(rs, 5), beneficiary = uuid(rs, 6);
        Date fulfilBy = rs.getDate(8);
        return new ObligationView(uuid(rs, 1), rs.getString(2), rs.getString(3), uuid(rs, 4), obligor, beneficiary,
            rs.getString(7), fulfilBy == null ? null : fulfilBy.toLocalDate(), rs.getString(9), rs.getString(10), uuid(rs, 11),
            actor.equals(obligor) ? "OBLIGOR" : actor.equals(beneficiary) ? "BENEFICIARY" : null, events);
    }

    public FulfilmentView declare(CurrentUser user, UUID agreementId, UUID obligationId, String type, String note) {
        UUID tenant = tenant(), actor = actor(user);
        var agreement = partyAgreement(tenant, agreementId, actor, false);
        ensureObligations(tenant, agreement);
        var rows = jdbc.queryForList("select kind,obligor_user_id,beneficiary_user_id,status from stir.agreement_obligation "
            + "where tenant_id=? and agreement_id=? and id=? for update", tenant, agreementId, obligationId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Obligation not found");
        var row = rows.getFirst();
        if (!FulfilmentRules.DECLARABLE_KINDS.contains((String) row.get("kind")))
            throw new ResponseStatusException(CONFLICT, "The economic exchange is evidenced by its osTRIS commit, not by a declaration");
        if ("SUPERSEDED".equals(row.get("status")))
            throw new ResponseStatusException(CONFLICT, "This obligation was replaced by an accepted amendment");
        String role = actor.equals(row.get("obligor_user_id")) ? "OBLIGOR" : actor.equals(row.get("beneficiary_user_id")) ? "BENEFICIARY" : null;
        if (role == null) throw new ResponseStatusException(NOT_FOUND, "Obligation not found");
        if (type == null || !("OBLIGOR".equals(role) ? FulfilmentRules.OBLIGOR_TYPES : FulfilmentRules.BENEFICIARY_TYPES).contains(type))
            throw new ResponseStatusException(BAD_REQUEST, "OBLIGOR".equals(role)
                ? "As the party who owes this, you can declare PERFORMED, PARTIALLY_PERFORMED or NOT_PERFORMED"
                : "As the party this is owed to, you can declare CONFORMITY_CONFIRMED, PARTIAL_RECEIVED, NONCONFORMITY_REPORTED or NOT_RECEIVED");
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (FulfilmentRules.NOTE_REQUIRED.contains(type) && trimmed == null)
            throw new ResponseStatusException(BAD_REQUEST, "Describe what was and was not done");
        if (trimmed != null && trimmed.length() > 2000) throw new ResponseStatusException(BAD_REQUEST, "The note is limited to 2000 characters");
        jdbc.update("insert into stir.agreement_obligation_event(id,tenant_id,agreement_id,obligation_id,actor_user_id,actor_role,type,note,created_at) "
            + "values (?,?,?,?,?,?,?,?,clock_timestamp())", UUID.randomUUID(), tenant, agreementId, obligationId, actor, role, type, trimmed);
        String obligorLatest = latest(tenant, obligationId, "OBLIGOR"), beneficiaryLatest = latest(tenant, obligationId, "BENEFICIARY");
        jdbc.update("update stir.agreement_obligation set status=?, updated_at=now() where tenant_id=? and id=?",
            FulfilmentRules.status(obligorLatest, beneficiaryLatest), tenant, obligationId);
        notifications.create(tenant, agreement.other(actor), "OBLIGATION_UPDATED", "AGREEMENT", agreementId);
        return view(user, agreementId);
    }
    private String latest(UUID tenant, UUID obligationId, String role) {
        var rows = jdbc.queryForList("select type from stir.agreement_obligation_event where tenant_id=? and obligation_id=? and actor_role=? "
            + "order by created_at desc, id desc limit 1", String.class, tenant, obligationId, role);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ----- amendments ----------------------------------------------------------------------------

    public AmendmentView propose(CurrentUser user, UUID agreementId, AmendmentRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var agreement = partyAgreement(tenant, agreementId, actor, false);
        ensureObligations(tenant, agreement);
        org.stir.cases.CaseService.requireNotContained(jdbc, tenant, agreementId);
        if (request == null) throw new ResponseStatusException(BAD_REQUEST, "Amendment required");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 2000) throw new ResponseStatusException(BAD_REQUEST, "Say why the agreement changes (up to 2000 characters)");
        List<UUID> replaces = request.replaces() == null ? List.of() : request.replaces().stream().distinct().sorted().toList();
        List<NewObligation> added = request.obligations() == null ? List.of() : request.obligations();
        if (replaces.isEmpty() && added.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "An amendment must replace or add at least one obligation");
        if (added.size() > MAX_NEW_OBLIGATIONS) throw new ResponseStatusException(BAD_REQUEST, "At most " + MAX_NEW_OBLIGATIONS + " new obligations per amendment");
        for (var o : added) validate(o);
        if (request.validUntil() != null && !request.validUntil().isAfter(Instant.now()))
            throw new ResponseStatusException(BAD_REQUEST, "validUntil must be in the future");
        var snapshot = snapshot(tenant, agreementId);
        String head = headDigest(tenant, agreementId, snapshot.digest());
        if (!head.equals(request.baseDigest()))
            throw new ResponseStatusException(CONFLICT, "The agreement has changed since you read it; reload before proposing an amendment");
        requireReplaceable(tenant, agreementId, replaces);
        var proposal = new LinkedHashMap<String, Object>();
        proposal.put("replaces", replaces.stream().map(UUID::toString).toList());
        proposal.put("obligations", added.stream().map(FulfilmentService::canonical).toList());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stir.agreement_amendment(id,tenant_id,agreement_id,proposer_user_id,base_digest,proposal_json,reason,valid_until,status,created_at) "
                + "values (?,?,?,?,?,?,?,?,'PROPOSED',clock_timestamp())", id, tenant, agreementId, actor, head,
            new String(CanonicalJson.canonicalBytes(proposal), StandardCharsets.UTF_8), reason,
            request.validUntil() == null ? null : Timestamp.from(request.validUntil()));
        notifications.create(tenant, agreement.other(actor), "AMENDMENT_PROPOSED", "AGREEMENT", agreementId);
        return amendment(tenant, agreementId, id);
    }

    private static void validate(NewObligation o) {
        if (o == null || o.kind() == null || !FulfilmentRules.DECLARABLE_KINDS.contains(o.kind()))
            throw new ResponseStatusException(BAD_REQUEST, "A new obligation is OBJECT, OWNER_TASKS or INITIATOR_TASKS; the economic exchange cannot be amended here");
        if (o.description() == null || o.description().isBlank() || o.description().length() > 4000)
            throw new ResponseStatusException(BAD_REQUEST, "Describe each new obligation (up to 4000 characters)");
        if (o.fulfilmentMode() != null && !FulfilmentRules.FULFILMENT_MODES.contains(o.fulfilmentMode()))
            throw new ResponseStatusException(BAD_REQUEST, "Unknown fulfilment mode " + o.fulfilmentMode());
    }
    private static Map<String, Object> canonical(NewObligation o) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", o.kind()); m.put("description", o.description().trim());
        m.put("fulfilBy", o.fulfilBy() == null ? null : o.fulfilBy().toString()); m.put("fulfilmentMode", o.fulfilmentMode());
        return m;
    }

    private void requireReplaceable(UUID tenant, UUID agreementId, List<UUID> replaces) {
        for (UUID id : replaces) {
            var rows = jdbc.queryForList("select kind,status from stir.agreement_obligation where tenant_id=? and agreement_id=? and id=?",
                tenant, agreementId, id);
            if (rows.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Obligation " + id + " is not part of this agreement");
            if ("ECONOMIC_EXCHANGE".equals(rows.getFirst().get("kind")))
                throw new ResponseStatusException(CONFLICT, "The economic exchange cannot be amended; correcting it is a separate, governed act");
            if (FulfilmentRules.SETTLED.contains((String) rows.getFirst().get("status")))
                throw new ResponseStatusException(CONFLICT, "Obligation " + id + " is already " + rows.getFirst().get("status") + " and cannot be replaced");
        }
    }

    public AmendmentView accept(CurrentUser user, UUID agreementId, UUID amendmentId) {
        UUID tenant = tenant(), actor = actor(user);
        // Serialize amendment decisions on one Agreement; the unique index is the final word.
        var agreement = partyAgreement(tenant, agreementId, actor, true);
        var proposal = proposed(tenant, agreementId, amendmentId);
        org.stir.cases.CaseService.requireNotContained(jdbc, tenant, agreementId);
        if (actor.equals(proposal.get("proposer_user_id"))) throw new ResponseStatusException(CONFLICT, "Only the other party may accept this amendment");
        var validUntil = (Timestamp) proposal.get("valid_until");
        if (validUntil != null && Instant.now().isAfter(validUntil.toInstant()))
            throw new ResponseStatusException(CONFLICT, "This amendment could only be accepted until " + validUntil.toInstant() + "; ask for a new one");
        var snapshot = snapshot(tenant, agreementId);
        String base = (String) proposal.get("base_digest");
        if (!base.equals(headDigest(tenant, agreementId, snapshot.digest())))
            throw new ResponseStatusException(CONFLICT, "Another amendment was accepted after this one was proposed; it no longer applies");
        JsonNode content = readTree((String) proposal.get("proposal_json"));
        List<UUID> replaces = new ArrayList<>();
        content.path("replaces").forEach(n -> replaces.add(UUID.fromString(n.asText())));
        requireReplaceable(tenant, agreementId, replaces);
        Integer accepted = jdbc.queryForObject("select count(*) from stir.agreement_amendment where tenant_id=? and agreement_id=? and status='ACCEPTED'",
            Integer.class, tenant, agreementId);
        int sequence = (accepted == null ? 0 : accepted) + 1;
        Instant acceptedAt = Instant.now();

        var created = new ArrayList<Map<String, Object>>();
        for (JsonNode o : content.path("obligations")) {
            String kind = o.path("kind").asText();
            UUID obligor = switch (kind) {
                case "OWNER_TASKS" -> agreement.ownerId();
                case "INITIATOR_TASKS" -> agreement.initiatorId();
                default -> "WANTED".equals(text(snapshot.fields(), "listingDirection")) ? agreement.initiatorId() : agreement.ownerId();
            };
            var m = new LinkedHashMap<String, Object>();
            m.put("id", UUID.randomUUID().toString()); m.put("kind", kind);
            m.put("obligorUserId", obligor.toString()); m.put("beneficiaryUserId", agreement.other(obligor).toString());
            m.put("description", text(o, "description")); m.put("fulfilBy", text(o, "fulfilBy")); m.put("fulfilmentMode", text(o, "fulfilmentMode"));
            created.add(m);
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("format", AMENDMENT_FORMAT);
        fields.put("agreementId", agreementId.toString());
        fields.put("amendmentId", amendmentId.toString());
        fields.put("sequence", sequence);
        fields.put("snapshotDigest", snapshot.digest());
        fields.put("previousDigest", base);
        fields.put("proposerUserId", proposal.get("proposer_user_id").toString());
        fields.put("acceptedByUserId", actor.toString());
        fields.put("reason", proposal.get("reason"));
        fields.put("replaces", replaces.stream().map(UUID::toString).toList());
        fields.put("obligations", created);
        fields.put("acceptedAt", acceptedAt.toString());
        byte[] bytes = CanonicalJson.canonicalBytes(fields);
        try {
            jdbc.update("update stir.agreement_amendment set status='ACCEPTED', decided_by_user_id=?, decided_at=?, sequence_number=?, "
                    + "canonical_json=?, digest_sha256=? where tenant_id=? and id=?",
                actor, Timestamp.from(acceptedAt), sequence, new String(bytes, StandardCharsets.UTF_8), CanonicalJson.sha256Hex(bytes), tenant, amendmentId);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "Another amendment was accepted first; this one no longer applies", ex);
        }
        for (UUID id : replaces)
            jdbc.update("update stir.agreement_obligation set status='SUPERSEDED', superseded_by_amendment_id=?, updated_at=now() where tenant_id=? and id=?",
                amendmentId, tenant, id);
        for (var m : created)
            jdbc.update("insert into stir.agreement_obligation(id,tenant_id,agreement_id,kind,origin,amendment_id,obligor_user_id,beneficiary_user_id,"
                    + "description,fulfil_by,fulfilment_mode,status,created_at,updated_at) values (?,?,?,?,'AMENDMENT',?,?,?,?,?,?,'PENDING',now(),now())",
                UUID.fromString((String) m.get("id")), tenant, agreementId, m.get("kind"), amendmentId,
                UUID.fromString((String) m.get("obligorUserId")), UUID.fromString((String) m.get("beneficiaryUserId")), m.get("description"),
                m.get("fulfilBy") == null ? null : Date.valueOf((String) m.get("fulfilBy")), m.get("fulfilmentMode"));
        notifications.create(tenant, agreement.other(actor), "AMENDMENT_ACCEPTED", "AGREEMENT", agreementId);
        return amendment(tenant, agreementId, amendmentId);
    }

    public AmendmentView reject(CurrentUser user, UUID agreementId, UUID amendmentId) {
        UUID tenant = tenant(), actor = actor(user);
        var agreement = partyAgreement(tenant, agreementId, actor, true);
        proposed(tenant, agreementId, amendmentId);
        jdbc.update("update stir.agreement_amendment set status='REJECTED', decided_by_user_id=?, decided_at=now() where tenant_id=? and id=?",
            actor, tenant, amendmentId);
        notifications.create(tenant, agreement.other(actor), "AMENDMENT_REJECTED", "AGREEMENT", agreementId);
        return amendment(tenant, agreementId, amendmentId);
    }

    private Map<String, Object> proposed(UUID tenant, UUID agreementId, UUID amendmentId) {
        var rows = jdbc.queryForList("select * from stir.agreement_amendment where tenant_id=? and agreement_id=? and id=? for update",
            tenant, agreementId, amendmentId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Amendment not found");
        if (!"PROPOSED".equals(rows.getFirst().get("status"))) throw new ResponseStatusException(CONFLICT, "This amendment has already been decided");
        return rows.getFirst();
    }

    public List<AmendmentView> amendments(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant(), actor = actor(user);
        partyAgreement(tenant, agreementId, actor, false);
        return jdbc.query(AMENDMENT_SELECT + " order by created_at, id", (rs, i) -> amendmentRow(rs), tenant, agreementId);
    }
    private AmendmentView amendment(UUID tenant, UUID agreementId, UUID id) {
        return jdbc.query(AMENDMENT_SELECT + " and id=?", (rs, i) -> amendmentRow(rs), tenant, agreementId, id).getFirst();
    }
    private static final String AMENDMENT_SELECT = "select id,proposer_user_id,base_digest,proposal_json,reason,valid_until,status,decided_by_user_id,"
        + "decided_at,sequence_number,digest_sha256,created_at,canonical_json from stir.agreement_amendment where tenant_id=? and agreement_id=?";
    private static AmendmentView amendmentRow(ResultSet rs) throws SQLException {
        String status = rs.getString(7);
        Instant validUntil = instant(rs, 6);
        // An unanswered proposal past its validity is shown as expired; it can no longer be accepted.
        if ("PROPOSED".equals(status) && validUntil != null && Instant.now().isAfter(validUntil)) status = "EXPIRED";
        String accepted = rs.getString(13);
        JsonNode proposal = readTree(rs.getString(4));
        return new AmendmentView(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(5), validUntil, status, uuid(rs, 8),
            instant(rs, 9), (Integer) rs.getObject(10), rs.getString(11), instant(rs, 12),
            accepted == null ? proposal : readTree(accepted));
    }

    // ----- contextual history --------------------------------------------------------------------

    /**
     * Described facts, never a score (R16): how many obligations of each kind this participant
     * fulfilled to the other party's confirmed conformity. No amounts, counterparties, dates or
     * ratios, and nothing about what did not happen. Off unless the participant shares it; a
     * participant always sees their own.
     */
    @Transactional(readOnly = true)
    public HistoryView history(CurrentUser user, UUID userId) {
        UUID tenant = tenant(), viewer = actor(user);
        var shared = jdbc.queryForList("select share_fulfilment_history from stir.participant_profile where tenant_id=? and user_id=?",
            Boolean.class, tenant, userId);
        boolean sharing = !shared.isEmpty() && Boolean.TRUE.equals(shared.getFirst());
        if (!sharing && !viewer.equals(userId)) return new HistoryView(userId, "NOT_SHARED", false, List.of());
        var entries = jdbc.query("select kind, fulfilment_mode, count(*) from stir.agreement_obligation where tenant_id=? and obligor_user_id=? "
                + "and status='DONE' and kind<>'ECONOMIC_EXCHANGE' group by kind, fulfilment_mode order by kind, fulfilment_mode nulls last",
            (rs, i) -> new HistoryEntry(rs.getString(1), rs.getString(2), rs.getLong(3)), tenant, userId);
        return new HistoryView(userId, entries.isEmpty() ? "NO_HISTORY_YET" : "HISTORY", sharing, entries);
    }

    public HistoryView share(CurrentUser user, boolean share) {
        UUID tenant = tenant(), owner = actor(user);
        int updated = jdbc.update("update stir.participant_profile set share_fulfilment_history=?, version=version+1, updated_at=now() "
            + "where tenant_id=? and user_id=?", share, tenant, owner);
        if (updated == 0) throw new ResponseStatusException(CONFLICT, "Create your profile first");
        return history(user, owner);
    }

    // ----- helpers -------------------------------------------------------------------------------

    private static UUID uuid(ResultSet rs, int column) throws SQLException { return rs.getObject(column, UUID.class); }
    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
    private static JsonNode readTree(String json) {
        try { return JSON.readTree(json); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Stored canonical JSON is unreadable", ex); }
    }

    // ----- API shapes ----------------------------------------------------------------------------

    public record EventView(UUID id, UUID actorUserId, String actorRole, String type, String note, Instant createdAt) {}
    public record ObligationView(UUID id, String kind, String origin, UUID amendmentId, UUID obligorUserId, UUID beneficiaryUserId,
                                 String description, LocalDate fulfilBy, String fulfilmentMode, String status, UUID supersededByAmendmentId,
                                 String yourRole, List<EventView> events) {}
    public record FulfilmentView(UUID agreementId, String snapshotDigest, String headDigest, String economicPhase,
                                 List<ObligationView> obligations, ExecutionAttemptRecorder.AttemptView lastExecutionAttempt,
                                 List<CaseRef> cases, List<CorrectionRef> corrections, ContainmentRef containment) {}
    public record CaseRef(UUID id, UUID obligationId, String claim, String status) {}
    public record CorrectionRef(UUID id, UUID caseId, String kind, String status, UUID ostrisTransactionId, Long committedSequence, Instant committedAt) {}
    public record ContainmentRef(UUID caseId, Instant until) {}
    public record NewObligation(String kind, String description, LocalDate fulfilBy, String fulfilmentMode) {}
    public record AmendmentRequest(String baseDigest, List<UUID> replaces, List<NewObligation> obligations, String reason, Instant validUntil) {}
    public record AmendmentView(UUID id, UUID proposerUserId, String baseDigest, String reason, Instant validUntil, String status,
                                UUID decidedByUserId, Instant decidedAt, Integer sequenceNumber, String digestSha256, Instant createdAt,
                                JsonNode content) {}
    public record HistoryEntry(String kind, String fulfilmentMode, long count) {}
    public record HistoryView(UUID userId, String status, boolean shared, List<HistoryEntry> entries) {}
}
