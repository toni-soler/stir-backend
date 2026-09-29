package org.stir.audit.verifier;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Every method here runs as stir_auditor - never idax_app. Recomputation of hashes/digests calls
 * the SAME stir_audit.* SQL functions the trigger itself calls (GRANTed EXECUTE to stir_auditor
 * specifically for this purpose in V18/V19), so there is exactly one implementation of each
 * canonical byte format in this whole system, not two that could silently drift.
 *
 * P1-RA-001 remediation: there is no time/UUID-based "watermark" anywhere in this class. The
 * incremental query (fetchUnverifiedEvents) is a durable anti-join against verification_result -
 * "has this specific event been verified for this rule version yet" - which is correct regardless
 * of commit order, transaction duration, or how late a transaction commits relative to others.
 * stir_audit.verifier_cursor still exists (see VerifierLoop) purely as an observability/latency
 * hint, never as the completeness mechanism. */
public final class AuditSql implements AutoCloseable {
    private final Connection connection;

    AuditSql(Config config) throws SQLException {
        this.connection = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password());
        this.connection.setAutoCommit(true);
    }

    record TableCoverage(long tableOid, String tableName, String domain, List<String> pkColumns) {}

    List<TableCoverage> fetchCoveredTables() throws SQLException {
        var out = new ArrayList<TableCoverage>();
        try (var ps = connection.prepareStatement(
                "select table_oid, table_name, domain, pk_columns from stir_audit.coverage_registry where coverage_status = 'COVERED' order by table_name");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                Array pkArray = rs.getArray("pk_columns");
                String[] pkColumns = (String[]) pkArray.getArray();
                out.add(new TableCoverage(rs.getLong("table_oid"), rs.getString("table_name"), rs.getString("domain"), List.of(pkColumns)));
            }
        }
        return out;
    }

    /** Unqualified: safe wherever the FROM clause is exactly stir_audit.mutation_event (optionally
     * aliased) and no other table contributes columns to the same SELECT list - true everywhere
     * this constant is used, including fetchUnverifiedEvents, whose correlated NOT EXISTS subquery
     * has its own separate column scope and never conflicts with these names. */
    private static final String EVENT_COLUMNS = """
        audit_event_id, tenant_id, domain, sequence, db_time,
        to_char(db_time AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as db_time_canonical,
        table_oid, table_name, operation, entity_key_canonical, community_id, old_row_digest,
        new_row_digest, row_digest_profile, session_user_name, session_role_reported,
        application_name, txid::text as txid_text, client_addr::text as client_addr_text,
        request_id, correlation_id, authorization_id, proposal_id, previous_hash, current_hash,
        event_format_version
        """;

    private MutationEvent mapEvent(ResultSet rs) throws SQLException {
        return new MutationEvent(
            (UUID) rs.getObject("audit_event_id"), (UUID) rs.getObject("tenant_id"), rs.getString("domain"),
            rs.getLong("sequence"), rs.getObject("db_time", OffsetDateTime.class), rs.getString("db_time_canonical"),
            rs.getLong("table_oid"), rs.getString("table_name"), rs.getString("operation"),
            rs.getString("entity_key_canonical"), (UUID) rs.getObject("community_id"), rs.getBytes("old_row_digest"),
            rs.getBytes("new_row_digest"), rs.getString("row_digest_profile"), rs.getString("session_user_name"),
            rs.getString("session_role_reported"), rs.getString("application_name"), rs.getString("txid_text"),
            rs.getString("client_addr_text"), rs.getString("request_id"), rs.getString("correlation_id"),
            rs.getString("authorization_id"), rs.getString("proposal_id"), rs.getBytes("previous_hash"),
            rs.getBytes("current_hash"), rs.getString("event_format_version"));
    }

    /** P1-RA-001's actual fix: every covered event with no verification_result row for this rule
     * version, full stop - no ordering assumption, so a transaction that commits late is picked up
     * on the very next cycle that observes it, regardless of what any other stream did meanwhile.
     * Ordered by (tenant_id, domain, sequence) so a single stream's events are processed in their
     * real causal order within one batch, which is what ChainVerifier's predecessor lookups need -
     * but this ordering is a courtesy for locality, never a completeness precondition. */
    List<MutationEvent> fetchUnverifiedEvents(String ruleVersion, int limit) throws SQLException {
        String sql = """
            select %s
            from stir_audit.mutation_event me
            where not exists (
              select 1 from stir_audit.verification_result vr
              where vr.audit_event_id = me.audit_event_id and vr.verifier_rule_version = ?
            )
            order by me.tenant_id, me.domain, me.sequence
            limit ?
            """.formatted(EVENT_COLUMNS);
        var out = new ArrayList<MutationEvent>();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, ruleVersion);
            ps.setInt(2, limit);
            try (var rs = ps.executeQuery()) { while (rs.next()) out.add(mapEvent(rs)); }
        }
        return out;
    }

    /** All events for one (tenant,domain) stream from a given sequence onward, in sequence order -
     * used by ChainVerifier to walk a stream's continuity, both incrementally (one predecessor
     * lookup at a time via fetchEventAtOrNull) and for P1-RA-007's periodic full reconciliation. */
    List<MutationEvent> fetchStreamFrom(UUID tenantId, String domain, long fromSequenceInclusive) throws SQLException {
        String sql = "select " + EVENT_COLUMNS +
            " from stir_audit.mutation_event where tenant_id = ? and domain = ? and sequence >= ? order by sequence";
        var out = new ArrayList<MutationEvent>();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setObject(1, tenantId); ps.setString(2, domain); ps.setLong(3, fromSequenceInclusive);
            try (var rs = ps.executeQuery()) { while (rs.next()) out.add(mapEvent(rs)); }
        }
        return out;
    }

    MutationEvent fetchEventAtOrNull(UUID tenant, String domain, long sequence) throws SQLException {
        String sql = "select " + EVENT_COLUMNS + " from stir_audit.mutation_event where tenant_id = ? and domain = ? and sequence = ?";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setObject(1, tenant); ps.setString(2, domain); ps.setLong(3, sequence);
            try (var rs = ps.executeQuery()) { return rs.next() ? mapEvent(rs) : null; }
        }
    }

    byte[] genesisHash(UUID tenant, String domain) throws SQLException {
        try (var ps = connection.prepareStatement("select stir_audit.genesis_hash(?, ?)")) {
            ps.setObject(1, tenant); ps.setString(2, domain);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getBytes(1); }
        }
    }

    /** P1-RA-005: version-dispatched. A V1 event (event_format_version='STIR_AUDIT_EVENT_V1',
     * every event this MVP wrote before the remediation) recomputes against the original,
     * untouched compute_event_hash(); a V2 event recomputes against compute_event_hash_v2(), which
     * additionally commits audit_event_id/db_time/txid/session_role_reported/application_name/
     * client_addr/event_format_version - never silently reinterpreting what a V1 event's own hash
     * already meant. */
    byte[] recomputeEventHash(MutationEvent e) throws SQLException {
        if ("STIR_AUDIT_EVENT_V1".equals(e.eventFormatVersion())) {
            try (var ps = connection.prepareStatement("select stir_audit.compute_event_hash(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setBytes(1, e.previousHash());
                ps.setLong(2, e.sequence());
                ps.setObject(3, e.tenantId());
                ps.setString(4, e.domain());
                ps.setLong(5, e.tableOid());
                ps.setString(6, e.tableName());
                ps.setString(7, e.operation());
                ps.setString(8, e.entityKeyCanonical());
                ps.setBytes(9, e.oldRowDigest());
                ps.setBytes(10, e.newRowDigest());
                ps.setString(11, e.rowDigestProfile());
                ps.setString(12, e.sessionUserName());
                ps.setObject(13, e.communityId());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getBytes(1); }
            }
        }
        if ("STIR_AUDIT_EVENT_V2".equals(e.eventFormatVersion())) {
            try (var ps = connection.prepareStatement(
                    "select stir_audit.compute_event_hash_v2(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setBytes(1, e.previousHash());
                ps.setLong(2, e.sequence());
                ps.setObject(3, e.auditEventId());
                ps.setObject(4, e.tenantId());
                ps.setString(5, e.domain());
                ps.setLong(6, e.tableOid());
                ps.setString(7, e.tableName());
                ps.setString(8, e.operation());
                ps.setString(9, e.entityKeyCanonical());
                ps.setBytes(10, e.oldRowDigest());
                ps.setBytes(11, e.newRowDigest());
                ps.setString(12, e.rowDigestProfile());
                ps.setString(13, e.sessionUserName());
                ps.setString(14, e.sessionRoleReported());
                ps.setString(15, e.applicationName());
                ps.setString(16, e.clientAddrText());
                ps.setString(17, e.requestId());
                ps.setString(18, e.correlationId());
                ps.setString(19, e.authorizationId());
                ps.setString(20, e.proposalId());
                ps.setString(21, e.dbTimeCanonical());
                ps.setString(22, e.txidText());
                ps.setString(23, e.eventFormatVersion());
                ps.setObject(24, e.communityId());
                try (var rs = ps.executeQuery()) { rs.next(); return rs.getBytes(1); }
            }
        }
        throw new IllegalStateException("Unknown event_format_version '" + e.eventFormatVersion() +
            "' for audit_event_id=" + e.auditEventId() + " - no recomputation formula registered for it.");
    }

    byte[] currentRowDigestOrNull(String tableName, List<String> pkColumns, String entityKeyCanonical) throws SQLException {
        Map<String, String> pk = parseEntityKeyCanonical(entityKeyCanonical);
        var where = new StringBuilder();
        var values = new ArrayList<String>();
        for (String col : pkColumns) {
            if (!where.isEmpty()) where.append(" and ");
            where.append('"').append(col).append("\"::text = ?");
            values.add(pk.get(col));
        }
        String sql = "select stir_audit.row_digest_pg17_jsonb_text_sha256_v1(to_jsonb(t)) from stir." + quoteIdent(tableName) + " t where " + where;
        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) ps.setString(i + 1, values.get(i));
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; }
        }
    }

    public static Map<String, String> parseEntityKeyCanonical(String canonical) {
        var out = new LinkedHashMap<String, String>();
        for (String pair : canonical.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private static String quoteIdent(String ident) { return "\"" + ident.replace("\"", "\"\"") + "\""; }

    List<String> entityKeysExpectedLive(long tableOid) throws SQLException {
        String sql = """
            select distinct on (entity_key_canonical) entity_key_canonical, operation
            from stir_audit.mutation_event where table_oid = ?
            order by entity_key_canonical, sequence desc
            """;
        var out = new ArrayList<String>();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tableOid);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) if (!"DELETE".equals(rs.getString("operation"))) out.add(rs.getString("entity_key_canonical"));
            }
        }
        return out;
    }

    /** P1-RA-003/P1-R2-002: every (table_oid, entity_key) pair captured once by V18's activation
     * ceremony - LEGACY_UNVERIFIED (present at the moment the ceremony ran), never VALID/AUTHORIZED/
     * AUDITED; these rows' own creation was never itself observed by this audit trail. Never events,
     * never a hash chain entry. See Reconciler's class javadoc for the full semantics. */
    Set<String> baselineImportedKeys(long tableOid) throws SQLException {
        var out = new HashSet<String>();
        try (var ps = connection.prepareStatement("select entity_key_canonical from stir_audit.baseline_import where table_oid = ?")) {
            ps.setLong(1, tableOid);
            try (var rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        }
        return out;
    }

    /** A single, internally consistent read of "what currently lives in this table", queried
     * inside one statement/snapshot - see Reconciler's own comment on why calling this exactly
     * once per table per pass (rather than issuing it, then issuing a second, separately-snapshot
     * query later) is what actually addresses the autocommit-race gap Codex flagged as unresolved. */
    List<String> liveEntityKeys(String tableName, List<String> pkColumns) throws SQLException {
        String concatExpr = pkColumns.stream().map(c -> "'" + c + "=' || \"" + c + "\"::text").reduce((a, b) -> a + " || ';' || " + b).orElseThrow();
        String sql = "select " + concatExpr + " as k from stir." + quoteIdent(tableName) + " t";
        var out = new ArrayList<String>();
        try (var ps = connection.prepareStatement(sql); var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString("k"));
        }
        return out;
    }

    /** Reconciliation's TOCTOU fix (Codex's own unreproduced-but-real concurrency gap note): the
     * three reads a table's reconciliation needs (live rows, mutation_event's "should still be
     * live" claims, baseline-imported keys) run inside ONE REPEATABLE READ transaction, so
     * PostgreSQL's own MVCC snapshot - fixed at this transaction's first statement, not the
     * connection's default per-statement READ COMMITTED - guarantees all three see the identical
     * point in time. A mutation that commits after this snapshot is taken is simply invisible to
     * this pass entirely (correctly deferred to the next reconciliation cycle), never partially
     * visible to one read and not another. No application-level lock of any kind. */
    record TableSnapshot(Set<String> live, Set<String> expectedLive, Set<String> baselineImported) {}

    private boolean priorAutoCommitBeforeOpenSnapshot;

    /** P1-R2 ChainVerifier race fix: begins a REPEATABLE READ, READ ONLY transaction that stays
     * open ACROSS multiple subsequent calls (unlike readConsistentTableSnapshot above, which opens,
     * reads, and closes within one method) until endRepeatableReadSnapshot() closes it. Codex's
     * second reaudit found `ChainVerifier.reconcileStreamFrom()` read `stream_head` and
     * `mutation_event` via two separate autocommit calls (PRE-FIX `ChainVerifier.java:74,78`) - a
     * commit landing between them could produce a transiently inconsistent view (a live head the
     * event read hasn't caught up to yet, or vice versa), surfacing as a false
     * `EXPECTED_EVENT_MISSING`/`STREAM_HEAD_MISMATCH`. Exposing explicit begin/end (rather than one
     * black-box method like readConsistentTableSnapshot) specifically lets a test interleave a real
     * writer commit between the head-read and the events-read and prove the snapshot still holds -
     * see VerifierDetectionTest's ChainVerifier race tests. */
    void beginRepeatableReadSnapshot() throws SQLException {
        priorAutoCommitBeforeOpenSnapshot = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (var st = connection.createStatement()) {
            st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        }
    }

    void endRepeatableReadSnapshot() throws SQLException {
        try {
            connection.commit(); // read-only; commit vs rollback is immaterial, commit releases resources promptly
        } finally {
            connection.setAutoCommit(priorAutoCommitBeforeOpenSnapshot);
        }
    }

    TableSnapshot readConsistentTableSnapshot(TableCoverage table) throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (var st = connection.createStatement()) {
            st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            // The first statement inside this block establishes the snapshot every later
            // statement in the same transaction reuses - order among these three reads no longer
            // matters for consistency, only that they share this one transaction.
            var live = new HashSet<>(liveEntityKeys(table.tableName(), table.pkColumns()));
            var expectedLive = new HashSet<>(entityKeysExpectedLive(table.tableOid()));
            var baseline = baselineImportedKeys(table.tableOid());
            connection.commit(); // read-only; commit vs rollback is immaterial, commit releases resources promptly
            return new TableSnapshot(live, expectedLive, baseline);
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    // ===== P1-RA-002: verdict + incident + cursor progress committed atomically. A crash at any
    // point before this transaction's COMMIT leaves NEITHER the verdict NOR the incident NOR the
    // cursor update persisted - the event is picked up again next cycle by fetchUnverifiedEvents
    // (which needs no cursor to find it) and reprocessed from scratch, producing the identical
    // verdict and, if it was a VIOLATION, the identical incident (deduplicated by dedup_key if the
    // very same incident is somehow computed twice). =====

    record IncidentToRecord(UUID tenantId, String domain, UUID auditEventId, String reasonCode, String evidenceJson, String dedupKey) {}

    /** Records a verdict, optionally one incident, and advances the observability cursor, all in
     * one JDBC transaction. `incident` is null for every non-VIOLATION verdict. Returns true only
     * when a NEW `security_incident` row was actually inserted (never for a non-incident verdict,
     * never when `ON CONFLICT (dedup_key) DO NOTHING` silently deduplicated an already-known
     * incident) - callers (VerifierLoop) use this to emit the external CRITICAL log line exactly
     * once per durable incident, not once per poll cycle that happens to re-observe a persistent
     * condition (the alert-storm half of Codex's second reaudit: dedup_key already deduplicated the
     * DB row, but the log line printed unconditionally on every cycle regardless). */
    boolean recordVerdictAtomically(UUID auditEventId, String ruleVersion, VerificationVerdict verdict,
                                     String reason, IncidentToRecord incident, String cursorName) throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            upsertVerificationResultTx(auditEventId, ruleVersion, verdict, reason);
            boolean newIncident = incident != null && insertSecurityIncidentTx(incident);
            if (cursorName != null) writeCursorTx(cursorName, auditEventId);
            connection.commit();
            return newIncident;
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    /** Same atomicity guarantee as recordVerdictAtomically, for a chain-level incident detected by
     * periodic full reconciliation (P1-RA-007), which has no single audit_event_id to anchor a
     * verification_result row to - only the incident (deduplicated by dedup_key) and the
     * reconciliation checkpoint advance together. Returns true only when a NEW incident row was
     * inserted, same alert-dedup contract as recordVerdictAtomically above. */
    boolean recordChainIncidentAtomically(IncidentToRecord incident, UUID tenant, String domain,
                                           long verifiedThroughSequence, byte[] verifiedHead) throws SQLException {
        boolean priorAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            boolean newIncident = incident != null && insertSecurityIncidentTx(incident);
            upsertChainCheckpointTx(tenant, domain, verifiedThroughSequence, verifiedHead);
            connection.commit();
            return newIncident;
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(priorAutoCommit);
        }
    }

    private void upsertVerificationResultTx(UUID auditEventId, String ruleVersion, VerificationVerdict verdict, String reason) throws SQLException {
        try (var ps = connection.prepareStatement("""
                insert into stir_audit.verification_result (audit_event_id, verifier_rule_version, result, reason, verified_at)
                values (?, ?, ?, ?, now())
                on conflict (audit_event_id, verifier_rule_version) do update set result = excluded.result, reason = excluded.reason, verified_at = now()
                """)) {
            ps.setObject(1, auditEventId); ps.setString(2, ruleVersion); ps.setString(3, verdict.name()); ps.setString(4, reason);
            ps.executeUpdate();
        }
    }

    /** For a Reconciler row-existence finding, which has no single audit_event_id to attach a
     * verification_result to and no chain checkpoint to advance - just the incident, deduplicated
     * by dedup_key. A single INSERT is already atomic on its own; no explicit transaction needed.
     * Returns true only when a NEW incident row was inserted (alert-dedup contract, see
     * recordVerdictAtomically). */
    boolean recordIncidentOnly(IncidentToRecord incident) throws SQLException {
        return insertSecurityIncidentTx(incident);
    }

    /** Returns true iff this call actually inserted a new row (i.e. `dedup_key` had never been seen
     * before) - false when `ON CONFLICT DO NOTHING` silently deduplicated an already-known
     * incident. `executeUpdate()`'s row count on an `ON CONFLICT DO NOTHING` statement is exactly
     * 0 for a deduplicated no-op and 1 for a real insert, so no `RETURNING` clause is needed. */
    private boolean insertSecurityIncidentTx(IncidentToRecord incident) throws SQLException {
        try (var ps = connection.prepareStatement(
                "insert into stir_audit.security_incident (tenant_id, domain, audit_event_id, reason_code, evidence, dedup_key) " +
                "values (?,?,?,?,?::jsonb,?) on conflict (dedup_key) do nothing")) {
            ps.setObject(1, incident.tenantId()); ps.setString(2, incident.domain()); ps.setObject(3, incident.auditEventId());
            ps.setString(4, incident.reasonCode()); ps.setString(5, incident.evidenceJson()); ps.setString(6, incident.dedupKey());
            return ps.executeUpdate() > 0;
        }
    }

    private void writeCursorTx(String cursorName, UUID lastAuditEventId) throws SQLException {
        try (var ps = connection.prepareStatement("""
                insert into stir_audit.verifier_cursor (cursor_name, last_audit_event_id, updated_at)
                values (?, ?, now())
                on conflict (cursor_name) do update set last_audit_event_id = excluded.last_audit_event_id, updated_at = now()
                """)) {
            ps.setString(1, cursorName);
            ps.setObject(2, lastAuditEventId);
            ps.executeUpdate();
        }
    }

    boolean hasVerificationResult(UUID auditEventId, String ruleVersion) throws SQLException {
        try (var ps = connection.prepareStatement("select 1 from stir_audit.verification_result where audit_event_id = ? and verifier_rule_version = ?")) {
            ps.setObject(1, auditEventId); ps.setString(2, ruleVersion);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    long countIncidents() throws SQLException { return queryLong("select count(*) from stir_audit.security_incident"); }

    // ===== P1-RA-007: periodic full-chain reconciliation checkpoint (durable, per stream) =====

    record ChainCheckpoint(long lastVerifiedSequence, byte[] lastVerifiedHead) {}

    ChainCheckpoint readChainCheckpoint(UUID tenant, String domain) throws SQLException {
        try (var ps = connection.prepareStatement(
                "select last_verified_sequence, last_verified_head from stir_audit.chain_reconciliation_checkpoint where tenant_id = ? and domain = ?")) {
            ps.setObject(1, tenant); ps.setString(2, domain);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return new ChainCheckpoint(0, null);
                return new ChainCheckpoint(rs.getLong("last_verified_sequence"), rs.getBytes("last_verified_head"));
            }
        }
    }

    private void upsertChainCheckpointTx(UUID tenant, String domain, long throughSequence, byte[] head) throws SQLException {
        try (var ps = connection.prepareStatement("""
                insert into stir_audit.chain_reconciliation_checkpoint (tenant_id, domain, last_verified_sequence, last_verified_head, last_run_at)
                values (?, ?, ?, ?, now())
                on conflict (tenant_id, domain) do update set last_verified_sequence = excluded.last_verified_sequence,
                  last_verified_head = excluded.last_verified_head, last_run_at = now()
                """)) {
            ps.setObject(1, tenant); ps.setString(2, domain); ps.setLong(3, throughSequence); ps.setBytes(4, head);
            ps.executeUpdate();
        }
    }

    /** All (tenant,domain) streams that currently have at least one event - the periodic scan's
     * work list. */
    List<Map.Entry<UUID, String>> allStreams() throws SQLException {
        var out = new ArrayList<Map.Entry<UUID, String>>();
        try (var ps = connection.prepareStatement("select tenant_id, domain from stir_audit.stream_head");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(Map.entry((UUID) rs.getObject("tenant_id"), rs.getString("domain")));
        }
        return out;
    }

    Map.Entry<Long, byte[]> currentStreamHead(UUID tenant, String domain) throws SQLException {
        try (var ps = connection.prepareStatement("select sequence, head_hash from stir_audit.stream_head where tenant_id = ? and domain = ?")) {
            ps.setObject(1, tenant); ps.setString(2, domain);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Map.entry(0L, new byte[0]);
                return Map.entry(rs.getLong("sequence"), rs.getBytes("head_hash"));
            }
        }
    }

    public long queryLong(String sql, Object... params) throws SQLException {
        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    public String queryStringOrNull(String sql, Object... params) throws SQLException {
        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public List<String> queryStringList(String sql, Object... params) throws SQLException {
        var out = new ArrayList<String>();
        try (var ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (var rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        }
        return out;
    }

    void listen(String channel) throws SQLException {
        try (var st = connection.createStatement()) { st.execute("LISTEN " + channel); }
    }

    boolean canSelect(String schemaQualifiedTable) throws SQLException {
        try (var ps = connection.prepareStatement("select has_table_privilege(current_user, ?, 'SELECT')")) {
            ps.setString(1, schemaQualifiedTable);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getBoolean(1); }
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
