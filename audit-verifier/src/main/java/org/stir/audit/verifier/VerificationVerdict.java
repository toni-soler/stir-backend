package org.stir.audit.verifier;

/** Every domain rule returns exactly one of these. There is deliberately no "AUTHORIZED" or
 * "VALID" value anywhere in this enum or this codebase: CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md's
 * explicit stop condition is that PASS_STRUCTURE_ONLY must never become PASS_AUTHORIZED. A rule
 * that only checked sequence/quorum/threshold math against actor IDs the DB cannot independently
 * verify must return PASS_STRUCTURE_ONLY, never claim more than it proved. */
public enum VerificationVerdict {
    /** Every signature/envelope involved was independently re-verified against persisted
     * cryptographic material (e.g. a Seven Keys proposal whose stored constitutional_signature
     * rows still verify against the seat's historical public key). */
    PASS_CRYPTO,
    /** The event sequence, thresholds and state machine are internally consistent and match the
     * documented contract, but actor identity/intent has no independent proof in this database -
     * a self-consistent SQL-fabricated history passes this and only this. */
    PASS_STRUCTURE_ONLY,
    /** The rule could evaluate but genuinely cannot determine PASS or VIOLATION with the evidence
     * available (e.g. a referenced envelope was never persisted historically). */
    INDETERMINATE,
    /** Baseline/legacy data imported before this MVP existed, or genesis/bootstrap rows this rule
     * is not yet equipped to verify - explicitly not a claim of validity or of tampering. */
    NEEDS_BASELINE,
    /** A reproducible, evidenced contract violation - the only verdict that ever creates a
     * security_incident row. */
    VIOLATION
}
