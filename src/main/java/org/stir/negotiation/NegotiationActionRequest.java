package org.stir.negotiation;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** expectedPayerUserId/expectedPayeeUserId (optional, additive): the economic parties the caller
 * reviewed. When supplied, accept refuses unless they are exactly the parties it would freeze. */
public record NegotiationActionRequest(
    UUID offerId,
    @NotNull @PositiveOrZero Long expectedVersion,
    boolean shareReferenceObservation,
    UUID expectedPayerUserId,
    UUID expectedPayeeUserId
) {
    public NegotiationActionRequest(UUID offerId, Long expectedVersion, boolean shareReferenceObservation) {
        this(offerId, expectedVersion, shareReferenceObservation, null, null);
    }
}
