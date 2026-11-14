package org.stir.negotiation;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A proposal is more than an amount (STRUCTURED_TERMS_AND_NEEDS.md): what it covers, what the
 * listing owner and the person who started the negotiation each do, how and by when it is
 * fulfilled, what is still open, and until when it can be accepted. All optional; frozen into the
 * Agreement snapshot from schemaVersion 4.
 */
public record OfferTerms(
    @Size(max=2000) String scope,
    @Size(max=1000) String ownerTasks,
    @Size(max=1000) String initiatorTasks,
    @Pattern(regexp=org.stir.listing.ListingDetails.FULFILMENT_MODES) String fulfilmentMode,
    LocalDate fulfilBy,
    @Size(max=1000) String openQuestions,
    Instant validUntil
) {
    public static final OfferTerms NONE=new OfferTerms(null,null,null,null,null,null,null);
}
