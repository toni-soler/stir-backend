package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/governance")
public class SevenKeysController {
    private final SevenKeysService service;
    private final WebAuthnCredentialService webauthn;
    public SevenKeysController(SevenKeysService service, WebAuthnCredentialService webauthn) { this.service=service; this.webauthn=webauthn; }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }
    // Same permission every other Seven Keys ceremony action requires in this controller - a
    // WebAuthn credential only ever becomes constitutionally meaningful once its resulting public
    // key is submitted as part of a bootstrap/proposal payload through the endpoints below, exactly
    // like a locally-generated Ed25519 key today; registering one is not itself a governance act.
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
    @PostMapping("/{community}/proposals") @PreAuthorize("@permissionService.hasPermission('stir.references.propose')")
    public Object propose(@AuthenticationPrincipal CurrentUser user,@PathVariable UUID community,
                          @RequestBody SevenKeysService.ProposalInput body) { return service.propose(user,community,body); }
    @GetMapping("/proposals/{id}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object proposal(@PathVariable UUID id) { return service.publicProposal(id); }
    @GetMapping("/proposals/{id}/signing-payload") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object signingPayload(@PathVariable UUID id) { return service.proposal(id); }
    @PostMapping("/proposals/{id}/signatures") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object sign(@PathVariable UUID id,@RequestBody SevenKeysService.SignatureInput body) { return service.sign(id,body); }
    @PostMapping("/proposals/{id}/activate") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object activate(@PathVariable UUID id,@RequestBody SevenKeysService.ExecutionInput body) { return service.activate(id,body); }
    @PostMapping("/{community}/emergency-suspensions") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object suspend(@PathVariable UUID community,@RequestBody SevenKeysService.SuspensionInput body) { return service.suspend(community,body); }
}
