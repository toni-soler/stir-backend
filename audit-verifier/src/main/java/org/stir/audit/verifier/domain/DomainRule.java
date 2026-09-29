package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;
import org.stir.audit.verifier.VerificationVerdict;

/** One rule per GOVERNED_STATE_AUDIT_ARCHITECTURE.md domain. A rule only ever reads stir.* tables
 * through the caller-supplied AuditSql (stir_auditor's own connection) - it never sees idax_app's
 * credential and never mutates anything outside verification_result/security_incident, which the
 * caller (not the rule) is responsible for persisting. */
public interface DomainRule {
    record Reasoned(VerificationVerdict verdict, String reasonCode, String detail) {
        public static Reasoned structureOnly(String detail) { return new Reasoned(VerificationVerdict.PASS_STRUCTURE_ONLY, "STRUCTURE_OK", detail); }
        public static Reasoned indeterminate(String code, String detail) { return new Reasoned(VerificationVerdict.INDETERMINATE, code, detail); }
        public static Reasoned needsBaseline(String detail) { return new Reasoned(VerificationVerdict.NEEDS_BASELINE, "NEEDS_BASELINE", detail); }
        public static Reasoned violation(String code, String detail) { return new Reasoned(VerificationVerdict.VIOLATION, code, detail); }
    }

    Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException;
}
