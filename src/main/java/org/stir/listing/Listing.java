package org.stir.listing;

import jakarta.persistence.*;
import java.math.BigDecimal;
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
    @Column(name="reference_definition_id") public UUID referenceDefinitionId;
    // The owner's own ask/want, never "what the market accepts" - opt-in, mirroring Offer's own
    // proposedAmount/proposedUnitRef/shareReferenceObservation (MULTI_SOURCE_VALUE_EVIDENCE.md).
    @Column(name="indicative_amount", precision=18, scale=2) public BigDecimal indicativeAmount;
    @Column(name="indicative_quantity", precision=18, scale=4) public BigDecimal indicativeQuantity;
    @Column(name="indicative_unit_label", length=40) public String indicativeUnitLabel;
    @Column(name="indicative_unit_ref", length=60) public String indicativeUnitRef;
    @Column(name="share_reference_observation", nullable=false) public boolean shareReferenceObservation;
    // What is offered or needed beyond title/description (STRUCTURED_TERMS_AND_NEEDS.md).
    @Column(name="item_condition", length=20) public String itemCondition;
    @Column(name="fulfilment_mode", length=20) public String fulfilmentMode;
    @Column(name="included_items", length=1000) public String includedItems;
    @Column(name="available_until") public java.time.LocalDate availableUntil;
    @Column(nullable=false, length=20) public String availability="AVAILABLE";
    @Column(name="needed_by") public java.time.LocalDate neededBy;
    @Column(name="flexible_on", length=120) public String flexibleOn;
    @Column(name="firm_on", length=120) public String firmOn;
    /** Only for the owner and the people negotiating on this listing; never in search or revisions. */
    @Column(name="counterpart_note", length=2000) public String counterpartNote;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
