package org.stir.moderation;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.CREATED;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}")
public class ModerationController {
    private final ModerationService service;
    public ModerationController(ModerationService service) { this.service = service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @PostMapping("/reports") @ResponseStatus(CREATED) @PreAuthorize("@permissionService.hasPermission('stir.content.report')")
    public ModerationService.ReportView report(@AuthenticationPrincipal CurrentUser user, @RequestBody ReportRequest request) {
        return service.report(user, request.targetType(), request.targetId(), request.reason());
    }

    /** A tiny, STIR-owned probe the frontend calls once to decide whether to show the moderation
     * nav link at all: the platform's own client-side AuthContext.hasPermission() only reflects
     * IDAX-core permissions (populated from GET /api/me, which 403s for custom tenant roles that
     * lack a baseline platform permission), so it cannot answer "can this user moderate" for a
     * module-defined permission like stir.moderation.manage. This endpoint carries no data of its
     * own - a 200 IS the answer; @PreAuthorize does the actual check. */
    @GetMapping("/moderation/access") @PreAuthorize("@permissionService.hasPermission('stir.moderation.manage')")
    public void access() {}

    @GetMapping("/moderation/reports") @PreAuthorize("@permissionService.hasPermission('stir.moderation.manage')")
    public Page<ModerationService.ReportView> openReports(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.openReports(page, size);
    }

    @PostMapping("/moderation/reports/{id}/dismiss") @PreAuthorize("@permissionService.hasPermission('stir.moderation.manage')")
    public ModerationService.ReportView dismiss(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        return service.dismiss(user, id);
    }

    @PostMapping("/moderation/reports/{id}/hide") @PreAuthorize("@permissionService.hasPermission('stir.moderation.manage')")
    public ModerationService.ReportView hide(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        return service.hideAndResolve(user, id);
    }

    @PostMapping("/moderation/listings/{id}/restore") @PreAuthorize("@permissionService.hasPermission('stir.moderation.manage')")
    public void restore(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        service.restoreListing(user, id);
    }

    public record ReportRequest(@NotBlank String targetType, @NotNull UUID targetId, @NotBlank @Size(max = 500) String reason) {}
}
