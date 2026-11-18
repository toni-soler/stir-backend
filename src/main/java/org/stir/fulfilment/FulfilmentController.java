package org.stir.fulfilment;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.stir.economic.ExecutionAttemptRecorder;
import org.stir.ratelimit.RateLimited;

/** Fulfilment of one Agreement (AGREEMENT_FULFILMENT.md). Party-only: FulfilmentService answers 404 to anyone else. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/agreements/{agreementId}")
public class FulfilmentController {
    private final FulfilmentService service;
    private final ExecutionAttemptRecorder attempts;
    public FulfilmentController(FulfilmentService service, ExecutionAttemptRecorder attempts) { this.service = service; this.attempts = attempts; }

    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping("/obligations") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public FulfilmentService.FulfilmentView obligations(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId) {
        return service.view(user, agreementId);
    }

    public record DeclarationRequest(String type, String note) {}
    @PostMapping("/obligations/{obligationId}/events") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    @RateLimited(key = "obligationDeclare", limit = 30, windowSeconds = 60)
    public FulfilmentService.FulfilmentView declare(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId,
                                                    @PathVariable UUID obligationId, @RequestBody DeclarationRequest request) {
        return service.declare(user, agreementId, obligationId, request == null ? null : request.type(), request == null ? null : request.note());
    }

    @GetMapping("/amendments") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public List<FulfilmentService.AmendmentView> amendments(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId) {
        return service.amendments(user, agreementId);
    }

    @PostMapping("/amendments") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')") @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(key = "amendmentPropose", limit = 10, windowSeconds = 60)
    public FulfilmentService.AmendmentView propose(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId,
                                                   @RequestBody FulfilmentService.AmendmentRequest request) {
        return service.propose(user, agreementId, request);
    }

    @PostMapping("/amendments/{amendmentId}/accept") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public FulfilmentService.AmendmentView accept(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId, @PathVariable UUID amendmentId) {
        return service.accept(user, agreementId, amendmentId);
    }

    @PostMapping("/amendments/{amendmentId}/reject") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public FulfilmentService.AmendmentView reject(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId, @PathVariable UUID amendmentId) {
        return service.reject(user, agreementId, amendmentId);
    }

    /** Every recorded execution attempt with its typed outcome, oldest first. */
    @GetMapping("/trade/attempts") @PreAuthorize("@permissionService.hasPermission('stir.economic.read')")
    public List<ExecutionAttemptRecorder.AttemptView> executionAttempts(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId) {
        service.view(user, agreementId); // party check, same 404 as every other route here
        return attempts.list(service.tenant(), agreementId);
    }
}
