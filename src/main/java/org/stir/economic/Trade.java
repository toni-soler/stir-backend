package org.stir.economic;

import jakarta.persistence.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * STIR's link to an osTRIS EXCHANGE - application data only (who/what/how much/state/receipt),
 * never a parallel ledger. osTRIS remains the sole source of truth for whether value actually
 * moved; this row is never used to compute or display a balance itself.
 */
@Entity @Table(schema="stir", name="trade")
public class Trade {
    @Id public UUID id;
    @Column(name="tenant_id", nullable=false) public UUID tenantId;
    @Column(name="agreement_id", nullable=false) public UUID agreementId;
    @Column(name="community_id", nullable=false) public UUID communityId;
    @Column(name="unit_id", nullable=false) public UUID unitId;
    @Column(name="transaction_id", nullable=false) public UUID transactionId;
    @Column(name="payer_account_id", nullable=false) public UUID payerAccountId;
    @Column(name="payee_account_id", nullable=false) public UUID payeeAccountId;
    @Column(name="payer_user_id", nullable=false) public UUID payerUserId;
    @Column(name="payee_user_id", nullable=false) public UUID payeeUserId;
    @Column(nullable=false, precision=78, scale=0) public BigInteger amount;
    @Column(name="contractual_metadata_digest", nullable=false, length=64) public String contractualMetadataDigest;
    @Column(name="execution_state", nullable=false, length=24) public String executionState;
    @Column(name="committed_sequence") public Long committedSequence;
    @Column(name="protocol_digest", length=64) public String protocolDigest;
    @Column(name="committed_at") public Instant committedAt;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
}
