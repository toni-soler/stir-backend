package org.stir.negotiation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** listingDirection/payerUserId/payeeUserId: who pays whom if the current offer carries an amount,
 * fixed for the whole negotiation (NegotiationService.accept enforces it), so a client can show and
 * bind its acceptance to exactly these parties. */
public record NegotiationDetail(UUID id, UUID tenantId, UUID listingId, UUID initiatorId, UUID ownerId,
        String status, long version, Instant createdAt, Instant updatedAt, List<Offer> offers, UUID agreementId, UUID referenceDefinitionId,
        String listingDirection, UUID payerUserId, UUID payeeUserId) {
    static NegotiationDetail of(Negotiation negotiation, List<Offer> offers, UUID agreementId, EconomicParties parties) {
        return new NegotiationDetail(negotiation.id, negotiation.tenantId, negotiation.listingId,
            negotiation.initiatorId, negotiation.ownerId, negotiation.status, negotiation.version,
            negotiation.createdAt, negotiation.updatedAt, offers, agreementId, negotiation.referenceDefinitionId,
            parties == null ? null : parties.direction(), parties == null ? null : parties.payer(), parties == null ? null : parties.payee());
    }
}
