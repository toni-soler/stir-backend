package org.stir.negotiation;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** expectedPayerUserId/expectedPayeeUserId (optional, additive): the economic parties the caller
 * reviewed. When supplied, accept refuses unless they are exactly the parties it would freeze.
 * acknowledgeOpenQuestions: required when the accepted offer leaves questions open - accepting
 * then means accepting these terms with those questions still open (STRUCTURED_TERMS_AND_NEEDS.md). */
public record NegotiationActionRequest(
    UUID offerId,
    @NotNull @PositiveOrZero Long expectedVersion,
    boolean shareReferenceObservation,
    UUID expectedPayerUserId,
    UUID expectedPayeeUserId,
    boolean acknowledgeOpenQuestions
) {
    public NegotiationActionRequest(UUID offerId, Long expectedVersion, boolean shareReferenceObservation) {
        this(offerId, expectedVersion, shareReferenceObservation, null, null, false);
    }
    public NegotiationActionRequest(UUID offerId, Long expectedVersion, boolean shareReferenceObservation, UUID expectedPayerUserId, UUID expectedPayeeUserId) {
        this(offerId, expectedVersion, shareReferenceObservation, expectedPayerUserId, expectedPayeeUserId, false);
    }
}
