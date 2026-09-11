package org.stir.economic;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** tenant/user -> osTRIS participant/account/controller/credential. Never inferred from UUID equality. */
@Entity @Table(schema="stir", name="participant_economic_binding")
public class ParticipantEconomicBinding {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="user_id", nullable=false) public UUID userId;
    @Column(name="community_id", nullable=false) public UUID communityId;
    @Column(name="unit_id", nullable=false) public UUID unitId;
    @Column(name="participant_id", nullable=false) public UUID participantId;
    @Column(name="account_id", nullable=false) public UUID accountId;
    @Column(name="controller_id", nullable=false) public UUID controllerId;
    @Column(name="credential_id", nullable=false) public UUID credentialId;
    @Column(name="public_key_base64url", nullable=false, length=64) public String publicKeyBase64url;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
