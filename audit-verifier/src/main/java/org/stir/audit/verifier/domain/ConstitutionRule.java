package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** CONSTITUTION domain: market_constitution, constitutional_proposal, constitutional_signature,
 * market_governance_event, constitutional_authority, constitutional_seat,
 * constitutional_credential_history, constitutional_webauthn_credential,
 * constitutional_webauthn_challenge.
 *
 * PHASE 1 SCOPE DECISION (explicit, not a silent gap): this rule never returns PASS_CRYPTO. Doing
 * so honestly would require the verifier to independently re-verify Ed25519/WebAuthn signatures
 * against RFC 8785 JCS-canonicalized payloads, reimplemented from scratch in this separate module
 * (it deliberately does not depend on stir-backend's SevenKeysCrypto/WebAuthnCrypto, since sharing
 * that code would make the backend's own crypto library implicitly authoritative over the
 * verifier's results). That reimplementation was not attempted within Phase 1's scope/time - see
 * VALIDATION_GOVERNED_STATE_AUDIT_MVP.md's SPEC GAP entry. This rule proves only that a signature
 * count/threshold and a proposal-to-constitution digest linkage structurally exist, never that the
 * signatures are cryptographically valid or that the signer identities are genuine. */
public final class ConstitutionRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if (!"market_constitution".equals(event.tableName()) || !"INSERT".equals(event.operation())) {
            // Every other CONSTITUTION-domain table/operation (proposal creation, signature
            // collection, seat/credential history, WebAuthn material) is proven append-only and
            // tamper-evident by the chain check alone; this rule adds no further claim for them.
            return Reasoned.structureOnly("Chain integrity is the only claim made for " + event.tableName() + " " + event.operation() + " events in Phase 1.");
        }

        Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());
        String rowId = pk.get("id");
        long version = sql.queryLong("select version from stir.market_constitution where id = ?::uuid", rowId);
        String digest = sql.queryStringOrNull("select digest_sha256 from stir.market_constitution where id = ?::uuid", rowId);
        String authorityId = sql.queryStringOrNull("select authority_id::text from stir.market_constitution where id = ?::uuid", rowId);

        if (version == 1) {
            // SevenKeysService.bootstrap() verifies seven seat + Guardian possession envelopes
            // before writing, but does not persist them - GOVERNED_STATE_AUDIT_ARCHITECTURE.md's
            // own finding, not something this rule can work around retroactively.
            return Reasoned.needsBaseline("market_constitution version=1 is the bootstrap row; possession envelopes for the seven seats and Guardian are verified at bootstrap time but not persisted, so no historical material exists to re-verify against.");
        }

        String proposalId = sql.queryStringOrNull("""
            select id::text from stir.constitutional_proposal
            where authority_id = ?::uuid and action_type = 'AMEND_CONSTITUTION' and after_digest = ?
            """, authorityId, digest);
        if (proposalId == null) {
            return Reasoned.violation("CONSTITUTION_WITHOUT_MATCHING_PROPOSAL",
                "market_constitution id=" + rowId + " version=" + version + " digest=" + digest +
                " has no constitutional_proposal(action_type=AMEND_CONSTITUTION, after_digest=<this digest>) for authority " + authorityId +
                " - this is exactly the AUD-012 unsigned-constitution attack shape: a constitution row with no corresponding authorized proposal.");
        }
        long signatureCount = sql.queryLong("select count(*) from stir.constitutional_signature where proposal_id = ?::uuid", proposalId);
        if (signatureCount < 7) {
            return Reasoned.violation("CONSTITUTION_INSUFFICIENT_SIGNATURES",
                "constitutional_proposal " + proposalId + " backing market_constitution " + rowId + " has only " + signatureCount +
                " constitutional_signature rows; AMEND_CONSTITUTION requires 7-of-7 (SEVEN_KEYS_GOVERNANCE.md).");
        }
        return Reasoned.structureOnly("market_constitution " + rowId + " version=" + version +
            " matches constitutional_proposal " + proposalId + " with " + signatureCount +
            " recorded signatures - structurally consistent with 7-of-7, signatures not independently cryptographically re-verified in Phase 1.");
    }
}
