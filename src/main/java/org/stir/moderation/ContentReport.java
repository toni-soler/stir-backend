package org.stir.moderation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** STIR's own content moderation (Listings/profiles) - never osTRIS Findings, PENALTY, RESTITUTION
 * or any economic sanction. Hiding a Listing never touches its Agreement/Trade/journal history. */
@Entity @Table(schema="stir", name="content_report")
public class ContentReport {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="reporter_user_id", nullable=false) public UUID reporterUserId;
    @Column(name="target_type", nullable=false, length=20) public String targetType;
    @Column(name="target_id", nullable=false) public UUID targetId;
    @Column(nullable=false, length=500) public String reason;
    @Column(nullable=false, length=16) public String status;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="resolved_at") public Instant resolvedAt;
    @Column(name="resolved_by_user_id") public UUID resolvedByUserId;
}
