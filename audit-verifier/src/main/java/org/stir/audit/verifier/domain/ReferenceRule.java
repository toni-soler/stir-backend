package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** REFERENCE domain: community_reference, reference_policy, reference_snapshot,
 * reference_proposal, reference_context_snapshot, reference_definition.
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md rule 3: "publisher directo... actor declarado" - no
 * independent cryptographic proof of publisher authority exists in this schema today. Chain
 * integrity (proven by ChainVerifier, not this rule) is the substantive claim for this domain in
 * Phase 1. */
public final class ReferenceRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        return Reasoned.structureOnly("REFERENCE domain: " + event.tableName() + " " + event.operation() +
            " - append-only/versioned by its own existing trigger and this MVP's chain; publisher authority is a declared actor claim, not cryptographically proven.");
    }
}
