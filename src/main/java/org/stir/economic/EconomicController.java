package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/economic")
public class EconomicController {
    private final EconomicActivationService service;
    public EconomicController(EconomicActivationService service) { this.service = service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping("/marketplace") @PreAuthorize("@permissionService.hasPermission('stir.economic.read')")
    public EconomicActivationService.MarketplaceEconomicView marketplace() { return service.marketplace(); }

    @PostMapping("/marketplace/bootstrap") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    public EconomicActivationService.MarketplaceEconomicView bootstrap(@Valid @RequestBody BootstrapRequest request) {
        return service.bootstrap(request.communityName(), request.unitCode(), request.unitScale());
    }

    @GetMapping("/me") @PreAuthorize("@permissionService.hasPermission('stir.economic.read')")
    public EconomicActivationService.ParticipantEconomicView me(@AuthenticationPrincipal CurrentUser user) { return service.me(user); }

    @PostMapping("/activate") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    public EconomicActivationService.ParticipantEconomicView activate(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ActivateRequest request) {
        return service.activate(user, request.publicKeyBase64url());
    }

    public record BootstrapRequest(@NotBlank @Size(max = 160) String communityName,
        @NotBlank @Size(max = 16) String unitCode, @NotNull @Min(0) @Max(18) Integer unitScale) {}
    public record ActivateRequest(@NotBlank String publicKeyBase64url) {}
}
