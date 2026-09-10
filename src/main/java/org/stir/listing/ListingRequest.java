package org.stir.listing;

import jakarta.validation.constraints.*;

public record ListingRequest(
    @NotNull @Pattern(regexp="OFFER|WANTED") String direction,
    @NotBlank @Size(max=160) String title,
    @NotBlank @Size(max=8000) String description,
    @NotBlank @Size(max=40) String category,
    @NotBlank @Size(max=40) String resourceKind,
    @Size(max=160) String location,
    @PositiveOrZero Long version
) {}
