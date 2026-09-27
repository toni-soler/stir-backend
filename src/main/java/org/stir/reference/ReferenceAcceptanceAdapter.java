package org.stir.reference;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.stir.negotiation.*;

/** Transactional integration boundary: no pricing validation, no osTRIS dependency. */
@Component
public class ReferenceAcceptanceAdapter {
    private final ReferenceService references;
    private final ConsentService consent;
    public ReferenceAcceptanceAdapter(ReferenceService references,ConsentService consent) { this.references=references; this.consent=consent; }
    public void accepted(Negotiation negotiation,Offer offer,Agreement agreement,AgreementSnapshot snapshot,boolean acceptorConsent) {
        boolean bilateralConsent=offer.shareReferenceObservation && acceptorConsent;
        references.freezeContext(negotiation.referenceDefinitionId,agreement.id,snapshot.digestSha256,bilateralConsent);
        UUID observationId=references.record(negotiation.referenceDefinitionId,"AGREEMENT",agreement.id,agreement.initiatorId,agreement.ownerId,
            offer.proposedAmount,offer.quantity,offer.unitLabel,offer.proposedUnitRef,bilateralConsent,agreement.createdAt);
        // Each party's own decision is captured individually and independently withdrawable later -
        // not just the AND'ed aggregate_consent frozen on the observation itself. The offeror's
        // decision is whatever they set when proposing this exact offer; the acceptor's is the flag
        // they passed to accept() just now. Neither party's row depends on the other's value.
        UUID acceptorId=negotiation.initiatorId.equals(offer.authorId)?negotiation.ownerId:negotiation.initiatorId;
        consent.capture(observationId,negotiation.referenceDefinitionId,offer.authorId,offer.shareReferenceObservation);
        consent.capture(observationId,negotiation.referenceDefinitionId,acceptorId,acceptorConsent);
    }
    public void proposed(Negotiation negotiation,Offer offer) {
        references.record(negotiation.referenceDefinitionId,"PROPOSAL",offer.id,negotiation.initiatorId,negotiation.ownerId,
            offer.proposedAmount,offer.quantity,offer.unitLabel,offer.proposedUnitRef,false,offer.createdAt);
    }
}
