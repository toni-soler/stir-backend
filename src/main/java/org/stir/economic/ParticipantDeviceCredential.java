package org.stir.economic;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** STIR-side friendly label for one of the caller's osTRIS credentials (device lifecycle UX only -
 * osTRIS discovery remains the sole source of truth for whether a credential is active/revoked). */
@Entity @Table(schema="stir", name="participant_device_credential")
public class ParticipantDeviceCredential {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="user_id", nullable=false) public UUID userId;
    @Column(name="ostris_credential_id", nullable=false) public UUID ostrisCredentialId;
    @Column(nullable=false, length=80) public String label;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
