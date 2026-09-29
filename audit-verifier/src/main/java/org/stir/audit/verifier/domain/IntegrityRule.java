package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** INTEGRITY domain: market_integrity_case, market_integrity_case_event.
 *
 * P1-RA-004 remediation: the real contract, read directly from MarketIntegrityService.decide():
 *   ("SIGNAL".equals(current) && "UNDER_REVIEW".equals(status)) ||
 *   ("UNDER_REVIEW".equals(current) && Set.of("FINAL","DISMISSED").contains(status))
 * are the ONLY two legal transitions - a direct SIGNAL->FINAL/DISMISSED is rejected by the real
 * service with 409 CONFLICT. The original Phase 1 rule only checked that the history's first
 * event was SIGNAL, which let SIGNAL(seq=1)->FINAL(seq=2) - skipping UNDER_REVIEW entirely - pass
 * as PASS_STRUCTURE_ONLY. This rewrite validates the COMPLETE ordered transition chain, contiguous
 * sequence numbering with no gap/duplicate, and that nothing follows a terminal FINAL/DISMISSED
 * event - still never claiming more than PASS_STRUCTURE_ONLY, since actor/decisor identity remains
 * an unauthenticated DB claim. */
public final class IntegrityRule implements DomainRule {
    private record SeqStatus(long sequence, String status) {}

    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if (!"market_integrity_case_event".equals(event.tableName()) || !"INSERT".equals(event.operation())) {
            return Reasoned.structureOnly("Chain integrity is the only claim made for " + event.tableName() + " " + event.operation() + " events in Phase 1.");
        }
        Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());
        String eventRowId = pk.get("id");
        String caseId = sql.queryStringOrNull("select case_id::text from stir.market_integrity_case_event where id = ?::uuid", eventRowId);
        if (caseId == null) {
            return Reasoned.indeterminate("EVENT_ROW_NOT_FOUND", "market_integrity_case_event " + eventRowId + " could not be re-read (already superseded by a later reconciliation pass?).");
        }

        // Full ordered history for this case, INCLUDING this event, by the case_event table's own
        // `sequence` column (MarketIntegrityService.event() assigns coalesce(max(sequence),0)+1 -
        // the audit stream's own `sequence` on mutation_event is a different, unrelated number).
        var rows = sql.queryStringList(
            "select status || ':' || sequence from stir.market_integrity_case_event where case_id = ?::uuid order by sequence", caseId);
        var history = new ArrayList<SeqStatus>();
        for (String row : rows) {
            int colon = row.lastIndexOf(':');
            history.add(new SeqStatus(Long.parseLong(row.substring(colon + 1)), row.substring(0, colon)));
        }
        if (history.isEmpty()) {
            return Reasoned.indeterminate("CASE_HISTORY_EMPTY", "case " + caseId + " has no market_integrity_case_event rows at all, including the one just inserted.");
        }

        // Contiguous 1..N, no gap, no duplicate.
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).sequence() != i + 1) {
                return Reasoned.violation("SEQUENCE_GAP_OR_DUPLICATE",
                    "case " + caseId + " event history sequence numbers are not contiguous 1.." + history.size() + ": " + history);
            }
        }

        // Terminal state: nothing may follow a FINAL/DISMISSED event.
        for (int i = 0; i < history.size() - 1; i++) {
            String s = history.get(i).status();
            if ("FINAL".equals(s) || "DISMISSED".equals(s)) {
                return Reasoned.violation("EVENT_AFTER_TERMINAL_STATE",
                    "case " + caseId + " has an event at sequence " + history.get(i + 1).sequence() +
                    " after a terminal " + s + " at sequence " + history.get(i).sequence() + " - MarketIntegrityService never appends past a terminal decision.");
            }
        }

        // First event must be SIGNAL.
        if (!"SIGNAL".equals(history.get(0).status())) {
            return Reasoned.violation("FIRST_EVENT_NOT_SIGNAL",
                "case " + caseId + "'s first event (sequence 1) is '" + history.get(0).status() + "', not SIGNAL.");
        }

        // Every consecutive transition must be one of the two MarketIntegrityService.decide()
        // allows - this is what actually catches SIGNAL->FINAL/DISMISSED skipping UNDER_REVIEW.
        for (int i = 1; i < history.size(); i++) {
            String prev = history.get(i - 1).status(), cur = history.get(i).status();
            boolean validTransition = ("SIGNAL".equals(prev) && "UNDER_REVIEW".equals(cur))
                || ("UNDER_REVIEW".equals(prev) && ("FINAL".equals(cur) || "DISMISSED".equals(cur)));
            if (!validTransition) {
                return Reasoned.violation("INVALID_TRANSITION",
                    "case " + caseId + " transitions " + prev + " -> " + cur + " at sequence " + history.get(i).sequence() +
                    ", which MarketIntegrityService.decide() never allows (only SIGNAL->UNDER_REVIEW or UNDER_REVIEW->FINAL/DISMISSED). " +
                    "This is exactly the AUD-012 forged-FINAL attack shape when prev=SIGNAL and cur=FINAL directly.");
            }
        }

        String thisStatus = history.get(history.size() - 1).status();
        if ("FINAL".equals(thisStatus) || "DISMISSED".equals(thisStatus)) {
            String originatorId = sql.queryStringOrNull("select created_by::text from stir.market_integrity_case where id = ?::uuid", caseId);
            String decisorId = sql.queryStringOrNull("select actor_id::text from stir.market_integrity_case_event where id = ?::uuid", eventRowId);
            if (decisorId != null && decisorId.equals(originatorId)) {
                return Reasoned.violation("DECISOR_EQUALS_ORIGINATOR",
                    "case " + caseId + " " + thisStatus + " actor_id equals the case's own created_by - MarketIntegrityService requires the decisor to differ from the originator.");
            }
        }

        return Reasoned.structureOnly("case " + caseId + "'s full event history (" + history.size() +
            " events) is a structurally valid SIGNAL[->UNDER_REVIEW[->FINAL|DISMISSED]] sequence with contiguous numbering - actor identity itself is not cryptographically proven.");
    }
}
