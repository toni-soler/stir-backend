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
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }
    // requireCommunityAuthority(user) is the same shared ReferenceService check signal()/decide()
    // call again themselves - see ReferenceService.requireCommunityAuthority. Fast defense-in-depth
    // here, never a second implementation. cases()/history() stay permission-gated only, same as
    // ReferenceController's plain reads.
    @PostMapping("/signals") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object signal(@AuthenticationPrincipal CurrentUser user,@RequestBody MarketIntegrityService.SignalRequest body) {
        ReferenceService.requireCommunityAuthority(user); return service.signal(user,body);
    }
    @PostMapping("/cases/{id}/decisions") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object decide(@AuthenticationPrincipal CurrentUser user,@PathVariable UUID id,@RequestBody MarketIntegrityService.DecisionRequest body) {
        ReferenceService.requireCommunityAuthority(user); return service.decide(user,id,body);
    }
    @GetMapping("/definitions/{id}/cases") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object cases(@PathVariable UUID id) {return service.cases(id);}
    @GetMapping("/cases/{id}/history") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object history(@PathVariable UUID id) {return service.history(id);}
}
