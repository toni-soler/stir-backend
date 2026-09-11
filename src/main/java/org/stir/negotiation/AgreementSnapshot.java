package org.stir.negotiation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable, versioned, canonical contractual commitment. Never updated after insert. */
@Entity @Table(schema="stir", name="agreement_snapshot")
public class AgreementSnapshot {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="agreement_id", nullable=false) public UUID agreementId;
    @Column(name="schema_version", nullable=false) public int schemaVersion;
    @Column(name="canonical_json", nullable=false, columnDefinition="text") public String canonicalJson;
    @Column(nullable=false, length=64) public String nonce;
    @Column(name="digest_sha256", nullable=false, length=64) public String digestSha256;
    @Column(name="created_at", nullable=false) public Instant createdAt;
}
