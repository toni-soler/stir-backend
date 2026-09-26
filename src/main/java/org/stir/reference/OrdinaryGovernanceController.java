package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Ordinary community governance: quorum-based, explicitly separate from Seven Keys
 * (ORDINARY_GOVERNANCE.md). requireCommunityAuthority is checked again here as defense-in-depth,
 * same shared method OrdinaryGovernanceService itself calls - never a second implementation. */
@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references/governance/ordinary")
public class OrdinaryGovernanceController {
    private final OrdinaryGovernanceService service;
    public OrdinaryGovernanceController(OrdinaryGovernanceService service) { this.service=service; }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping("/settings/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object settings(@PathVariable UUID communityId) { return service.settings(communityId); }
    @PutMapping("/settings/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.governance.manage')")
    public Object setEnabled(@PathVariable UUID communityId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody SettingsRequest r) {
        ReferenceService.requireCommunityAuthority(user); return service.setEnabled(user,communityId,r.enabled());
    }

    @GetMapping("/members/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.governance.vote')")
    public Object members(@PathVariable UUID communityId) { return service.members(communityId); }
    @PostMapping("/members/{communityId}/{userId}") @PreAuthorize("@permissionService.hasPermission('stir.governance.manage')")
    public Object addMember(@PathVariable UUID communityId,@PathVariable UUID userId,@AuthenticationPrincipal CurrentUser user) {
        ReferenceService.requireCommunityAuthority(user); return service.addMember(user,communityId,userId);
    }
    @DeleteMapping("/members/{communityId}/{userId}") @PreAuthorize("@permissionService.hasPermission('stir.governance.manage')")
    public Object removeMember(@PathVariable UUID communityId,@PathVariable UUID userId,@AuthenticationPrincipal CurrentUser user) {
        ReferenceService.requireCommunityAuthority(user); return service.removeMember(user,communityId,userId);
    }

    @GetMapping("/policy/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object currentPolicy(@PathVariable UUID communityId) { return service.currentPolicy(communityId); }
    @PostMapping("/policy/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.governance.manage')")
    public Object setPolicy(@PathVariable UUID communityId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody PolicyRequest r) {
        ReferenceService.requireCommunityAuthority(user);
        return service.setPolicy(user,communityId,new OrdinaryGovernanceService.PolicyRequest(r.quorumNumerator(),r.quorumDenominator(),
            r.approvalNumerator(),r.approvalDenominator(),r.votingWindowHours(),r.abstentionRule(),r.explanation()));
    }

    @PostMapping("/proposals/{definitionId}/reference/{referenceProposalId}") @PreAuthorize("@permissionService.hasPermission('stir.governance.vote')")
    public Object proposePublishReference(@PathVariable UUID definitionId,@PathVariable UUID referenceProposalId,@AuthenticationPrincipal CurrentUser user) {
        ReferenceService.requireCommunityAuthority(user); return service.proposePublishReference(user,definitionId,referenceProposalId);
    }
    @PostMapping("/proposals/{definitionId}/policy") @PreAuthorize("@permissionService.hasPermission('stir.governance.vote')")
    public Object proposePolicyChange(@PathVariable UUID definitionId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody ReferenceController.PolicyRequest r) {
        ReferenceService.requireCommunityAuthority(user); return service.proposePolicyChange(user,definitionId,r);
    }

    @GetMapping("/proposals/community/{communityId}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object proposals(@PathVariable UUID communityId) { return service.proposals(communityId); }
    @GetMapping("/proposal/{proposalId}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object proposal(@PathVariable UUID proposalId) { return service.proposal(proposalId); }
    @GetMapping("/proposal/{proposalId}/votes") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object votes(@PathVariable UUID proposalId) { return service.votes(proposalId); }
    @GetMapping("/proposal/{proposalId}/electorate") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object electorate(@PathVariable UUID proposalId) { return service.electorate(proposalId); }

    // requireCommunityAuthority below is the same shared check the service itself calls again -
    // fast defense-in-depth, never a second implementation. Being platform SuperAdmin never
    // approves/votes/executes anything here regardless of what stir.governance.* the permission
    // bypass would otherwise grant (GOVERNANCE_CAPTURE_THREAT_MODEL.md).
    @PostMapping("/proposal/{proposalId}/vote") @PreAuthorize("@permissionService.hasPermission('stir.governance.vote')")
    public Object vote(@PathVariable UUID proposalId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody VoteRequest r) {
        ReferenceService.requireCommunityAuthority(user); return service.vote(user,proposalId,r.choice());
    }
    @PostMapping("/proposal/{proposalId}/close") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object close(@PathVariable UUID proposalId) { return service.close(proposalId); }
    @PostMapping("/proposal/{proposalId}/execute") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object execute(@PathVariable UUID proposalId,@AuthenticationPrincipal CurrentUser user) {
        ReferenceService.requireCommunityAuthority(user); return service.execute(user,proposalId);
    }

    public record SettingsRequest(boolean enabled) {}
    public record VoteRequest(@NotBlank @Pattern(regexp="APPROVE|REJECT|ABSTAIN") String choice) {}
    public record PolicyRequest(@Min(1) int quorumNumerator,@Min(1) int quorumDenominator,@Min(1) int approvalNumerator,@Min(1) int approvalDenominator,
        @Min(1) @Max(2160) int votingWindowHours,@NotBlank String abstentionRule,@NotBlank @Size(max=2000) String explanation) {}
}
