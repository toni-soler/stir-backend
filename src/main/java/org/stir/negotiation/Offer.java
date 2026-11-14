package org.stir.negotiation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(schema="stir", name="offer")
public class Offer {
    @Column(name="share_reference_observation", nullable=false) public boolean shareReferenceObservation;
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="negotiation_id", nullable=false) public UUID negotiationId;
    @Column(name="listing_id", nullable=false) public UUID listingId;
    @Column(name="sequence_number", nullable=false) public int sequenceNumber;
    @Column(name="author_id", nullable=false) public UUID authorId;
    @Column(name="previous_offer_id") public UUID previousOfferId;
    @Column(nullable=false, length=2000) public String message;
    @Column(precision=18, scale=4) public BigDecimal quantity;
    @Column(name="unit_label", length=40) public String unitLabel;
    @Column(name="proposed_amount", precision=18, scale=2) public BigDecimal proposedAmount;
    @Column(name="proposed_unit_ref", length=60) public String proposedUnitRef;
    @Column(length=2000) public String terms;
    @Column(name="external_contract_namespace", length=80) public String externalContractNamespace;
    @Column(name="external_contract_digest", length=64) public String externalContractDigest;
    // Structured terms (STRUCTURED_TERMS_AND_NEEDS.md): owner/initiator are the negotiation's roles.
    @Column(length=2000) public String scope;
    @Column(name="owner_tasks", length=1000) public String ownerTasks;
    @Column(name="initiator_tasks", length=1000) public String initiatorTasks;
    @Column(name="fulfilment_mode", length=20) public String fulfilmentMode;
    @Column(name="fulfil_by") public java.time.LocalDate fulfilBy;
    @Column(name="open_questions", length=1000) public String openQuestions;
    @Column(name="valid_until") public Instant validUntil;
    @Column(nullable=false, length=10) public String status;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
