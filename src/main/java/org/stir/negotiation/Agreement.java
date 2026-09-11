package org.stir.negotiation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(schema="stir", name="agreement")
public class Agreement {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="negotiation_id", nullable=false) public UUID negotiationId;
    @Column(name="listing_id", nullable=false) public UUID listingId;
    @Column(name="offer_id", nullable=false) public UUID offerId;
    @Column(name="initiator_id", nullable=false) public UUID initiatorId;
    @Column(name="owner_id", nullable=false) public UUID ownerId;
    @Column(name="economic_phase", nullable=false, length=40) public String economicPhase;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
