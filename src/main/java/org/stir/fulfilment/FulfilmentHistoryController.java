package org.stir.fulfilment;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Contextual fulfilment history on profiles: described facts, no score, opt-in (AGREEMENT_FULFILMENT.md). */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/participants")
public class FulfilmentHistoryController {
    private final FulfilmentService service;
    public FulfilmentHistoryController(FulfilmentService service) { this.service = service; }

    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping("/{userId}/fulfilment-history") @PreAuthorize("@permissionService.hasPermission('stir.participants.read')")
    public FulfilmentService.HistoryView history(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID userId) {
        return service.history(user, userId);
    }

    public record SharingRequest(boolean share) {}
    @PutMapping("/me/fulfilment-history-sharing") @PreAuthorize("@permissionService.hasPermission('stir.participants.update')")
    public FulfilmentService.HistoryView share(@AuthenticationPrincipal CurrentUser user, @RequestBody SharingRequest request) {
        return service.share(user, request != null && request.share());
    }
}
