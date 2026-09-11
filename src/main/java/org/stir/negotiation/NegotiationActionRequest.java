package org.stir.negotiation;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record NegotiationActionRequest(
    UUID offerId,
    @NotNull @PositiveOrZero Long expectedVersion
) {}
