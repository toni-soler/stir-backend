package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Case details contain private observation links; only integrity publishers can inspect them. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/integrity")
public class MarketIntegrityController {
    private final MarketIntegrityService service;
    public MarketIntegrityController(MarketIntegrityService service) {this.service=service;}
    // See ReferenceController.tenant(): idax-core's PermissionService grants every permission to
    // authentication.principal.superuser unconditionally, so @PreAuthorize alone would let a
    // platform SuperAdmin raise signals and decide market integrity cases purely by being
    // SuperAdmin. Reject that here, once, for the whole controller.
    @ModelAttribute public void tenant(@PathVariable UUID tenantId, @AuthenticationPrincipal CurrentUser user) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
        if(user!=null && user.isSuperuser()) throw new AccessDeniedException("Platform administration does not grant community governance");
    }
    @PostMapping("/signals") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object signal(@AuthenticationPrincipal CurrentUser user,@RequestBody MarketIntegrityService.SignalRequest body) {
        return service.signal(user,body);
    }
    @PostMapping("/cases/{id}/decisions") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object decide(@AuthenticationPrincipal CurrentUser user,@PathVariable UUID id,@RequestBody MarketIntegrityService.DecisionRequest body) {
        return service.decide(user,id,body);
    }
    @GetMapping("/definitions/{id}/cases") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object cases(@PathVariable UUID id) {return service.cases(id);}
    @GetMapping("/cases/{id}/history") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object history(@PathVariable UUID id) {return service.history(id);}
}
