package org.stir.negotiation;

import java.util.UUID;

/** Who pays whom under a listing direction. OFFER: the owner provides, so the initiator pays the
 * owner. WANTED: the owner is requesting, so the owner pays the initiator who provides it. */
record EconomicParties(String direction, UUID payer, UUID payee) {
    static EconomicParties of(String direction, Negotiation negotiation) {
        return "OFFER".equals(direction)
            ? new EconomicParties(direction, negotiation.initiatorId, negotiation.ownerId)
            : new EconomicParties(direction, negotiation.ownerId, negotiation.initiatorId);
    }
}
