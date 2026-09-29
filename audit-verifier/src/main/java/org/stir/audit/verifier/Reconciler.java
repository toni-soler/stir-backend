package org.stir.audit.verifier;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Full-scan reconciliation, independent of the incremental cursor: for each covered table,
 * compares the live rows against mutation_event's own claims. This is what
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md calls detecting "una fila sin evento" and "un evento sin
 * fila cuando no hay DELETE legítimo" - not a per-event check, a periodic sweep.
 *
 * P1-RA-003/P1-R2-002: a live row with no matching mutation_event is only a real finding if it is
 * ALSO absent from stir_audit.baseline_import. Stated precisely, per Codex's second reaudit
 * correction of the first round's overclaim: a baseline_import row means ONLY that this (table,
 * key) was present at the moment the activation ceremony captured it - LEGACY_UNVERIFIED, never
 * VALID/AUTHORIZED/AUDITED. It is not evidence of legitimate origin; its own creation was never
 * itself observed by this audit trail, and a database owner who disabled the trigger before that
 * ceremony ran (entirely outside Phase 1's runtime trust boundary - see V18's own activation-
 * ceremony comment) would be indistinguishable from genuine pre-activation history. What this
 * DOES reliably distinguish, within the ordinary idax_app/idax_admin/idax_backend runtime threat
 * model Phase 1 actually defends against, is "captured by the activation ceremony" from "a new row
 * that skipped the trigger afterward" - a row NOT in baseline_import that also has no event is
 * exactly that second, reconciliation-worthy case.
 *
 * Concurrency: each table's three reads (live rows, mutation_event's "should still be live"
 * claims, baseline-imported keys) run inside ONE REPEATABLE READ transaction via
 * AuditSql.readConsistentTableSnapshot - see that method's own comment for why this closes the
 * TOCTOU gap Codex's reaudit flagged as real but unreproduced, without any application-level lock. */
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
        AuditSql.TableSnapshot snapshot = sql.readConsistentTableSnapshot(table);

        for (String key : snapshot.live()) {
            if (snapshot.expectedLive().contains(key)) continue;
            if (snapshot.baselineImported().contains(key)) continue; // P1-RA-003/P1-R2-002: LEGACY_UNVERIFIED, not a finding - see class javadoc
            boolean hasAnyEvent = sql.queryLong(
                "select count(*) from stir_audit.mutation_event where table_oid = ? and entity_key_canonical = ?",
                table.tableOid(), key) > 0;
            findings.add(new Finding(hasAnyEvent ? "LIVE_ROW_LATEST_EVENT_IS_DELETE" : "LIVE_ROW_WITH_NO_AUDIT_EVENT",
                table.tableOid(), table.tableName(), key,
                hasAnyEvent ? "Row exists live but the latest recorded event for this key is a DELETE."
                            : "Row exists live with zero mutation_event rows for this key and is not in baseline_import - the trigger did not fire for this row's creation."));
        }
        // The inverse: mutation_event's history says this key should still exist (latest op is not
        // DELETE) but the row is now gone from the live table - a silent disappearance. Baseline
        // import is irrelevant here: any key with a real mutation_event claiming it should be live
        // has, by definition, a post-audit-activation event, regardless of whether it also
        // happened to predate the baseline snapshot.
        for (String key : snapshot.expectedLive()) {
            if (!snapshot.live().contains(key)) {
                findings.add(new Finding("EXPECTED_ROW_MISSING", table.tableOid(), table.tableName(), key,
                    "mutation_event's latest recorded operation for this key is not DELETE, but no live row with this key exists."));
            }
        }
        return findings;
    }
}
