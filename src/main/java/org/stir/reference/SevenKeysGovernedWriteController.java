package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Constitutional governed writes. Registered ONLY in the governed-verifier process, which alone
 * holds the database credential allowed to mutate effective constitutional state. The ordinary STIR
 * runtime does not register these routes at all. Each request is authenticated by the verifier's own
 * idax-core JWT filter; the caller's identity is never supplied as a body field. */
@Profile("governed-verifier")
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/governance")
public class SevenKeysGovernedWriteController {
    private final SevenKeysService service;
    private final WebAuthnCredentialService webauthn;
    public SevenKeysGovernedWriteController(SevenKeysService service, WebAuthnCredentialService webauthn) {
        this.service=service; this.webauthn=webauthn;
    }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }
    @PostMapping("/webauthn/register/begin") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object beginWebauthnRegistration(@AuthenticationPrincipal CurrentUser user,@RequestBody WebAuthnCredentialService.RegistrationRequest body) {
        return webauthn.beginRegistration(user,body);
    }
    public record FinishWebauthnRegistration(UUID authorityId,String context,UUID credentialId,
        String attestationObject,String clientDataJson,String webauthnCredentialId,boolean userVerificationRequired) {}
    @PostMapping("/webauthn/register/finish") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object finishWebauthnRegistration(@AuthenticationPrincipal CurrentUser user,@RequestBody FinishWebauthnRegistration body) {
        return webauthn.finishRegistration(user,new WebAuthnCredentialService.RegistrationRequest(body.authorityId(),body.context(),null),
            new WebAuthnCredentialService.RegistrationResponse(body.credentialId(),body.attestationObject(),body.clientDataJson(),
                body.webauthnCredentialId(),body.userVerificationRequired()));
    }
    @PostMapping("/bootstrap") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object bootstrap(@AuthenticationPrincipal CurrentUser user,@RequestBody SevenKeysService.Bootstrap body) {
        return service.bootstrap(user,body);
    }
    @PostMapping("/{community}/proposals") @PreAuthorize("@permissionService.hasPermission('stir.references.propose')")
    public Object propose(@AuthenticationPrincipal CurrentUser user,@PathVariable UUID community,
                          @RequestBody SevenKeysService.ProposalInput body) { return service.propose(user,community,body); }
    @PostMapping("/proposals/{id}/signatures") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object sign(@PathVariable UUID id,@RequestBody SevenKeysService.SignatureInput body) { return service.sign(id,body); }
    @PostMapping("/proposals/{id}/activate") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object activate(@PathVariable UUID id,@RequestBody SevenKeysService.ExecutionInput body) { return service.activate(id,body); }
    @PostMapping("/{community}/emergency-suspensions") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object suspend(@PathVariable UUID community,@RequestBody SevenKeysService.SuspensionInput body) { return service.suspend(community,body); }
}
