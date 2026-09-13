package org.stir.listing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(schema="stir", name="listing")
public class Listing {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="owner_id", nullable=false) public UUID ownerId;
    @Column(nullable=false, length=10) public String direction;
    @Column(nullable=false, length=160) public String title;
    @Column(nullable=false, length=8000) public String description;
    @Column(nullable=false, length=40) public String category;
    @Column(name="resource_kind", nullable=false, length=40) public String resourceKind;
    @Column(length=160) public String location;
    @Column(nullable=false, length=10) public String status;
    @Column(name="hidden_by_moderator", nullable=false) public boolean hiddenByModerator;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
