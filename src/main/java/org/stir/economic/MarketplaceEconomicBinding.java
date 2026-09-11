package org.stir.economic;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** tenant -> osTRIS community/unit. One row per tenant; explicit, never inferred from UUID equality. */
@Entity @Table(schema="stir", name="marketplace_economic_binding")
public class MarketplaceEconomicBinding {
    @Id @Column(name="tenant_id") public UUID tenantId;
    @Column(name="community_id", nullable=false) public UUID communityId;
    @Column(name="unit_id", nullable=false) public UUID unitId;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
