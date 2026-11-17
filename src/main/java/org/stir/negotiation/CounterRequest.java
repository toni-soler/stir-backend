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
    boolean shareReferenceObservation,
    @Pattern(regexp="[a-z][a-z0-9._-]{2,79}") String externalContractNamespace,
    @Pattern(regexp="[0-9a-f]{64}") String externalContractDigest,
    @jakarta.validation.Valid OfferTerms structuredTerms
) {
    public CounterRequest(String message,BigDecimal quantity,String unitLabel,BigDecimal proposedAmount,String proposedUnitRef,String terms,
            Long expectedVersion,boolean shareReferenceObservation,String externalContractNamespace,String externalContractDigest) {
        this(message,quantity,unitLabel,proposedAmount,proposedUnitRef,terms,expectedVersion,shareReferenceObservation,externalContractNamespace,externalContractDigest,null);
    }
    OfferRequest offer() { return new OfferRequest(message,quantity,unitLabel,proposedAmount,proposedUnitRef,terms,shareReferenceObservation,externalContractNamespace,externalContractDigest,structuredTerms); }
}
