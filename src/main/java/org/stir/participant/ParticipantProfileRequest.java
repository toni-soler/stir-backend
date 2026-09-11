package org.stir.participant;

import jakarta.validation.constraints.*;

public record ParticipantProfileRequest(
    @NotBlank @Size(max=80) String displayName,
    @Size(max=500) String bio,
    @Size(max=160) String location
) {}
