package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** AGREEMENT_ECONOMIC domain: agreement, agreement_snapshot, offer, negotiation, trade,
 * marketplace_economic_binding, participant_economic_binding.
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md rule 5: "Para COMMITTED, comprobar recibo/journal osTRIS...
 * no declarar ejecución por flag STIR." This verifier has no osTRIS credential and does not query
 * ostris.* tables (CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md's preparation section: only stir-backend/
 * stir-main/stir-doc are in scope) - a trade reaching COMMITTED is therefore always INDETERMINATE
 * here, never PASS of any kind, until a Phase-2-or-later cross-check against osTRIS's own journal
 * is deliberately designed and authorized. */
public final class AgreementEconomicRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if ("trade".equals(event.tableName())) {
            Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());
            String tradeId = pk.get("id");
            String state = sql.queryStringOrNull("select execution_state from stir.trade where id = ?::uuid", tradeId);
            if ("COMMITTED".equalsIgnoreCase(state)) {
                return Reasoned.indeterminate("OSTRIS_JOURNAL_NOT_CROSS_CHECKED",
                    "trade " + tradeId + " shows execution_state=COMMITTED in STIR's own table; this verifier has no osTRIS credential and cannot independently confirm the receipt/journal exists in osTRIS itself. Never treat a STIR-side flag as economic execution proof.");
            }
        }
        return Reasoned.structureOnly("AGREEMENT_ECONOMIC domain: " + event.tableName() + " " + event.operation() +
            " - Agreement acceptance state is separate from economic execution; this rule does not claim economic settlement occurred.");
    }
}
