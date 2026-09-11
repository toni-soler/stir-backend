package org.stir.participant;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import es.idynamicsax.idax.security.CurrentUser;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/participants")
public class ParticipantProfileController {
    private final ParticipantProfileService service;
    public ParticipantProfileController(ParticipantProfileService service) { this.service=service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context=es.idynamicsax.idax.tenant.TenantContext.get();
        if(context==null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping("/me") @PreAuthorize("@permissionService.hasPermission('stir.participants.read')")
    public ParticipantProfileView me(@AuthenticationPrincipal CurrentUser user) { return service.me(user); }
    @PutMapping("/me") @PreAuthorize("@permissionService.hasPermission('stir.participants.update')")
    public ParticipantProfileView updateMe(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ParticipantProfileRequest request) { return service.upsertMe(user,request); }
    @GetMapping("/{userId}") @PreAuthorize("@permissionService.hasPermission('stir.participants.read')")
    public ParticipantProfileView view(@PathVariable UUID userId) { return service.view(userId); }
}
