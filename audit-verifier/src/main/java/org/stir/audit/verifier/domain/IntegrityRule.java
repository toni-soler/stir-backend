package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** INTEGRITY domain: market_integrity_case, market_integrity_case_event.
 *
 * P1-R2-001 remediation: the P1-RA-004 rewrite (first remediation round) validated the transition
 * chain correctly, but conflated two different things that Codex's second reaudit found mixed
 * together: "the audit event currently being verified" and "the latest/final status of the whole
 * case". The old code read `thisStatus` as `history.get(history.size() - 1).status()` - the LAST
 * status of the ENTIRE case history, not the status of the specific row this rule invocation is
 * about - then used that `thisStatus` to decide whether to run the DECISOR_EQUALS_ORIGINATOR check,
 * while still reading `decisorId` from the CURRENTLY VERIFIED event's own actor_id. For a
 * legitimate SIGNAL(originator)->UNDER_REVIEW(decisor)->FINAL(decisor) case, verifying the SIGNAL
 * event (audit_event_id for that INSERT) would read `thisStatus` = "FINAL" (the case's eventual
 * last status, from a row that did not exist yet if the backlog is processed in order, or simply
 * belongs to a different row) but `decisorId` = SIGNAL's own actor_id = the originator - producing
 * a FALSE VIOLATION/DECISOR_EQUALS_ORIGINATOR on the SIGNAL event itself, every time the full
 * backlog is processed (not just the last event evaluated in isolation, which is all the P1-RA-004
 * regression test actually exercised).
 *
 * The fix: for each audit event, evaluate the HISTORICAL PREFIX that ends EXACTLY at that event's
 * own `sequence` (the case_event table's own sequence column, not mutation_event's unrelated audit
 * sequence) - never the full case history, which may contain rows that come strictly after the
 * event being verified. `thisStatus`/`thisActorId` are read directly from the event's own row, not
 * derived from "the last element of some list". The DECISOR_EQUALS_ORIGINATOR check only ever
 * applies when THIS event's own status is FINAL/DISMISSED, using THIS event's own actor - matching
 * MarketIntegrityService.decide()'s real contract, which checks the actor of the decision being
 * made, not some later actor unrelated to the row being evaluated. */
public final class IntegrityRule implements DomainRule {
    private record SeqStatus(long sequence, String status) {}

    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if (!"market_integrity_case_event".equals(event.tableName()) || !"INSERT".equals(event.operation())) {
            return Reasoned.structureOnly("Chain integrity is the only claim made for " + event.tableName() + " " + event.operation() + " events in Phase 1.");
        }
        Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());
        String eventRowId = pk.get("id");

        // This event's OWN row - case, status, sequence, actor - read once, directly. Never derived
        // from a list of other rows.
        String thisRow = sql.queryStringOrNull(
            "select case_id::text || '\u0001' || status || '\u0001' || sequence || '\u0001' || coalesce(actor_id::text, '') " +
            "from stir.market_integrity_case_event where id = ?::uuid", eventRowId);
        if (thisRow == null) {
            return Reasoned.indeterminate("EVENT_ROW_NOT_FOUND", "market_integrity_case_event " + eventRowId + " could not be re-read (already superseded by a later reconciliation pass?).");
        }
        String[] parts = thisRow.split("\u0001", -1);
        String caseId = parts[0];
        String thisStatus = parts[1];
        long thisSequence = Long.parseLong(parts[2]);
        String thisActorId = parts[3].isEmpty() ? null : parts[3];

        // The prefix ending EXACTLY at this event's own sequence - not the full case history, which
        // may (in a real backlog, or after a later reconciliation pass) already contain rows that
        // come strictly after this one. This is the crux of the P1-R2-001 fix: what this specific
        // audit event structurally represents is "the case as of this event", never "the case as of
        // whatever the newest row happens to be right now".
        var rows = sql.queryStringList(
            "select status || ':' || sequence from stir.market_integrity_case_event where case_id = ?::uuid and sequence <= ? order by sequence",
            caseId, thisSequence);
        var history = new ArrayList<SeqStatus>();
        for (String row : rows) {
            int colon = row.lastIndexOf(':');
            history.add(new SeqStatus(Long.parseLong(row.substring(colon + 1)), row.substring(0, colon)));
        }
        if (history.isEmpty() || history.get(history.size() - 1).sequence() != thisSequence) {
            return Reasoned.indeterminate("CASE_HISTORY_EMPTY", "case " + caseId + " has no market_integrity_case_event row at sequence " + thisSequence + " including the one just inserted (id=" + eventRowId + ").");
        }

        // Contiguous 1..thisSequence, no gap, no duplicate.
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).sequence() != i + 1) {
                return Reasoned.violation("SEQUENCE_GAP_OR_DUPLICATE",
                    "case " + caseId + "'s history up to sequence " + thisSequence + " is not contiguous 1.." + history.size() + ": " + history);
            }
        }

        // Terminal state: nothing may precede this event after a FINAL/DISMISSED event (excluding
        // this event's own position, the last in the prefix by construction).
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

        // Every consecutive transition up to and including this event must be one of the two
        // MarketIntegrityService.decide() allows.
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

        // P1-R2-001: gated on THIS event's OWN status, using THIS event's OWN actor - never the
        // case's eventual last status, never a different row's actor.
        if ("FINAL".equals(thisStatus) || "DISMISSED".equals(thisStatus)) {
            String originatorId = sql.queryStringOrNull("select created_by::text from stir.market_integrity_case where id = ?::uuid", caseId);
            if (thisActorId != null && thisActorId.equals(originatorId)) {
                return Reasoned.violation("DECISOR_EQUALS_ORIGINATOR",
                    "case " + caseId + " " + thisStatus + " (sequence " + thisSequence + ") actor_id equals the case's own created_by - MarketIntegrityService requires the decisor to differ from the originator.");
            }
        }

        return Reasoned.structureOnly("case " + caseId + "'s history up to and including this event (sequence " + thisSequence +
            ", status " + thisStatus + ") is a structurally valid SIGNAL[->UNDER_REVIEW[->FINAL|DISMISSED]] prefix with contiguous numbering - actor identity itself is not cryptographically proven.");
    }
}
