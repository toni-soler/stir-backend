package org.stir.participant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(schema="stir", name="participant_profile")
public class ParticipantProfile {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="user_id", nullable=false) public UUID userId;
    @Column(name="display_name", nullable=false, length=80) public String displayName;
    @Column(length=500) public String bio;
    @Column(length=160) public String location;
    @Column(name="avatar_attachment_id") public UUID avatarAttachmentId;
    @Column(nullable=false) public boolean active;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
