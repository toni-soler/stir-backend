package org.stir.negotiation;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CounterRequest(
    @NotBlank @Size(max=2000) String message,
    @Positive BigDecimal quantity,
    @Size(max=40) String unitLabel,
    @PositiveOrZero BigDecimal proposedAmount,
    @Size(max=60) String proposedUnitRef,
    @Size(max=2000) String terms,
    @NotNull @PositiveOrZero Long expectedVersion,
    boolean shareReferenceObservation
) {
    OfferRequest offer() { return new OfferRequest(message,quantity,unitLabel,proposedAmount,proposedUnitRef,terms,shareReferenceObservation); }
}
