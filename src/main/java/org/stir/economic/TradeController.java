package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.stir.ratelimit.RateLimited;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/agreements/{agreementId}/trade")
public class TradeController {
    private final TradeService service;
    public TradeController(TradeService service) { this.service = service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.economic.read')")
    public TradeService.TradeView find(@PathVariable UUID agreementId, @AuthenticationPrincipal CurrentUser user) { return service.find(user, agreementId); }

    @GetMapping("/signing-payload") @PreAuthorize("@permissionService.hasPermission('stir.economic.read')")
    public TradeService.SigningPayload signingPayload(@PathVariable UUID agreementId, @AuthenticationPrincipal CurrentUser user) { return service.signingPayload(user, agreementId); }

    @PostMapping("/activate") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    public TradeService.TradeView activate(@PathVariable UUID agreementId, @AuthenticationPrincipal CurrentUser user) { return service.activate(user, agreementId); }

    @PostMapping("/authorizations") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    @RateLimited(key = "tradeAuthorize", limit = 10, windowSeconds = 60)
    public TradeService.TradeView authorize(@PathVariable UUID agreementId, @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody AuthorizeRequest request) {
        return service.authorize(user, agreementId, request.credentialId(), request.signatureBase64url());
    }

    @PostMapping("/commit") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    @RateLimited(key = "tradeCommit", limit = 10, windowSeconds = 60)
    public TradeService.TradeView commit(@PathVariable UUID agreementId, @AuthenticationPrincipal CurrentUser user) { return service.commit(user, agreementId); }

    @PostMapping("/sync") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    public TradeService.TradeView sync(@PathVariable UUID agreementId, @AuthenticationPrincipal CurrentUser user) { return service.sync(user, agreementId); }

    public record AuthorizeRequest(@NotNull UUID credentialId, @NotBlank String signatureBase64url) {}
}
