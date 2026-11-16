package org.stir.reference;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only Seven Keys surface served by the ordinary STIR runtime. Every constitutional write
 * (WebAuthn registration, bootstrap, proposal, signature, activation, suspension) is served only by
 * the governed-state verifier process - see SevenKeysGovernedWriteController. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/governance")
public class SevenKeysController {
    private final SevenKeysService service;
    public SevenKeysController(SevenKeysService service) { this.service=service; }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping("/{community}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object view(@PathVariable UUID community) { return service.view(community); }
    @GetMapping("/{community}/events") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object events(@PathVariable UUID community) { return service.events(community); }
    @GetMapping("/{community}/audit") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object audit(@PathVariable UUID community) { return service.audit(community); }
    @GetMapping("/{community}/credentials") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object credentials(@PathVariable UUID community) { return service.credentialHistory(community); }
    @GetMapping("/{community}/proposals") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object proposals(@PathVariable UUID community) { return service.publicProposals(community); }
    @GetMapping("/proposals/{id}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object proposal(@PathVariable UUID id) { return service.publicProposal(id); }
    @GetMapping("/proposals/{id}/signing-payload") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object signingPayload(@PathVariable UUID id) { return service.proposal(id); }
}
