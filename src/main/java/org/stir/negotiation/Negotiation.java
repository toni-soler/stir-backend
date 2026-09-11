package org.stir.negotiation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(schema="stir", name="negotiation")
public class Negotiation {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="listing_id", nullable=false) public UUID listingId;
    @Column(name="initiator_id", nullable=false) public UUID initiatorId;
    @Column(name="owner_id", nullable=false) public UUID ownerId;
    @Column(nullable=false, length=10) public String status;
    @Column(name="last_offer_id") public UUID lastOfferId;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;

    public boolean isParty(UUID userId) { return initiatorId.equals(userId) || ownerId.equals(userId); }
    public UUID otherParty(UUID userId) { return initiatorId.equals(userId) ? ownerId : initiatorId; }
}
