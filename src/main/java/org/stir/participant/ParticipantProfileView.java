package org.stir.participant;

import java.time.Instant;
import java.util.UUID;

/** Public/self presence view. Never exposes the profile row's own surrogate id. */
public record ParticipantProfileView(UUID userId, String displayName, String bio, String location, UUID avatarAttachmentId, boolean active, long version, Instant updatedAt) {
    static ParticipantProfileView of(ParticipantProfile profile) {
        return new ParticipantProfileView(profile.userId, profile.displayName, profile.bio, profile.location, profile.avatarAttachmentId, profile.active, profile.version, profile.updatedAt);
    }
}
