package org.stir.negotiation;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/agreements") @Validated
public class AgreementController {
    private final AgreementService service;
    public AgreementController(AgreementService service) { this.service=service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context=es.idynamicsax.idax.tenant.TenantContext.get();
        if(context==null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public Page<Agreement> list(@AuthenticationPrincipal CurrentUser user,
        @RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.listMine(user,page,size);
    }
    @GetMapping("/{id}") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public AgreementDetail read(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) { return service.read(user,id); }
}
