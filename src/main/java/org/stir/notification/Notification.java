package org.stir.notification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** In-app activity signal. NEVER the source of truth - Negotiation/Agreement/Trade remain
 * authoritative; this row only points at one of them for the UI's "something needs your
 * attention" badge/list. */
@Entity @Table(schema="stir", name="notification")
public class Notification {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="recipient_user_id", nullable=false) public UUID recipientUserId;
    @Column(nullable=false, length=40) public String type;
    @Column(name="reference_type", nullable=false, length=20) public String referenceType;
    @Column(name="reference_id", nullable=false) public UUID referenceId;
    @Column(name="read_at") public Instant readAt;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
