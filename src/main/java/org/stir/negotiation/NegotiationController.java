package org.stir.negotiation;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/negotiations") @Validated
public class NegotiationController {
    private final NegotiationService service;
    public NegotiationController(NegotiationService service) { this.service=service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context=es.idynamicsax.idax.tenant.TenantContext.get();
        if(context==null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.negotiations.read')")
    public Page<Negotiation> list(@AuthenticationPrincipal CurrentUser user,
        @RequestParam(required=false) @Pattern(regexp="OPEN|ACCEPTED|DECLINED") String status,
        @RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.listMine(user,status,page,size);
    }
    @GetMapping("/{id}") @PreAuthorize("@permissionService.hasPermission('stir.negotiations.read')")
    public NegotiationDetail read(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) { return service.read(user,id); }
    @PostMapping("/{id}/offers") @PreAuthorize("@permissionService.hasPermission('stir.negotiations.update')")
    public NegotiationDetail counter(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CounterRequest request) {
        return service.counter(user,id,request.offer(),request.expectedVersion());
    }

    @PostMapping("/{id}/accept") @PreAuthorize("@permissionService.hasPermission('stir.negotiations.update')")
    public AgreementDetail accept(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody NegotiationActionRequest request) {
        return service.accept(user,id,request.offerId(),request.expectedVersion(),request.shareReferenceObservation());
    }
    @PostMapping("/{id}/decline") @PreAuthorize("@permissionService.hasPermission('stir.negotiations.update')")
    public NegotiationDetail decline(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody NegotiationActionRequest request) {
        return service.decline(user,id,request.expectedVersion());
    }
}
