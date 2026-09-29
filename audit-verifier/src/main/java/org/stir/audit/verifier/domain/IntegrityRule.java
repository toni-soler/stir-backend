package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** INTEGRITY domain: market_integrity_case, market_integrity_case_event.
 *
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md rule 2: "Un FINAL aislado = VIOLATION. Una cadena completa
 * con actor_id inventados puede pasar estructura: marcar PASS_STRUCTURE_ONLY, no PASS_AUTHORIZED."
 * This rule checks the SIGNAL -> UNDER_REVIEW -> FINAL/DISMISSED sequence exists for the case; it
 * cannot prove the decisor is a real, distinct, authorized person - only that the event history
 * for this case_id has the right shape. This is the second AUD-012 attack this MVP must catch:
 * market_integrity_case_event(status='FINAL') inserted directly with no SIGNAL/UNDER_REVIEW at all. */
public final class IntegrityRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if (!"market_integrity_case_event".equals(event.tableName()) || !"INSERT".equals(event.operation())) {
            return Reasoned.structureOnly("Chain integrity is the only claim made for " + event.tableName() + " " + event.operation() + " events in Phase 1.");
        }
        Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());
        String eventRowId = pk.get("id");
        String caseId = sql.queryStringOrNull("select case_id::text from stir.market_integrity_case_event where id = ?::uuid", eventRowId);
        String status = sql.queryStringOrNull("select status from stir.market_integrity_case_event where id = ?::uuid", eventRowId);
        long thisSequence = sql.queryLong("select sequence from stir.market_integrity_case_event where id = ?::uuid", eventRowId);

        if ("SIGNAL".equals(status)) {
            if (thisSequence != 1) {
                return Reasoned.violation("SIGNAL_NOT_FIRST", "case " + caseId + " event " + eventRowId + " is SIGNAL but sequence=" + thisSequence + ", not 1 - SIGNAL must be the first event for a case.");
            }
            return Reasoned.structureOnly("SIGNAL is the first event for case " + caseId + "; nothing prior to violate.");
        }

        List<String> priorStatuses = sql.queryStringList(
            "select status from stir.market_integrity_case_event where case_id = ?::uuid and sequence < ? order by sequence",
            caseId, thisSequence);

        if ("UNDER_REVIEW".equals(status)) {
            if (priorStatuses.isEmpty() || !"SIGNAL".equals(priorStatuses.get(0))) {
                return Reasoned.violation("UNDER_REVIEW_WITHOUT_SIGNAL", "case " + caseId + " event " + eventRowId + " is UNDER_REVIEW but the prior event history " + priorStatuses + " does not start with SIGNAL.");
            }
            return Reasoned.structureOnly("UNDER_REVIEW for case " + caseId + " correctly follows a SIGNAL.");
        }

        if ("FINAL".equals(status) || "DISMISSED".equals(status)) {
            if (priorStatuses.isEmpty()) {
                return Reasoned.violation("FINAL_WITHOUT_PRIOR_HISTORY", "case " + caseId + " event " + eventRowId + " is " + status +
                    " with zero prior market_integrity_case_event rows - a decision with no SIGNAL/UNDER_REVIEW behind it. This is exactly the AUD-012 forged-FINAL attack shape.");
            }
            if (!"SIGNAL".equals(priorStatuses.get(0))) {
                return Reasoned.violation("FINAL_HISTORY_DOES_NOT_START_WITH_SIGNAL", "case " + caseId + " event " + eventRowId + " is " + status + " but prior history " + priorStatuses + " does not begin with SIGNAL.");
            }
            String originatorId = sql.queryStringOrNull("select created_by::text from stir.market_integrity_case where id = ?::uuid", caseId);
            String decisorId = sql.queryStringOrNull("select actor_id::text from stir.market_integrity_case_event where id = ?::uuid", eventRowId);
            if (decisorId != null && decisorId.equals(originatorId)) {
                return Reasoned.violation("DECISOR_EQUALS_ORIGINATOR", "case " + caseId + " " + status + " actor_id equals the case's own created_by - MarketIntegrityService requires the decisor to differ from the originator, though both fields are unauthenticated DB claims, not cryptographic proof.");
            }
            return Reasoned.structureOnly("case " + caseId + " " + status + " follows a SIGNAL" +
                (priorStatuses.contains("UNDER_REVIEW") ? "->UNDER_REVIEW" : "") + " history with a distinct declared decisor - actor identity itself is not cryptographically proven.");
        }

        return Reasoned.indeterminate("UNKNOWN_STATUS", "market_integrity_case_event status=" + status + " is not one of SIGNAL/UNDER_REVIEW/FINAL/DISMISSED known to this rule.");
    }
}
