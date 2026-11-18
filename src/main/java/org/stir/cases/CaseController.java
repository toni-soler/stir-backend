package org.stir.cases;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.stir.economic.OstrisClient;
import org.stir.ratelimit.RateLimited;

/**
 * Agreement cases (AGREEMENT_CASES.md). Reads need stir.agreements.read and writes need
 * stir.agreements.fulfil; who may act on a case (party, reviewer, community authority) is decided
 * in the services, so a platform superuser who is none of those is refused like anyone else.
 */
@RestController
@RequestMapping("/api/stir/tenants/{tenantId}")
public class CaseController {
    private final CaseService cases;
    private final CaseReviewService review;
    private final CaseRemedyService remedies;

    public CaseController(CaseService cases, CaseReviewService review, CaseRemedyService remedies) {
        this.cases = cases; this.review = review; this.remedies = remedies;
    }

    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping("/case-review") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public CaseReviewService.ReviewView review(@AuthenticationPrincipal CurrentUser user) { return review.view(user); }

    @PutMapping("/case-review") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseReviewService.ReviewView configure(@AuthenticationPrincipal CurrentUser user, @RequestBody CaseReviewService.ConfigureRequest body) {
        return review.configure(user, body);
    }

    @GetMapping("/cases") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public CaseService.CaseLists list(@AuthenticationPrincipal CurrentUser user) { return cases.list(user); }

    @GetMapping("/agreements/{agreementId}/cases") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public List<CaseService.CaseSummary> forAgreement(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId) {
        return cases.forAgreement(user, agreementId);
    }

    @PostMapping("/agreements/{agreementId}/cases") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    @RateLimited(key = "caseOpen", limit = 10, windowSeconds = 60)
    public ResponseEntity<CaseService.CaseView> open(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID agreementId,
                                                     @RequestBody CaseService.OpenRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cases.open(user, agreementId, body));
    }

    @GetMapping("/cases/{caseId}") @PreAuthorize("@permissionService.hasPermission('stir.agreements.read')")
    public CaseService.CaseView view(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId) { return cases.view(user, caseId); }

    @PostMapping("/cases/{caseId}/evidence") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    @RateLimited(key = "caseEvidence", limit = 30, windowSeconds = 60)
    public CaseService.CaseView evidence(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @RequestBody CaseService.EvidenceRequest body) {
        return cases.addEvidence(user, caseId, body);
    }

    @PostMapping("/cases/{caseId}/take") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView take(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId) { return cases.take(user, caseId); }

    @PostMapping("/cases/{caseId}/containment") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView contain(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @RequestBody CaseService.ContainmentRequest body) {
        return cases.contain(user, caseId, body);
    }

    @PostMapping("/cases/{caseId}/containment/lift") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView lift(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId) { return cases.lift(user, caseId); }

    @PostMapping("/cases/{caseId}/findings") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView finding(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @RequestBody CaseService.FindingRequest body) {
        return cases.issueFinding(user, caseId, body);
    }

    @PostMapping("/cases/{caseId}/attributions") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView attribution(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @RequestBody CaseService.AttributionRequest body) {
        return cases.issueAttribution(user, caseId, body);
    }

    @PostMapping("/cases/{caseId}/sanctions") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView sanction(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @RequestBody CaseService.SanctionRequest body) {
        return cases.issueSanction(user, caseId, body);
    }

    @PostMapping("/cases/{caseId}/decisions/{decisionId}/waive") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView waive(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID decisionId) {
        return cases.waive(user, caseId, decisionId);
    }

    @PostMapping("/cases/{caseId}/decisions/{decisionId}/appeal") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView appeal(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID decisionId,
                                       @RequestBody CaseService.AppealRequest body) {
        return cases.appeal(user, caseId, decisionId, body);
    }

    @PostMapping("/cases/{caseId}/decisions/{decisionId}/finalize") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView finalizeDecision(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID decisionId) {
        return cases.finalizeDecision(user, caseId, decisionId);
    }

    @PostMapping("/cases/{caseId}/appeals/{appealId}/decide") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView decide(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID appealId,
                                       @RequestBody CaseService.AppealDecisionRequest body) {
        return cases.decideAppeal(user, caseId, appealId, body);
    }

    @PostMapping("/cases/{caseId}/withdraw") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView withdraw(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId,
                                         @RequestBody(required = false) CaseService.WithdrawRequest body) {
        return cases.withdraw(user, caseId, body);
    }

    @PostMapping("/cases/{caseId}/remedies") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView prepare(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId) {
        return remedies.prepare(user, caseId);
    }

    @GetMapping("/cases/{caseId}/remedies/{remedyId}/signing-payload") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public OstrisClient.GovernanceSigningPayload signingPayload(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID remedyId) {
        return remedies.signingPayload(user, caseId, remedyId);
    }

    @PostMapping("/cases/{caseId}/remedies/{remedyId}/signatures") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView sign(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID remedyId,
                                     @RequestBody CaseRemedyService.SignatureRequest body) {
        return remedies.sign(user, caseId, remedyId, body);
    }

    @PostMapping("/cases/{caseId}/remedies/{remedyId}/commit") @PreAuthorize("@permissionService.hasPermission('stir.agreements.fulfil')")
    public CaseService.CaseView commit(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID caseId, @PathVariable UUID remedyId) {
        return remedies.commit(user, caseId, remedyId);
    }
}
