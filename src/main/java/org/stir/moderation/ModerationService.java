package org.stir.moderation;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.listing.ListingRepository;
import org.stir.participant.ParticipantProfileRepository;
import static org.springframework.http.HttpStatus.*;

@Service @Transactional
public class ModerationService {
    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);
    private final ContentReportRepository reports;
    private final ListingRepository listings;
    private final ParticipantProfileRepository profiles;

    public ModerationService(ContentReportRepository reports, ListingRepository listings, ParticipantProfileRepository profiles) {
        this.reports = reports; this.listings = listings; this.profiles = profiles;
    }

    private UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID actor(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    public ReportView report(CurrentUser user, String targetType, UUID targetId, String reason) {
        UUID tenant = tenant(), actor = actor(user);
        if (!"LISTING".equals(targetType) && !"PROFILE".equals(targetType)) throw new ResponseStatusException(BAD_REQUEST, "Unknown target type");
        if ("LISTING".equals(targetType)) listings.findByIdAndTenantId(targetId, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Listing not found"));
        var report = new ContentReport();
        report.id = UUID.randomUUID(); report.tenantId = tenant; report.reporterUserId = actor;
        report.targetType = targetType; report.targetId = targetId; report.reason = reason.trim();
        report.status = "OPEN"; report.createdAt = Instant.now();
        return toView(reports.saveAndFlush(report));
    }

    public Page<ReportView> openReports(int page, int size) {
        return reports.findByTenantIdAndStatusOrderByCreatedAtDesc(tenant(), "OPEN", PageRequest.of(page, size)).map(this::toView);
    }

    // A moderator deciding "Ocultar publicación" vs "Descartar" needs to see what's actually being
    // reported, not just its type/id - previously the queue showed neither a title nor a way to
    // open the target before deciding. Falls back to null (frontend shows a generic placeholder)
    // if the target was deleted after the report was filed, rather than failing the whole list.
    private ReportView toView(ContentReport r) {
        String preview = switch (r.targetType) {
            case "LISTING" -> listings.findByIdAndTenantId(r.targetId, r.tenantId).map(l -> l.title).orElse(null);
            case "PROFILE" -> profiles.findByTenantIdAndUserId(r.tenantId, r.targetId).map(p -> p.displayName).orElse(null);
            default -> null;
        };
        return ReportView.of(r, preview);
    }

    public ReportView dismiss(CurrentUser user, UUID reportId) {
        return resolve(user, reportId, "DISMISSED");
    }

    /** Hides the reported Listing (stops appearing in public search) and resolves the report.
     * Never touches the Listing's Agreement/Trade/journal history. */
    public ReportView hideAndResolve(CurrentUser user, UUID reportId) {
        UUID tenant = tenant();
        var report = reports.findByIdAndTenantId(reportId, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Report not found"));
        if ("LISTING".equals(report.targetType)) {
            listings.findByIdAndTenantId(report.targetId, tenant).ifPresent(listing -> {
                listing.hiddenByModerator = true; listing.updatedAt = Instant.now(); listings.saveAndFlush(listing);
                log.info("Listing {} hidden by moderator, tenant={}", listing.id, tenant);
            });
        }
        return resolve(user, reportId, "RESOLVED");
    }

    public void restoreListing(CurrentUser user, UUID listingId) {
        UUID tenant = tenant(); actor(user);
        var listing = listings.findByIdAndTenantId(listingId, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Listing not found"));
        listing.hiddenByModerator = false; listing.updatedAt = Instant.now(); listings.saveAndFlush(listing);
    }

    private ReportView resolve(CurrentUser user, UUID reportId, String status) {
        UUID tenant = tenant(), actor = actor(user);
        var report = reports.findByIdAndTenantId(reportId, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Report not found"));
        if (!"OPEN".equals(report.status)) throw new ResponseStatusException(CONFLICT, "Report already resolved");
        report.status = status; report.resolvedAt = Instant.now(); report.resolvedByUserId = actor;
        return toView(reports.saveAndFlush(report));
    }

    public record ReportView(UUID id, String targetType, UUID targetId, String reason, String status, Instant createdAt, String targetPreview) {
        static ReportView of(ContentReport r, String targetPreview) { return new ReportView(r.id, r.targetType, r.targetId, r.reason, r.status, r.createdAt, targetPreview); }
    }
}
