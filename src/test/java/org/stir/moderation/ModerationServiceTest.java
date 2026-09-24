package org.stir.moderation;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import org.stir.listing.Listing;
import org.stir.listing.ListingRepository;
import org.stir.participant.ParticipantProfileRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModerationServiceTest {
    final UUID tenant = UUID.randomUUID(), reporter = UUID.randomUUID(), moderator = UUID.randomUUID();
    ContentReportRepository reports; ListingRepository listings; ParticipantProfileRepository profiles; ModerationService service;
    Listing listing; CurrentUser reporterUser, moderatorUser;

    @BeforeEach void setup() {
        reports = mock(ContentReportRepository.class); listings = mock(ListingRepository.class); profiles = mock(ParticipantProfileRepository.class);
        service = new ModerationService(reports, listings, profiles);
        when(reports.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        listing = new Listing(); listing.id = UUID.randomUUID(); listing.tenantId = tenant; listing.status = "ACTIVE"; listing.title = "Chair";
        when(listings.findByIdAndTenantId(listing.id, tenant)).thenReturn(Optional.of(listing));

        reporterUser = mock(CurrentUser.class); when(reporterUser.getUserId()).thenReturn(reporter);
        moderatorUser = mock(CurrentUser.class); when(moderatorUser.getUserId()).thenReturn(moderator);
        TenantContext.set(new TenantContext(tenant, null, reporter, "test", TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void reportRejectsAnUnknownListing() {
        var error = assertThrows(ResponseStatusException.class, () -> service.report(reporterUser, "LISTING", UUID.randomUUID(), "spam"));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test void reportRejectsAnUnknownTargetType() {
        var error = assertThrows(ResponseStatusException.class, () -> service.report(reporterUser, "TRADE", listing.id, "spam"));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test void hideAndResolveHidesTheListingWithoutTouchingItsOtherState() {
        var report = new ContentReport(); report.id = UUID.randomUUID(); report.tenantId = tenant;
        report.targetType = "LISTING"; report.targetId = listing.id; report.status = "OPEN";
        when(reports.findByIdAndTenantId(report.id, tenant)).thenReturn(Optional.of(report));

        var result = service.hideAndResolve(moderatorUser, report.id);

        assertTrue(listing.hiddenByModerator);
        assertEquals("ACTIVE", listing.status, "moderation hides a listing without touching its owner-controlled ACTIVE/CLOSED status");
        assertEquals("RESOLVED", result.status());
    }

    @Test void resolvingAnAlreadyResolvedReportFails() {
        var report = new ContentReport(); report.id = UUID.randomUUID(); report.tenantId = tenant; report.status = "RESOLVED";
        when(reports.findByIdAndTenantId(report.id, tenant)).thenReturn(Optional.of(report));
        var error = assertThrows(ResponseStatusException.class, () -> service.dismiss(moderatorUser, report.id));
        assertEquals(409, error.getStatusCode().value());
    }

    @Test void restoreListingClearsTheHiddenFlag() {
        listing.hiddenByModerator = true;
        service.restoreListing(moderatorUser, listing.id);
        assertFalse(listing.hiddenByModerator);
    }

    @Test void openReportsCarryTheListingTitleSoAModeratorCanRecognizeTheTargetBeforeDeciding() {
        var report = new ContentReport(); report.id = UUID.randomUUID(); report.tenantId = tenant; report.reporterUserId = reporter;
        report.targetType = "LISTING"; report.targetId = listing.id; report.reason = "spam"; report.status = "OPEN";
        when(reports.findByTenantIdAndStatusOrderByCreatedAtDesc(eq(tenant), eq("OPEN"), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(report)));

        var page = service.openReports(0, 20);

        assertEquals("Chair", page.getContent().get(0).targetPreview());
    }

    @Test void aReportOfADeletedTargetStillListsInsteadOfFailingTheWholeQueue() {
        var report = new ContentReport(); report.id = UUID.randomUUID(); report.tenantId = tenant; report.reporterUserId = reporter;
        report.targetType = "LISTING"; report.targetId = UUID.randomUUID(); report.reason = "spam"; report.status = "OPEN";
        when(reports.findByTenantIdAndStatusOrderByCreatedAtDesc(eq(tenant), eq("OPEN"), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(report)));

        var page = service.openReports(0, 20);

        assertNull(page.getContent().get(0).targetPreview());
    }
}
