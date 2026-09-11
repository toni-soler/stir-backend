package org.stir.negotiation;

import java.time.Instant;
import java.util.UUID;

public record AgreementDetail(UUID id, UUID tenantId, UUID negotiationId, UUID listingId, UUID offerId,
        UUID initiatorId, UUID ownerId, String economicPhase, Instant createdAt, AgreementSnapshotView snapshot) {
    static AgreementDetail of(Agreement agreement, AgreementSnapshot snapshot) {
        return new AgreementDetail(agreement.id, agreement.tenantId, agreement.negotiationId, agreement.listingId,
            agreement.offerId, agreement.initiatorId, agreement.ownerId, agreement.economicPhase,
            agreement.createdAt, AgreementSnapshotView.of(snapshot));
    }
}
