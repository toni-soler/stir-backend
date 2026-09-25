package org.stir.reference;

import org.springframework.stereotype.Component;
import org.stir.negotiation.*;

/** Transactional integration boundary: no pricing validation, no osTRIS dependency. */
@Component
public class ReferenceAcceptanceAdapter {
    private final ReferenceService references;
    public ReferenceAcceptanceAdapter(ReferenceService references) { this.references=references; }
    public void accepted(Negotiation negotiation,Offer offer,Agreement agreement,AgreementSnapshot snapshot,boolean acceptorConsent) {
        boolean consent=offer.shareReferenceObservation && acceptorConsent;
        references.freezeContext(negotiation.referenceDefinitionId,agreement.id,snapshot.digestSha256,consent);
        references.record(negotiation.referenceDefinitionId,"AGREEMENT",agreement.id,agreement.initiatorId,agreement.ownerId,
            offer.proposedAmount,offer.quantity,offer.unitLabel,offer.proposedUnitRef,consent,agreement.createdAt);
    }
    public void proposed(Negotiation negotiation,Offer offer) {
        references.record(negotiation.referenceDefinitionId,"PROPOSAL",offer.id,negotiation.initiatorId,negotiation.ownerId,
            offer.proposedAmount,offer.quantity,offer.unitLabel,offer.proposedUnitRef,false,offer.createdAt);
    }
}
