package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Retention policy and the one real deletion-lifecycle action (anonymization), publisher/community-
 * authority gated exactly like ReferenceController's policy endpoints (CONSENT_RETENTION.md).
 * requireCommunityAuthority is checked again here as defense-in-depth, same shared method the
 * service itself calls - never a second implementation. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/retention")
public class RetentionController {
    private final RetentionService service;
    public RetentionController(RetentionService service) { this.service=service; }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping("/policy/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object currentPolicy(@PathVariable UUID communityId) { return service.currentPolicy(communityId); }
    @PostMapping("/policy/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.retention.manage')")
    public Object setPolicy(@PathVariable UUID communityId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody PolicyRequest r) {
        ReferenceService.requireCommunityAuthority(user);
        return service.setPolicy(user,communityId,new RetentionService.PolicyRequest(r.retentionPeriodDays(),r.explanation()));
    }
    @GetMapping("/observation/{observationId}/status") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object status(@PathVariable UUID observationId) { return service.statusFor(observationId); }
    @GetMapping("/definition/{definitionId}/due") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object due(@PathVariable UUID definitionId) { return service.dueForAnonymization(definitionId); }
    @PostMapping("/observation/{observationId}/anonymize") @PreAuthorize("@permissionService.hasPermission('stir.retention.manage')")
    public Object anonymize(@PathVariable UUID observationId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody AnonymizeRequest r) {
        ReferenceService.requireCommunityAuthority(user); return service.anonymize(user,observationId,r.reason());
    }
    public record PolicyRequest(@Min(90) int retentionPeriodDays,@NotBlank @Size(max=2000) String explanation) {}
    public record AnonymizeRequest(@NotBlank @Size(max=2000) String reason) {}
}
