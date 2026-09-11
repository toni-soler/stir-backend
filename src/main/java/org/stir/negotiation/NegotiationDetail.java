package org.stir.negotiation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NegotiationDetail(UUID id, UUID tenantId, UUID listingId, UUID initiatorId, UUID ownerId,
        String status, long version, Instant createdAt, Instant updatedAt, List<Offer> offers, UUID agreementId) {
    static NegotiationDetail of(Negotiation negotiation, List<Offer> offers, UUID agreementId) {
        return new NegotiationDetail(negotiation.id, negotiation.tenantId, negotiation.listingId,
            negotiation.initiatorId, negotiation.ownerId, negotiation.status, negotiation.version,
            negotiation.createdAt, negotiation.updatedAt, offers, agreementId);
    }
}
