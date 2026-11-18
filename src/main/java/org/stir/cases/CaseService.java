package org.stir.cases;

import es.idynamicsax.idax.security.CurrentUser;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.OstrisClient;
import org.stir.fulfilment.FulfilmentService;
import org.stir.notification.NotificationService;
import static org.springframework.http.HttpStatus.*;
import static org.stir.cases.CaseReviewService.actor;
import static org.stir.cases.CaseReviewService.tenant;

/**
 * Agreement cases (AGREEMENT_CASES.md): reviewing an unfulfilled obligation without judging the
 * person. The dispute (the case), the finding about the effect, the attribution of conduct, a
 * sanction and an appeal are separate objects with separate authors. Nothing here happens by
 * itself: no status, balance, history or sanction changes because time passed or because a
 * number crossed a line.
 *
 * <p>Who may see a case: its two parties and the community's named reviewers who are not parties.
 * Everyone else - a platform superuser included - gets 404, as for the Agreement itself.
 */
@Service @Transactional
public class CaseService {
    static final String DISPUTE_BASIS = "FINAL_DISPUTE_RESOLUTION";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final OstrisClient ostris;
    private final FulfilmentService fulfilment;

    public CaseService(JdbcTemplate jdbc, NotificationService notifications, OstrisClient ostris, FulfilmentService fulfilment) {
        this.jdbc = jdbc; this.notifications = notifications; this.ostris = ostris; this.fulfilment = fulfilment;
    }

    // ----- access ---------------------------------------------------------------------------------

    record CaseRow(UUID id, UUID agreementId, UUID obligationId, UUID claimant, UUID respondent, String claim, String statement,
                   String status, UUID reviewer, Instant createdAt, Instant updatedAt) {
        boolean party(UUID user) { return claimant.equals(user) || respondent.equals(user); }
        UUID other(UUID user) { return claimant.equals(user) ? respondent : claimant; }
    }

    CaseRow load(UUID tenant, UUID caseId, boolean lock) {
        var rows = jdbc.query("select id,agreement_id,obligation_id,claimant_user_id,respondent_user_id,claim,statement,status,reviewer_user_id,"
                + "created_at,updated_at from stir.agreement_case where tenant_id=? and id=?" + (lock ? " for update" : ""),
            (rs, i) -> caseRow(rs), tenant, caseId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Case not found");
        return rows.getFirst();
    }
    private static CaseRow caseRow(ResultSet rs) throws SQLException {
        return new CaseRow(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5), rs.getString(6), rs.getString(7),
            rs.getString(8), uuid(rs, 9), instant(rs, 10), instant(rs, 11));
    }

    /** CLAIMANT, RESPONDENT or REVIEWER; anyone else is told the case does not exist. */
    String role(UUID tenant, CaseRow row, UUID actor) {
        if (row.claimant().equals(actor)) return "CLAIMANT";
        if (row.respondent().equals(actor)) return "RESPONDENT";
        if (CaseReviewService.isReviewer(jdbc, tenant, actor)) return "REVIEWER";
        throw new ResponseStatusException(NOT_FOUND, "Case not found");
    }
    private String requireParty(UUID tenant, CaseRow row, UUID actor) {
        String role = role(tenant, row, actor);
        if ("REVIEWER".equals(role)) throw new ResponseStatusException(FORBIDDEN, "Only the parties to the case can do this");
        return role;
    }
    private void requireReviewer(UUID tenant, CaseRow row, UUID actor) {
        if (!"REVIEWER".equals(role(tenant, row, actor)))
            throw new ResponseStatusException(FORBIDDEN, "Only a community reviewer who is not a party can do this");
    }
    private static void requireOpen(CaseRow row) {
        if (CaseRules.CLOSED.contains(row.status())) throw new ResponseStatusException(CONFLICT, "This case is " + row.status().toLowerCase());
    }

    // ----- opening --------------------------------------------------------------------------------

    public CaseView open(CurrentUser user, UUID agreementId, OpenRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        // 404 for anyone but a party; also derives the obligations if nobody has read them yet.
        fulfilment.view(user, agreementId);
        if (request == null || request.obligationId() == null) throw new ResponseStatusException(BAD_REQUEST, "Say which obligation the case is about");
        var rows = jdbc.queryForList("select kind,obligor_user_id,beneficiary_user_id,status from stir.agreement_obligation "
            + "where tenant_id=? and agreement_id=? and id=?", tenant, agreementId, request.obligationId());
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Obligation not found");
        var obligation = rows.getFirst();
        if (!CaseRules.CLAIMABLE_KINDS.contains((String) obligation.get("kind")))
            throw new ResponseStatusException(CONFLICT, "The economic exchange is evidenced by osTRIS; open the case on the goods, service or tasks it paid for");
        if (!actor.equals(obligation.get("beneficiary_user_id")))
            throw new ResponseStatusException(CONFLICT, "Only the party this obligation is owed to can claim it was not fulfilled");
        if (CaseRules.UNCLAIMABLE_STATUSES.contains((String) obligation.get("status")))
            throw new ResponseStatusException(CONFLICT, "This obligation is " + obligation.get("status") + "; there is nothing to claim");
        if (request.claim() == null || !CaseRules.CLAIMS.contains(request.claim()))
            throw new ResponseStatusException(BAD_REQUEST, "The claim is NON_DELIVERY, NON_CONFORMITY or PARTIAL_DELIVERY");
        String statement = text(request.statement(), 4000, "Describe what happened (up to 4000 characters)");
        Integer reviewers = jdbc.queryForObject("select count(*) from stir.case_reviewer r join stir.case_review_settings s on s.tenant_id=r.tenant_id "
            + "where r.tenant_id=? and r.removed_at is null", Integer.class, tenant);
        if (reviewers == null || reviewers < CaseReviewService.MIN_REVIEWERS)
            throw new ResponseStatusException(CONFLICT, "This community has not named its case reviewers yet");
        Integer live = jdbc.queryForObject("select count(*) from stir.agreement_case where tenant_id=? and obligation_id=? "
            + "and status not in ('RESOLVED','WITHDRAWN')", Integer.class, tenant, request.obligationId());
        if (live != null && live > 0) throw new ResponseStatusException(CONFLICT, "There is already a case open about this obligation");
        UUID id = UUID.randomUUID();
        UUID respondent = (UUID) obligation.get("obligor_user_id");
        try {
            jdbc.update("insert into stir.agreement_case(id,tenant_id,agreement_id,obligation_id,claimant_user_id,respondent_user_id,claim,statement,"
                    + "status,created_at,updated_at) values (?,?,?,?,?,?,?,?,'OPEN',clock_timestamp(),clock_timestamp())",
                id, tenant, agreementId, request.obligationId(), actor, respondent, request.claim(), statement);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "There is already a case open about this obligation", ex);
        }
        event(tenant, id, actor, "CLAIMANT", "OPENED", "PARTIES", null, statement);
        notifications.create(tenant, respondent, "CASE_OPENED", "AGREEMENT", agreementId);
        return view(user, id);
    }

    // ----- reading --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CaseLists list(CurrentUser user) {
        UUID tenant = tenant(), actor = actor(user);
        String select = "select c.id,c.agreement_id,c.obligation_id,c.claimant_user_id,c.respondent_user_id,c.claim,c.statement,c.status,"
            + "c.reviewer_user_id,c.created_at,c.updated_at from stir.agreement_case c where c.tenant_id=? ";
        var mine = jdbc.query(select + "and (c.claimant_user_id=? or c.respondent_user_id=?) order by c.created_at desc limit 200",
            (rs, i) -> summary(caseRow(rs), actor), tenant, actor, actor);
        List<CaseSummary> review = List.of();
        if (CaseReviewService.isReviewer(jdbc, tenant, actor))
            review = jdbc.query(select + "and c.claimant_user_id<>? and c.respondent_user_id<>? order by "
                    + "case when c.status in ('RESOLVED','WITHDRAWN') then 1 else 0 end, c.created_at desc limit 200",
                (rs, i) -> summary(caseRow(rs), actor), tenant, actor, actor);
        return new CaseLists(mine, review);
    }
    private static CaseSummary summary(CaseRow c, UUID actor) {
        String role = c.claimant().equals(actor) ? "CLAIMANT" : c.respondent().equals(actor) ? "RESPONDENT" : "REVIEWER";
        return new CaseSummary(c.id(), c.agreementId(), c.obligationId(), c.claim(), c.status(), role, c.reviewer() != null, c.createdAt(), c.updatedAt());
    }

    @Transactional(readOnly = true)
    public List<CaseSummary> forAgreement(CurrentUser user, UUID agreementId) {
        UUID tenant = tenant(), actor = actor(user);
        var parties = jdbc.queryForList("select initiator_id, owner_id from stir.agreement where tenant_id=? and id=?", tenant, agreementId);
        if (parties.isEmpty() || !(actor.equals(parties.getFirst().get("initiator_id")) || actor.equals(parties.getFirst().get("owner_id"))))
            throw new ResponseStatusException(NOT_FOUND, "Agreement not found");
        return jdbc.query("select id,agreement_id,obligation_id,claimant_user_id,respondent_user_id,claim,statement,status,reviewer_user_id,"
                + "created_at,updated_at from stir.agreement_case where tenant_id=? and agreement_id=? order by created_at",
            (rs, i) -> summary(caseRow(rs), actor), tenant, agreementId);
    }

    @Transactional(readOnly = true)
    public CaseView view(CurrentUser user, UUID caseId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, false);
        String role = role(tenant, c, actor);
        boolean reviewer = "REVIEWER".equals(role);
        var obligation = jdbc.queryForMap("select kind, description, status, fulfil_by from stir.agreement_obligation where tenant_id=? and id=?",
            tenant, c.obligationId());
        var events = jdbc.query("select id,actor_user_id,actor_role,type,visibility,ref_id,note,created_at from stir.agreement_case_event "
                + "where tenant_id=? and case_id=? order by created_at, id",
            (rs, i) -> new EventView(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(4), rs.getString(5), uuid(rs, 6), rs.getString(7), instant(rs, 8)),
            tenant, caseId).stream().filter(e -> reviewer || "PARTIES".equals(e.visibility()) || actor.equals(e.actorUserId())).toList();
        var evidence = jdbc.query("select id,submitted_by,submitter_role,visibility,description,external_reference,created_at from stir.agreement_case_evidence "
                + "where tenant_id=? and case_id=? order by created_at, id",
            (rs, i) -> new EvidenceView(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), instant(rs, 7)),
            tenant, caseId).stream().map(e -> reviewer || "PARTIES_AND_REVIEWERS".equals(e.visibility()) || actor.equals(e.submittedBy()) ? e
                : new EvidenceView(e.id(), e.submittedBy(), e.submitterRole(), e.visibility(), null, null, e.createdAt())).toList();
        var waivers = new HashMap<UUID, List<UUID>>();
        jdbc.query("select ref_id, actor_user_id from stir.agreement_case_event where tenant_id=? and case_id=? and type='APPEAL_WAIVED'",
            rs -> { waivers.computeIfAbsent(uuid(rs, 1), k -> new ArrayList<>()).add(uuid(rs, 2)); }, tenant, caseId);
        var appeals = new HashMap<UUID, AppealView>();
        jdbc.query("select id,decision_id,filed_by_user_id,grounds,status,decided_by_user_id,decision_reasoning,decided_at,created_at "
                + "from stir.agreement_case_appeal where tenant_id=? and case_id=?",
            rs -> { appeals.put(uuid(rs, 2), new AppealView(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), rs.getString(4), rs.getString(5), uuid(rs, 6),
                rs.getString(7), instant(rs, 8), instant(rs, 9))); }, tenant, caseId);
        var decisions = jdbc.query(DECISION_SELECT + " order by created_at, id",
            (rs, i) -> decision(rs, waivers, appeals), tenant, caseId);
        var containments = jdbc.query("select id,scope,reason,applied_by_user_id,applied_at,until_at,lifted_by_user_id,lifted_at "
                + "from stir.agreement_case_containment where tenant_id=? and case_id=? order by applied_at",
            (rs, i) -> new ContainmentView(uuid(rs, 1), rs.getString(2), rs.getString(3), uuid(rs, 4), instant(rs, 5), instant(rs, 6), uuid(rs, 7),
                instant(rs, 8), instant(rs, 8) == null && Instant.now().isBefore(instant(rs, 6))), tenant, caseId);
        var remedies = CaseRemedyService.remedies(jdbc, tenant, caseId);
        var settings = jdbc.queryForList("select appeal_window_hours, authority_threshold from stir.case_review_settings where tenant_id=?", tenant);
        var trade = jdbc.queryForList("select execution_state from stir.trade where tenant_id=? and agreement_id=?", String.class, tenant, c.agreementId());
        return new CaseView(c.id(), c.agreementId(), c.obligationId(), (String) obligation.get("kind"), (String) obligation.get("description"),
            (String) obligation.get("status"), c.claimant(), c.respondent(), c.claim(), c.statement(), c.status(), c.reviewer(), role,
            c.createdAt(), c.updatedAt(), events, evidence, decisions, containments, remedies,
            settings.isEmpty() ? null : (Integer) settings.getFirst().get("appeal_window_hours"),
            settings.isEmpty() ? null : (Integer) settings.getFirst().get("authority_threshold"),
            !trade.isEmpty() && "COMMITTED".equals(trade.getFirst()));
    }

    static final String DECISION_SELECT = "select id,decision_type,issued_by_user_id,reasoning,status,appeal_deadline,final_at,effect,economic_correction,"
        + "performance_remedy,ostris_basis_id,subject_role,assessment,third_party_note,subject_user_id,sanction_kind,basis_decision_id,created_at "
        + "from stir.agreement_case_decision where tenant_id=? and case_id=?";
    private static DecisionView decision(ResultSet rs, Map<UUID, List<UUID>> waivers, Map<UUID, AppealView> appeals) throws SQLException {
        UUID id = uuid(rs, 1);
        return new DecisionView(id, rs.getString(2), uuid(rs, 3), rs.getString(4), rs.getString(5), instant(rs, 6), instant(rs, 7),
            rs.getString(8), rs.getString(9), rs.getString(10), uuid(rs, 11), rs.getString(12), rs.getString(13), rs.getString(14),
            uuid(rs, 15), rs.getString(16), uuid(rs, 17), instant(rs, 18), waivers.getOrDefault(id, List.of()), appeals.get(id));
    }

    // ----- evidence -------------------------------------------------------------------------------

    public CaseView addEvidence(CurrentUser user, UUID caseId, EvidenceRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        String role = role(tenant, c, actor);
        requireOpen(c);
        if (request == null) throw new ResponseStatusException(BAD_REQUEST, "Evidence required");
        String description = text(request.description(), 4000, "Describe the evidence (up to 4000 characters)");
        String visibility = request.visibility() == null ? "PARTIES_AND_REVIEWERS" : request.visibility();
        if (!CaseRules.EVIDENCE_VISIBILITIES.contains(visibility))
            throw new ResponseStatusException(BAD_REQUEST, "Visibility is PARTIES_AND_REVIEWERS or REVIEWERS_ONLY");
        String reference = request.externalReference() == null || request.externalReference().isBlank() ? null : request.externalReference().trim();
        if (reference != null && reference.length() > 500) throw new ResponseStatusException(BAD_REQUEST, "The reference is limited to 500 characters");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stir.agreement_case_evidence(id,tenant_id,case_id,submitted_by,submitter_role,visibility,description,external_reference,created_at) "
            + "values (?,?,?,?,?,?,?,?,clock_timestamp())", id, tenant, caseId, actor, role, visibility, description, reference);
        // Everyone on the case learns that evidence was added; restricted content stays with the reviewers.
        event(tenant, caseId, actor, role, "EVIDENCE_ADDED", "PARTIES", id, null);
        touch(tenant, caseId);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    // ----- review ---------------------------------------------------------------------------------

    public CaseView take(CurrentUser user, UUID caseId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        if (!"OPEN".equals(c.status())) throw new ResponseStatusException(CONFLICT, "This case is already being reviewed");
        jdbc.update("update stir.agreement_case set status='UNDER_REVIEW', reviewer_user_id=?, updated_at=clock_timestamp() where tenant_id=? and id=?",
            actor, tenant, caseId);
        event(tenant, caseId, actor, "REVIEWER", "REVIEW_TAKEN", "PARTIES", null, null);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    public CaseView contain(CurrentUser user, UUID caseId, ContainmentRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        requireOpen(c);
        if (request == null || request.until() == null) throw new ResponseStatusException(BAD_REQUEST, "Say until when the agreement's execution is paused");
        String reason = text(request.reason(), 2000, "Give the reason for containment (up to 2000 characters)");
        Instant now = Instant.now();
        if (!request.until().isAfter(now) || request.until().isAfter(now.plus(Duration.ofDays(CaseRules.MAX_CONTAINMENT_DAYS))))
            throw new ResponseStatusException(BAD_REQUEST, "Containment lasts at most " + CaseRules.MAX_CONTAINMENT_DAYS + " days and must end in the future");
        if (activeContainment(jdbc, tenant, c.agreementId()) != null)
            throw new ResponseStatusException(CONFLICT, "The agreement's execution is already paused");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stir.agreement_case_containment(id,tenant_id,case_id,agreement_id,scope,reason,applied_by_user_id,applied_at,until_at) "
            + "values (?,?,?,?,'AGREEMENT_EXECUTION',?,?,?,?)", id, tenant, caseId, c.agreementId(), reason, actor, Timestamp.from(now), Timestamp.from(request.until()));
        event(tenant, caseId, actor, "REVIEWER", "CONTAINMENT_APPLIED", "PARTIES", id, reason);
        touch(tenant, caseId);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    public CaseView lift(CurrentUser user, UUID caseId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        if (liftContainment(tenant, c, actor) == 0) throw new ResponseStatusException(CONFLICT, "Nothing is paused");
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }
    private int liftContainment(UUID tenant, CaseRow c, UUID actor) {
        var active = jdbc.queryForList("select id from stir.agreement_case_containment where tenant_id=? and case_id=? and lifted_at is null",
            UUID.class, tenant, c.id());
        for (UUID id : active) {
            jdbc.update("update stir.agreement_case_containment set lifted_by_user_id=?, lifted_at=clock_timestamp() where tenant_id=? and id=?", actor, tenant, id);
            event(tenant, c.id(), actor, roleOf(c, actor), "CONTAINMENT_LIFTED", "PARTIES", id, null);
        }
        return active.size();
    }

    /** The active containment on an Agreement's execution, if any. Used by trade commit and amendments. */
    public static UUID activeContainment(JdbcTemplate jdbc, UUID tenant, UUID agreementId) {
        var rows = jdbc.queryForList("select id from stir.agreement_case_containment where tenant_id=? and agreement_id=? "
            + "and lifted_at is null and until_at > now()", UUID.class, tenant, agreementId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public static void requireNotContained(JdbcTemplate jdbc, UUID tenant, UUID agreementId) {
        if (activeContainment(jdbc, tenant, agreementId) != null)
            throw new ResponseStatusException(CONFLICT, "CASE_CONTAINMENT_ACTIVE: a case reviewer paused this agreement's execution while the case is reviewed");
    }

    // ----- decisions ------------------------------------------------------------------------------

    public CaseView issueFinding(CurrentUser user, UUID caseId, FindingRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        if (!actor.equals(c.reviewer()) || !"UNDER_REVIEW".equals(c.status()))
            throw new ResponseStatusException(CONFLICT, "Only the reviewer who took the case issues its finding, while it is under review");
        if (request == null || request.effect() == null || !CaseRules.EFFECTS.contains(request.effect()))
            throw new ResponseStatusException(BAD_REQUEST, "The effect is ESTABLISHED, PARTIALLY_ESTABLISHED or NOT_ESTABLISHED");
        String correction = request.economicCorrection() == null ? "NONE" : request.economicCorrection();
        if (!CaseRules.ECONOMIC_CORRECTIONS.contains(correction)) throw new ResponseStatusException(BAD_REQUEST, "The economic correction is NONE or FULL_REVERSAL");
        if (!CaseRules.correctionAllowed(request.effect(), correction))
            throw new ResponseStatusException(BAD_REQUEST, "An economic correction needs an established effect");
        String reasoning = text(request.reasoning(), 4000, "Explain the finding (up to 4000 characters)");
        String remedy = request.performanceRemedy() == null || request.performanceRemedy().isBlank() ? null : request.performanceRemedy().trim();
        if (remedy != null && remedy.length() > 2000) throw new ResponseStatusException(BAD_REQUEST, "The repair is limited to 2000 characters");
        UUID basis = null;
        if ("FULL_REVERSAL".equals(correction)) {
            var trade = jdbc.queryForList("select execution_state from stir.trade where tenant_id=? and agreement_id=?", String.class, tenant, c.agreementId());
            if (trade.isEmpty() || !"COMMITTED".equals(trade.getFirst()))
                throw new ResponseStatusException(CONFLICT, "There is no committed economic exchange on this agreement to reverse");
            // The osTRIS basis exists from now on as PENDING; it becomes FINAL only with this finding.
            basis = uuidV7();
            ostris.recordResolutionBasis(community(tenant), basis, DISPUTE_BASIS);
        }
        UUID id = UUID.randomUUID();
        Instant deadline = Instant.now().plus(Duration.ofHours(appealWindowHours(tenant)));
        try {
            jdbc.update("insert into stir.agreement_case_decision(id,tenant_id,case_id,decision_type,issued_by_user_id,reasoning,status,appeal_deadline,"
                    + "effect,economic_correction,performance_remedy,ostris_basis_id,created_at) values (?,?,?,'FINDING',?,?,'ISSUED',?,?,?,?,?,clock_timestamp())",
                id, tenant, caseId, actor, reasoning, Timestamp.from(deadline), request.effect(), correction, remedy, basis);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "This case already has a standing finding", ex);
        }
        jdbc.update("update stir.agreement_case set status='FINDING_ISSUED', updated_at=clock_timestamp() where tenant_id=? and id=?", tenant, caseId);
        event(tenant, caseId, actor, "REVIEWER", "FINDING_ISSUED", "PARTIES", id, reasoning);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    public CaseView issueAttribution(CurrentUser user, UUID caseId, AttributionRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var finding = standing(tenant, caseId, "FINDING");
        if (finding == null || !"FINAL".equals(finding.get("status")))
            throw new ResponseStatusException(CONFLICT, "Conduct is only assessed after the finding about the effect is final");
        if (request == null || !CaseRules.SUBJECT_ROLES.contains(request.subjectRole()) || !CaseRules.ASSESSMENTS.contains(request.assessment()))
            throw new ResponseStatusException(BAD_REQUEST, "Name the subject (OBLIGOR, BENEFICIARY, THIRD_PARTY, NONE) and the assessment");
        if (!CaseRules.attributionAllowed((String) finding.get("effect"), request.subjectRole(), request.assessment()))
            throw new ResponseStatusException(BAD_REQUEST, "That attribution does not fit the finding: a breach names one of the parties, "
                + "a good-faith third party names a third party, and nothing is attributed for an effect that was not established");
        String reasoning = text(request.reasoning(), 4000, "Explain the attribution (up to 4000 characters)");
        String thirdParty = "THIRD_PARTY".equals(request.subjectRole()) ? text(request.thirdPartyNote(), 1000, "Describe the third party (up to 1000 characters)") : null;
        UUID id = UUID.randomUUID();
        Instant deadline = Instant.now().plus(Duration.ofHours(appealWindowHours(tenant)));
        try {
            jdbc.update("insert into stir.agreement_case_decision(id,tenant_id,case_id,decision_type,issued_by_user_id,reasoning,status,appeal_deadline,"
                    + "subject_role,assessment,third_party_note,basis_decision_id,created_at) values (?,?,?,'ATTRIBUTION',?,?,'ISSUED',?,?,?,?,?,clock_timestamp())",
                id, tenant, caseId, actor, reasoning, Timestamp.from(deadline), request.subjectRole(), request.assessment(), thirdParty, finding.get("id"));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "This case already has a standing attribution", ex);
        }
        event(tenant, caseId, actor, "REVIEWER", "ATTRIBUTION_ISSUED", "PARTIES", id, reasoning);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    public CaseView issueSanction(CurrentUser user, UUID caseId, SanctionRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var attribution = standing(tenant, caseId, "ATTRIBUTION");
        if (attribution == null || !"FINAL".equals(attribution.get("status")) || !"BREACH".equals(attribution.get("assessment")))
            throw new ResponseStatusException(CONFLICT, "A sanction needs a final attribution of breach; it is never a consequence of a balance or of the finding alone");
        if (request == null || !CaseRules.SANCTION_KINDS.contains(request.kind()))
            throw new ResponseStatusException(BAD_REQUEST, "The only sanction available is WARNING");
        String reasoning = text(request.reasoning(), 4000, "Explain the sanction (up to 4000 characters)");
        var obligation = jdbc.queryForMap("select obligor_user_id, beneficiary_user_id from stir.agreement_obligation where tenant_id=? and id=?",
            tenant, c.obligationId());
        UUID subject = (UUID) ("OBLIGOR".equals(attribution.get("subject_role")) ? obligation.get("obligor_user_id") : obligation.get("beneficiary_user_id"));
        UUID id = UUID.randomUUID();
        Instant deadline = Instant.now().plus(Duration.ofHours(appealWindowHours(tenant)));
        try {
            jdbc.update("insert into stir.agreement_case_decision(id,tenant_id,case_id,decision_type,issued_by_user_id,reasoning,status,appeal_deadline,"
                    + "subject_user_id,sanction_kind,basis_decision_id,created_at) values (?,?,?,'SANCTION',?,?,'ISSUED',?,?,?,?,clock_timestamp())",
                id, tenant, caseId, actor, reasoning, Timestamp.from(deadline), subject, request.kind(), attribution.get("id"));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "This case already has a standing sanction", ex);
        }
        event(tenant, caseId, actor, "REVIEWER", "SANCTION_ISSUED", "PARTIES", id, reasoning);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    private Map<String, Object> standing(UUID tenant, UUID caseId, String type) {
        var rows = jdbc.queryForList("select * from stir.agreement_case_decision where tenant_id=? and case_id=? and decision_type=? "
            + "and status<>'OVERTURNED'", tenant, caseId, type);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Map<String, Object> decisionRow(UUID tenant, UUID caseId, UUID decisionId, boolean lock) {
        var rows = jdbc.queryForList("select * from stir.agreement_case_decision where tenant_id=? and case_id=? and id=?" + (lock ? " for update" : ""),
            tenant, caseId, decisionId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Decision not found");
        return rows.getFirst();
    }

    // ----- appeal and finality ----------------------------------------------------------------------

    public CaseView waive(CurrentUser user, UUID caseId, UUID decisionId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        String role = requireParty(tenant, c, actor);
        var d = decisionRow(tenant, caseId, decisionId, true);
        if (!"ISSUED".equals(d.get("status"))) throw new ResponseStatusException(CONFLICT, "This decision is no longer open to appeal");
        if (waivedBy(tenant, caseId, decisionId).contains(actor)) throw new ResponseStatusException(CONFLICT, "You already accepted this decision");
        event(tenant, caseId, actor, role, "APPEAL_WAIVED", "PARTIES", decisionId, null);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }
    private Set<UUID> waivedBy(UUID tenant, UUID caseId, UUID decisionId) {
        return new HashSet<>(jdbc.queryForList("select actor_user_id from stir.agreement_case_event where tenant_id=? and case_id=? "
            + "and type='APPEAL_WAIVED' and ref_id=?", UUID.class, tenant, caseId, decisionId));
    }

    public CaseView appeal(CurrentUser user, UUID caseId, UUID decisionId, AppealRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        String role = requireParty(tenant, c, actor);
        var d = decisionRow(tenant, caseId, decisionId, true);
        if (!CaseRules.appealable((String) d.get("status"), ((Timestamp) d.get("appeal_deadline")).toInstant(), Instant.now()))
            throw new ResponseStatusException(CONFLICT, "This decision can no longer be appealed");
        if (waivedBy(tenant, caseId, decisionId).contains(actor)) throw new ResponseStatusException(CONFLICT, "You accepted this decision");
        String grounds = text(request == null ? null : request.grounds(), 4000, "Give the grounds of the appeal (up to 4000 characters)");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into stir.agreement_case_appeal(id,tenant_id,case_id,decision_id,filed_by_user_id,grounds,status,created_at) "
                + "values (?,?,?,?,?,?,'OPEN',clock_timestamp())", id, tenant, caseId, decisionId, actor, grounds);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(CONFLICT, "This decision was already appealed", ex);
        }
        jdbc.update("update stir.agreement_case_decision set status='APPEALED' where tenant_id=? and id=?", tenant, decisionId);
        if ("FINDING".equals(d.get("decision_type")))
            jdbc.update("update stir.agreement_case set status='APPEALED', updated_at=clock_timestamp() where tenant_id=? and id=?", tenant, caseId);
        event(tenant, caseId, actor, role, "APPEAL_FILED", "PARTIES", id, grounds);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    public CaseView decideAppeal(CurrentUser user, UUID caseId, UUID appealId, AppealDecisionRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var rows = jdbc.queryForList("select * from stir.agreement_case_appeal where tenant_id=? and case_id=? and id=? for update", tenant, caseId, appealId);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Appeal not found");
        var appeal = rows.getFirst();
        if (!"OPEN".equals(appeal.get("status"))) throw new ResponseStatusException(CONFLICT, "This appeal was already decided");
        var d = decisionRow(tenant, caseId, (UUID) appeal.get("decision_id"), true);
        if (actor.equals(d.get("issued_by_user_id")))
            throw new ResponseStatusException(CONFLICT, "An appeal is decided by a reviewer other than the author of the decision");
        if (request == null || !Set.of("UPHELD", "OVERTURNED").contains(request.outcome()))
            throw new ResponseStatusException(BAD_REQUEST, "The outcome is UPHELD or OVERTURNED");
        String reasoning = text(request.reasoning(), 4000, "Explain the appeal decision (up to 4000 characters)");
        jdbc.update("update stir.agreement_case_appeal set status=?, decided_by_user_id=?, decision_reasoning=?, decided_at=clock_timestamp() "
            + "where tenant_id=? and id=?", request.outcome(), actor, reasoning, tenant, appealId);
        event(tenant, caseId, actor, "REVIEWER", "APPEAL_DECIDED", "PARTIES", appealId, reasoning);
        if ("UPHELD".equals(request.outcome())) {
            makeFinal(tenant, c, d, actor);
        } else {
            jdbc.update("update stir.agreement_case_decision set status='OVERTURNED' where tenant_id=? and id=?", tenant, d.get("id"));
            if (d.get("ostris_basis_id") != null) ostris.resolutionBasisFinality(community(tenant), (UUID) d.get("ostris_basis_id"), "OVERTURNED");
            // An overturned finding goes back to review, by the reviewer who decided the appeal - never back to its author.
            if ("FINDING".equals(d.get("decision_type")))
                jdbc.update("update stir.agreement_case set status='UNDER_REVIEW', reviewer_user_id=?, updated_at=clock_timestamp() where tenant_id=? and id=?",
                    actor, tenant, caseId);
        }
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    public CaseView finalizeDecision(CurrentUser user, UUID caseId, UUID decisionId) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        requireReviewer(tenant, c, actor);
        var d = decisionRow(tenant, caseId, decisionId, true);
        boolean bothWaived = waivedBy(tenant, caseId, decisionId).containsAll(List.of(c.claimant(), c.respondent()));
        Instant deadline = ((Timestamp) d.get("appeal_deadline")).toInstant();
        if (!CaseRules.finalizable((String) d.get("status"), deadline, Instant.now(), bothWaived))
            throw new ResponseStatusException(CONFLICT, "APPEALED".equals(d.get("status")) ? "An appeal is open; it has to be decided first"
                : "The appeal window is open until " + deadline + " unless both parties accept the decision");
        makeFinal(tenant, c, d, actor);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    private void makeFinal(UUID tenant, CaseRow c, Map<String, Object> d, UUID actor) {
        jdbc.update("update stir.agreement_case_decision set status='FINAL', final_at=clock_timestamp() where tenant_id=? and id=?", tenant, d.get("id"));
        event(tenant, c.id(), actor, "REVIEWER", "DECISION_FINAL", "PARTIES", (UUID) d.get("id"), null);
        if (!"FINDING".equals(d.get("decision_type"))) return;
        if (d.get("ostris_basis_id") != null) ostris.resolutionBasisFinality(community(tenant), (UUID) d.get("ostris_basis_id"), "FINAL");
        // The effect is settled: the review of the effect ends here, and nothing stays paused because of it.
        liftContainment(tenant, c, actor);
        jdbc.update("update stir.agreement_case set status='RESOLVED', updated_at=clock_timestamp() where tenant_id=? and id=?", tenant, c.id());
    }

    public CaseView withdraw(CurrentUser user, UUID caseId, WithdrawRequest request) {
        UUID tenant = tenant(), actor = actor(user);
        var c = load(tenant, caseId, true);
        if (!c.claimant().equals(actor)) {
            role(tenant, c, actor);
            throw new ResponseStatusException(FORBIDDEN, "Only the party who opened the case can withdraw it");
        }
        requireOpen(c);
        String note = request == null || request.note() == null || request.note().isBlank() ? null : text(request.note(), 2000, "The note is limited to 2000 characters");
        liftContainment(tenant, c, actor);
        jdbc.update("update stir.agreement_case set status='WITHDRAWN', updated_at=clock_timestamp() where tenant_id=? and id=?", tenant, caseId);
        event(tenant, caseId, actor, "CLAIMANT", "WITHDRAWN", "PARTIES", null, note);
        notifyParties(tenant, c, actor);
        return view(user, caseId);
    }

    // ----- helpers --------------------------------------------------------------------------------

    void event(UUID tenant, UUID caseId, UUID actor, String role, String type, String visibility, UUID ref, String note) {
        jdbc.update("insert into stir.agreement_case_event(id,tenant_id,case_id,actor_user_id,actor_role,type,visibility,ref_id,note,created_at) "
            + "values (?,?,?,?,?,?,?,?,?,clock_timestamp())", UUID.randomUUID(), tenant, caseId, actor, role, type, visibility, ref, note);
    }
    private static String roleOf(CaseRow c, UUID actor) {
        return c.claimant().equals(actor) ? "CLAIMANT" : c.respondent().equals(actor) ? "RESPONDENT" : "REVIEWER";
    }
    private void touch(UUID tenant, UUID caseId) {
        jdbc.update("update stir.agreement_case set updated_at=clock_timestamp() where tenant_id=? and id=? and status not in ('RESOLVED','WITHDRAWN')", tenant, caseId);
    }
    void notifyParties(UUID tenant, CaseRow c, UUID actor) {
        for (UUID party : List.of(c.claimant(), c.respondent()))
            if (!party.equals(actor)) notifications.create(tenant, party, "CASE_UPDATED", "AGREEMENT", c.agreementId());
    }
    UUID community(UUID tenant) {
        var bound = jdbc.queryForList("select community_id from stir.marketplace_economic_binding where tenant_id=?", UUID.class, tenant);
        if (bound.isEmpty()) throw new ResponseStatusException(CONFLICT, "Bootstrap the economic marketplace first");
        return bound.getFirst();
    }
    private int appealWindowHours(UUID tenant) {
        var rows = jdbc.queryForList("select appeal_window_hours from stir.case_review_settings where tenant_id=?", Integer.class, tenant);
        if (rows.isEmpty()) throw new ResponseStatusException(CONFLICT, "This community has not named its case reviewers yet");
        return rows.getFirst();
    }
    private static String text(String value, int max, String message) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new ResponseStatusException(BAD_REQUEST, message);
        return value.trim();
    }
    /** RFC 9562 UUIDv7, as osTRIS expects for protocol identifiers STIR mints. */
    static UUID uuidV7() {
        long millis = Instant.now().toEpochMilli() & 0xFFFFFFFFFFFFL;
        long msb = (millis << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
    static UUID uuid(ResultSet rs, int column) throws SQLException { return rs.getObject(column, UUID.class); }
    static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    // ----- API shapes -----------------------------------------------------------------------------

    public record OpenRequest(UUID obligationId, String claim, String statement) {}
    public record EvidenceRequest(String description, String visibility, String externalReference) {}
    public record ContainmentRequest(String reason, Instant until) {}
    public record FindingRequest(String effect, String economicCorrection, String performanceRemedy, String reasoning) {}
    public record AttributionRequest(String subjectRole, String assessment, String thirdPartyNote, String reasoning) {}
    public record SanctionRequest(String kind, String reasoning) {}
    public record AppealRequest(String grounds) {}
    public record AppealDecisionRequest(String outcome, String reasoning) {}
    public record WithdrawRequest(String note) {}

    public record CaseSummary(UUID id, UUID agreementId, UUID obligationId, String claim, String status, String yourRole,
                              boolean reviewerAssigned, Instant createdAt, Instant updatedAt) {}
    public record CaseLists(List<CaseSummary> asParty, List<CaseSummary> toReview) {}
    public record EventView(UUID id, UUID actorUserId, String actorRole, String type, String visibility, UUID refId, String note, Instant createdAt) {}
    public record EvidenceView(UUID id, UUID submittedBy, String submitterRole, String visibility, String description,
                               String externalReference, Instant createdAt) {}
    public record AppealView(UUID id, UUID decisionId, UUID filedByUserId, String grounds, String status, UUID decidedByUserId,
                             String decisionReasoning, Instant decidedAt, Instant createdAt) {}
    public record DecisionView(UUID id, String decisionType, UUID issuedByUserId, String reasoning, String status, Instant appealDeadline,
                               Instant finalAt, String effect, String economicCorrection, String performanceRemedy, UUID ostrisBasisId,
                               String subjectRole, String assessment, String thirdPartyNote, UUID subjectUserId, String sanctionKind,
                               UUID basisDecisionId, Instant createdAt, List<UUID> waivedBy, AppealView appeal) {}
    public record ContainmentView(UUID id, String scope, String reason, UUID appliedByUserId, Instant appliedAt, Instant until,
                                  UUID liftedByUserId, Instant liftedAt, boolean active) {}
    public record CaseView(UUID id, UUID agreementId, UUID obligationId, String obligationKind, String obligationDescription,
                           String obligationStatus, UUID claimantUserId, UUID respondentUserId, String claim, String statement,
                           String status, UUID reviewerUserId, String yourRole, Instant createdAt, Instant updatedAt,
                           List<EventView> events, List<EvidenceView> evidence, List<DecisionView> decisions,
                           List<ContainmentView> containments, List<CaseRemedyService.RemedyView> remedies,
                           Integer appealWindowHours, Integer signatureThreshold, boolean economicExchangeCommitted) {}
}
