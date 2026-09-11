package org.stir.negotiation;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.CREATED;

/** Opens a Negotiation with its first Offer, directed at a Listing. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/listings/{listingId}/offers")
public class OfferController {
    private final NegotiationService service;
    public OfferController(NegotiationService service) { this.service=service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context=es.idynamicsax.idax.tenant.TenantContext.get();
        if(context==null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }
    @PostMapping @ResponseStatus(CREATED) @PreAuthorize("@permissionService.hasPermission('stir.negotiations.create')")
    public NegotiationDetail open(@PathVariable UUID listingId, @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody OfferRequest request) {
        return service.open(user,listingId,request);
    }
}
