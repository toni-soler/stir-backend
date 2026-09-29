package org.stir.audit.verifier;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Full-scan reconciliation, independent of the incremental cursor: for each covered table,
 * compares the live rows against mutation_event's own claims. This is what
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md calls detecting "una fila sin evento" and "un evento sin
 * fila cuando no hay DELETE legítimo" - not a per-event check, a periodic sweep. Does not fix a
 * single false-positive race against a commit landing mid-scan by taking a serializable snapshot;
 * Phase 1 documents that as a known limitation rather than solving it with a coordinated MVCC
 * snapshot across two separate connections. */
final class Reconciler {
    record Finding(String reasonCode, long tableOid, String tableName, String entityKeyCanonical, String detail) {}

    private final AuditSql sql;
    Reconciler(AuditSql sql) { this.sql = sql; }

    List<Finding> reconcileAll() throws SQLException {
        var findings = new ArrayList<Finding>();
        for (AuditSql.TableCoverage table : sql.fetchCoveredTables()) {
            findings.addAll(reconcileTable(table));
        }
        return findings;
    }

    private List<Finding> reconcileTable(AuditSql.TableCoverage table) throws SQLException {
        var findings = new ArrayList<Finding>();
        Set<String> live = new HashSet<>(sql.liveEntityKeys(table.tableName(), table.pkColumns()));
        Set<String> expectedLive = new HashSet<>(sql.entityKeysExpectedLive(table.tableOid()));

        // A row that exists live but mutation_event's own history says its latest state was a
        // DELETE, or that has no event at all - this can only happen if the trigger somehow did
        // not fire (pre-migration legacy row, or COPY/backdoor path this Phase 1 has not covered).
        for (String key : live) {
            if (!expectedLive.contains(key)) {
                boolean hasAnyEvent = sql.queryLong(
                    "select count(*) from stir_audit.mutation_event where table_oid = ? and entity_key_canonical = ?",
                    table.tableOid(), key) > 0;
                findings.add(new Finding(hasAnyEvent ? "LIVE_ROW_LATEST_EVENT_IS_DELETE" : "LIVE_ROW_WITH_NO_AUDIT_EVENT",
                    table.tableOid(), table.tableName(), key,
                    hasAnyEvent ? "Row exists live but the latest recorded event for this key is a DELETE."
                                : "Row exists live with zero mutation_event rows for this key - the trigger did not fire for this row's creation."));
            }
        }
        // The inverse: mutation_event's history says this key should still exist (latest op is not
        // DELETE) but the row is now gone from the live table - a silent disappearance.
        for (String key : expectedLive) {
            if (!live.contains(key)) {
                findings.add(new Finding("EXPECTED_ROW_MISSING", table.tableOid(), table.tableName(), key,
                    "mutation_event's latest recorded operation for this key is not DELETE, but no live row with this key exists."));
            }
        }
        return findings;
    }
}
