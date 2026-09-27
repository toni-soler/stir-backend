package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Self-service consent lifecycle: any party can see and withdraw their own consent, regardless of
 * publisher/governance authority - this is a personal right, not a community decision
 * (CONSENT_RETENTION.md). Grant/decline itself is captured automatically inside the existing
 * Agreement-acceptance flow (ReferenceAcceptanceAdapter); there is no endpoint here to grant or
 * decline directly, only to view and withdraw. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/consent")
public class ConsentController {
    private final ConsentService service;
    public ConsentController(ConsentService service) { this.service=service; }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping("/mine") @PreAuthorize("@permissionService.hasPermission('stir.consent.manage')")
    public Object mine(@AuthenticationPrincipal CurrentUser user) { return service.mine(user); }
    @GetMapping("/{consentId}") @PreAuthorize("@permissionService.hasPermission('stir.consent.manage')")
    public Object view(@PathVariable UUID consentId,@AuthenticationPrincipal CurrentUser user) { return service.view(user,consentId); }
    @PostMapping("/{consentId}/withdraw") @PreAuthorize("@permissionService.hasPermission('stir.consent.manage')")
    public Object withdraw(@PathVariable UUID consentId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody WithdrawRequest r) {
        return service.withdraw(user,consentId,r.reason());
    }
    public record WithdrawRequest(@Size(max=2000) String reason) {}
}
