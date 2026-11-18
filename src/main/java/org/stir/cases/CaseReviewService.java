package org.stir.cases;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.economic.OstrisClient;
import org.stir.reference.ReferenceService;
import static org.springframework.http.HttpStatus.*;

/**
 * Who reviews agreement cases (AGREEMENT_CASES.md). The roster is STIR's own, set by community
 * authority (tenant owner/admin bootstrap, or ordinary governance once enabled), never by a platform
 * role. Setting it also registers a new version of the community's osTRIS resolution authority
 * with the reviewers' own controllers and threshold, so an adjudicated correction can only commit
 * with the reviewers' own signatures. The roster and threshold are public to the community (R14).
 */
@Service @Transactional
public class CaseReviewService {
    static final int MIN_REVIEWERS = 2;
    private final JdbcTemplate jdbc;
    private final OstrisClient ostris;

    public CaseReviewService(JdbcTemplate jdbc, OstrisClient ostris) { this.jdbc = jdbc; this.ostris = ostris; }

    static UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    static UUID actor(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    static boolean isReviewer(JdbcTemplate jdbc, UUID tenant, UUID user) {
        return !jdbc.queryForList("select 1 from stir.case_reviewer where tenant_id=? and user_id=? and removed_at is null", tenant, user).isEmpty();
    }

    @Transactional(readOnly = true)
    public ReviewView view(CurrentUser user) {
        UUID tenant = tenant(), viewer = actor(user);
        var reviewers = jdbc.query("select r.user_id, p.display_name, r.added_at from stir.case_reviewer r "
                + "left join stir.participant_profile p on p.tenant_id=r.tenant_id and p.user_id=r.user_id "
                + "where r.tenant_id=? and r.removed_at is null order by r.added_at, r.user_id",
            (rs, i) -> new Reviewer(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant()), tenant);
        var settings = jdbc.queryForList("select appeal_window_hours, authority_threshold, ostris_authority_id, ostris_authority_version, updated_at "
            + "from stir.case_review_settings where tenant_id=?", tenant);
        if (settings.isEmpty()) return new ReviewView(false, reviewers, null, null, null, null, null, isReviewer(jdbc, tenant, viewer));
        var s = settings.getFirst();
        return new ReviewView(reviewers.size() >= MIN_REVIEWERS, reviewers, (Integer) s.get("appeal_window_hours"), (Integer) s.get("authority_threshold"),
            (UUID) s.get("ostris_authority_id"), (Integer) s.get("ostris_authority_version"), ((Timestamp) s.get("updated_at")).toInstant(),
            isReviewer(jdbc, tenant, viewer));
    }

    public ReviewView configure(CurrentUser user, ConfigureRequest request) {
        // Community authority, never platform capability: a superuser is refused here like everywhere else.
        ReferenceService.requireCommunityAuthority(jdbc, user, null);
        UUID tenant = tenant(), actor = actor(user);
        if (request == null || request.reviewerUserIds() == null) throw new ResponseStatusException(BAD_REQUEST, "Reviewers are required");
        var reviewers = new LinkedHashSet<>(request.reviewerUserIds());
        if (reviewers.size() != request.reviewerUserIds().size() || reviewers.contains(null))
            throw new ResponseStatusException(BAD_REQUEST, "List each reviewer once");
        if (reviewers.size() < MIN_REVIEWERS)
            throw new ResponseStatusException(BAD_REQUEST, "At least " + MIN_REVIEWERS + " reviewers: an appeal is always decided by someone other than the author of the decision");
        if (reviewers.size() > 32) throw new ResponseStatusException(BAD_REQUEST, "At most 32 reviewers");
        int threshold = request.threshold() == null ? 1 : request.threshold();
        if (threshold < 1 || threshold > reviewers.size())
            throw new ResponseStatusException(BAD_REQUEST, "The signature threshold must be between 1 and the number of reviewers");
        int window = request.appealWindowHours() == null ? 72 : request.appealWindowHours();
        if (window < 1 || window > 720) throw new ResponseStatusException(BAD_REQUEST, "The appeal window is between 1 and 720 hours");
        var bound = jdbc.queryForList("select community_id from stir.marketplace_economic_binding where tenant_id=?", UUID.class, tenant);
        if (bound.isEmpty()) throw new ResponseStatusException(CONFLICT, "Bootstrap the economic marketplace first");
        var controllers = new ArrayList<UUID>();
        for (UUID reviewer : reviewers) {
            var controller = jdbc.queryForList("select controller_id from stir.participant_economic_binding where tenant_id=? and user_id=?",
                UUID.class, tenant, reviewer);
            if (controller.isEmpty())
                throw new ResponseStatusException(CONFLICT, "Every reviewer signs corrections with their own key: " + reviewer + " has not activated economic exchange yet");
            controllers.add(controller.getFirst());
        }
        var authority = ostris.registerResolutionAuthority(bound.getFirst(), controllers, threshold);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("update stir.case_reviewer set removed_at=?, removed_by_user_id=? where tenant_id=? and removed_at is null and not (user_id = any (?))",
            now, actor, tenant, reviewers.toArray(new UUID[0]));
        for (UUID reviewer : reviewers)
            if (!isReviewer(jdbc, tenant, reviewer))
                jdbc.update("insert into stir.case_reviewer(id,tenant_id,user_id,added_by_user_id,added_at) values (?,?,?,?,?)",
                    UUID.randomUUID(), tenant, reviewer, actor, now);
        jdbc.update("insert into stir.case_review_settings(tenant_id,appeal_window_hours,authority_threshold,ostris_authority_id,ostris_authority_version,"
                + "updated_by_user_id,updated_at) values (?,?,?,?,?,?,?) on conflict (tenant_id) do update set appeal_window_hours=excluded.appeal_window_hours,"
                + "authority_threshold=excluded.authority_threshold, ostris_authority_id=excluded.ostris_authority_id,"
                + "ostris_authority_version=excluded.ostris_authority_version, updated_by_user_id=excluded.updated_by_user_id, updated_at=excluded.updated_at",
            tenant, window, threshold, authority.authorityId(), authority.authorityVersion(), actor, now);
        return view(user);
    }

    public record Reviewer(UUID userId, String displayName, Instant since) {}
    public record ReviewView(boolean configured, List<Reviewer> reviewers, Integer appealWindowHours, Integer signatureThreshold,
                             UUID ostrisAuthorityId, Integer ostrisAuthorityVersion, Instant updatedAt, boolean youReview) {}
    public record ConfigureRequest(List<UUID> reviewerUserIds, Integer threshold, Integer appealWindowHours) {}
}
