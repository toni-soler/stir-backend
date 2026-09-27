package org.stir.listing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable point-in-time snapshot of a Listing, created on every create/update. A LISTING/WANTED
 * reference_observation points here, never at Listing.id directly - editing a listing must never
 * rewrite what an earlier observation saw (MULTI_SOURCE_VALUE_EVIDENCE.md). Never updated after
 * insert. */
@Entity @Table(schema="stir", name="listing_revision")
public class ListingRevision {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="listing_id", nullable=false) public UUID listingId;
    @Column(name="revision_number", nullable=false) public int revisionNumber;
    @Column(name="owner_id", nullable=false) public UUID ownerId;
    @Column(nullable=false, length=10) public String direction;
    @Column(nullable=false, length=160) public String title;
    @Column(name="indicative_amount", precision=18, scale=2) public BigDecimal indicativeAmount;
    @Column(name="indicative_quantity", precision=18, scale=4) public BigDecimal indicativeQuantity;
    @Column(name="indicative_unit_label", length=40) public String indicativeUnitLabel;
    @Column(name="indicative_unit_ref", length=60) public String indicativeUnitRef;
    @Column(name="reference_definition_id") public UUID referenceDefinitionId;
    @Column(name="share_reference_observation", nullable=false) public boolean shareReferenceObservation;
    @Column(name="canonical_json", nullable=false, columnDefinition="text") public String canonicalJson;
    @Column(name="digest_sha256", nullable=false, length=64) public String digestSha256;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
