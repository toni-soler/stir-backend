package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/references")
public class ReferenceController {
    private final ReferenceService service;
    public ReferenceController(ReferenceService service) { this.service=service; }
    @ModelAttribute public void tenant(@PathVariable UUID tenantId) {
        if(!tenantId.equals(ReferenceService.tenant())) throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object definitions() { return service.definitions(); }
    @GetMapping("/publish-access") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object access() { return Map.of("allowed",true); }
    @PostMapping @PreAuthorize("@permissionService.hasPermission('stir.references.propose')")
    public Object create(@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody DefinitionRequest request) { return service.create(user,request); }
    @GetMapping("/{id}") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object view(@PathVariable UUID id) { return service.view(id); }
    @GetMapping("/{id}/history") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object history(@PathVariable UUID id) { return service.history(id); }
    @GetMapping("/{id}/proposals") @PreAuthorize("@permissionService.hasPermission('stir.references.read')")
    public Object proposals(@PathVariable UUID id) { return service.proposals(id); }
    @PostMapping("/{id}/proposals") @PreAuthorize("@permissionService.hasPermission('stir.references.propose')")
    public Object propose(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody ProposalRequest r) { return service.propose(user,id,r); }
    @PostMapping("/proposals/{id}/publish") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object publish(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody PublishRequest r) { return service.publish(user,id,r.decision()); }
    @PostMapping("/{id}/policies") @PreAuthorize("@permissionService.hasPermission('stir.references.publish')")
    public Object policy(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody PolicyRequest r) { return service.policy(user,id,r); }
    @GetMapping("/agreements/{id}/context") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public Object context(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser user) { return service.agreementContext(user,id); }

    public record DefinitionRequest(@NotBlank @Size(max=160) String name,@NotBlank @Size(max=500) String scope,
        @NotNull @Size(max=20) Map<@Size(max=60) String,@Size(max=200) String> attributes,
        @NotNull @Positive @Digits(integer=14,fraction=4) BigDecimal quantityBasis,@NotBlank @Size(max=40) String quantityUnit) {}
    public record PolicyRequest(@Min(7) @Max(365) int windowDays,@Min(5) @Max(10000) int minimumObservations,
        @Min(6) @Max(10000) int minimumParticipants,@NotNull @DecimalMin("0.1") @DecimalMax("0.5") BigDecimal maximumParticipantShare,
        @Min(1) @Max(365) int freshnessDays,@NotBlank @Size(max=2000) String explanation,
        Boolean independenceChecksRequired,Boolean concentrationChecksRequired,Boolean provenanceRequired,Boolean forceReference) {
        public PolicyRequest(int windowDays,int minimumObservations,int minimumParticipants,
            BigDecimal maximumParticipantShare,int freshnessDays,String explanation) {
            this(windowDays,minimumObservations,minimumParticipants,maximumParticipantShare,freshnessDays,
                explanation,null,null,null,null);
        }
    }
    public record ProposalRequest(@NotNull @Pattern(regexp="VALUE|BAND|CONVENTION|QUALITATIVE") String kind,
        @PositiveOrZero @Digits(integer=16,fraction=2) BigDecimal lowerValue,@PositiveOrZero @Digits(integer=16,fraction=2) BigDecimal upperValue,
        @NotBlank @Size(max=2000) String explanation,@NotBlank @Size(max=100) String origin,@Min(1) @Max(365) int validDays) {}
    public record PublishRequest(@NotBlank @Size(max=2000) String decision) {}
}
