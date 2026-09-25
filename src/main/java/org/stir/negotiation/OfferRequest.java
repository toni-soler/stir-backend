package org.stir.negotiation;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record OfferRequest(
    @NotBlank @Size(max=2000) String message,
    @Positive BigDecimal quantity,
    @Size(max=40) String unitLabel,
    @PositiveOrZero BigDecimal proposedAmount,
    @Size(max=60) String proposedUnitRef,
    @Size(max=2000) String terms,
    boolean shareReferenceObservation
 ) {
    public OfferRequest(String message,BigDecimal quantity,String unitLabel,BigDecimal proposedAmount,String proposedUnitRef,String terms) {
        this(message,quantity,unitLabel,proposedAmount,proposedUnitRef,terms,false);
    }
}
