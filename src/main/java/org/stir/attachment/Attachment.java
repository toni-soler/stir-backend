package org.stir.attachment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One stored file (a Listing photo or a participant avatar). Bytes live in object storage;
 * this row is metadata + authorization context only. */
@Entity @Table(schema="stir", name="attachment")
public class Attachment {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="owner_user_id", nullable=false) public UUID ownerUserId;
    @Column(nullable=false, length=32) public String purpose;
    @Column(name="listing_id") public UUID listingId;
    public Short position;
    @Column(name="object_key", nullable=false, length=300) public String objectKey;
    @Column(name="media_type", nullable=false, length=100) public String mediaType;
    @Column(name="size_bytes", nullable=false) public long sizeBytes;
    @Column(nullable=false, length=16) public String status;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
