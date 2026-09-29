package org.stir.audit.verifier;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Every method here runs as stir_auditor - never idax_app. Recomputation of hashes/digests calls
 * the SAME stir_audit.* SQL functions the trigger itself calls (GRANTed EXECUTE to stir_auditor
 * specifically for this purpose in V18), so there is exactly one implementation of the canonical
 * byte format in this whole system, not two that could silently drift. */
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

    /** Cursor is a single durable row per logical verifier instance: last audit_event_id processed
     * in a total INSERT order (mutation_event has no single global sequence, so this cursor tracks
     * db_time+audit_event_id as a stable "already seen" watermark, and a per-stream sequence map for
     * gap detection independent of that watermark). */
    record CursorState(UUID lastAuditEventId, OffsetDateTime lastDbTime) {}

    CursorState readCursor(String cursorName) throws SQLException {
        try (var ps = connection.prepareStatement(
                "select last_audit_event_id from stir_audit.verifier_cursor where cursor_name = ?")) {
            ps.setString(1, cursorName);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return new CursorState(null, null);
                UUID id = (UUID) rs.getObject("last_audit_event_id");
                if (id == null) return new CursorState(null, null);
                OffsetDateTime dbTime;
                try (var ps2 = connection.prepareStatement("select db_time from stir_audit.mutation_event where audit_event_id = ?")) {
                    ps2.setObject(1, id);
                    try (var rs2 = ps2.executeQuery()) { rs2.next(); dbTime = rs2.getObject("db_time", OffsetDateTime.class); }
                }
                return new CursorState(id, dbTime);
            }
        }
    }

    void writeCursor(String cursorName, UUID lastAuditEventId) throws SQLException {
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

    /** Ordered by (db_time, audit_event_id) as a total order stable enough to resume from - the
     * per-stream (tenant,domain,sequence) ordering used for chain verification is derived
     * separately inside ChainVerifier from whatever batch this returns. */
    List<MutationEvent> fetchEventsAfter(OffsetDateTime afterDbTime, UUID afterEventId, int limit) throws SQLException {
        String sql = """
            select audit_event_id, tenant_id, domain, sequence, db_time, table_oid, table_name, operation,
                   entity_key_canonical, community_id, old_row_digest, new_row_digest, row_digest_profile,
                   session_user_name, session_role_reported, application_name, request_id, correlation_id,
                   authorization_id, proposal_id, previous_hash, current_hash, event_format_version
            from stir_audit.mutation_event
            where (db_time, audit_event_id) > (coalesce(?, '-infinity'::timestamptz), coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid))
            order by db_time, audit_event_id
            limit ?
            """;
        var out = new ArrayList<MutationEvent>();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setObject(1, afterDbTime);
            ps.setObject(2, afterEventId);
            ps.setInt(3, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapEvent(rs));
            }
        }
        return out;
    }

    /** All events for one (tenant,domain) stream from a given sequence onward, in sequence order -
     * used by ChainVerifier to walk a stream's continuity independent of global fetch batching. */
    List<MutationEvent> fetchStreamFrom(UUID tenantId, String domain, long fromSequenceInclusive) throws SQLException {
        String sql = """
            select audit_event_id, tenant_id, domain, sequence, db_time, table_oid, table_name, operation,
                   entity_key_canonical, community_id, old_row_digest, new_row_digest, row_digest_profile,
                   session_user_name, session_role_reported, application_name, request_id, correlation_id,
                   authorization_id, proposal_id, previous_hash, current_hash, event_format_version
            from stir_audit.mutation_event where tenant_id = ? and domain = ? and sequence >= ? order by sequence
            """;
        var out = new ArrayList<MutationEvent>();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setObject(1, tenantId); ps.setString(2, domain); ps.setLong(3, fromSequenceInclusive);
            try (var rs = ps.executeQuery()) { while (rs.next()) out.add(mapEvent(rs)); }
        }
        return out;
    }

    private MutationEvent mapEvent(ResultSet rs) throws SQLException {
        return new MutationEvent(
            (UUID) rs.getObject("audit_event_id"), (UUID) rs.getObject("tenant_id"), rs.getString("domain"),
            rs.getLong("sequence"), rs.getObject("db_time", OffsetDateTime.class), rs.getLong("table_oid"),
            rs.getString("table_name"), rs.getString("operation"), rs.getString("entity_key_canonical"),
            (UUID) rs.getObject("community_id"), rs.getBytes("old_row_digest"), rs.getBytes("new_row_digest"),
            rs.getString("row_digest_profile"), rs.getString("session_user_name"), rs.getString("session_role_reported"),
            rs.getString("application_name"), rs.getString("request_id"), rs.getString("correlation_id"),
            rs.getString("authorization_id"), rs.getString("proposal_id"), rs.getBytes("previous_hash"),
            rs.getBytes("current_hash"), rs.getString("event_format_version"));
    }

    /** Single event by its natural stream position - used to fetch a predecessor for one-link
     * verification without needing the caller to have already fetched a contiguous batch. */
    MutationEvent fetchEventAtOrNull(UUID tenant, String domain, long sequence) throws SQLException {
        String sql = """
            select audit_event_id, tenant_id, domain, sequence, db_time, table_oid, table_name, operation,
                   entity_key_canonical, community_id, old_row_digest, new_row_digest, row_digest_profile,
                   session_user_name, session_role_reported, application_name, request_id, correlation_id,
                   authorization_id, proposal_id, previous_hash, current_hash, event_format_version
            from stir_audit.mutation_event where tenant_id = ? and domain = ? and sequence = ?
            """;
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

    /** Recomputes current_hash purely from the event's OWN already-stored fields (never from live
     * table state) - catches a stored event row whose fields were altered without recomputing its
     * hash. This can only ever be exercised by a table/function owner or PostgreSQL superuser: no
     * UPDATE policy on mutation_event exists for any role including stir_audit_owner. */
    byte[] recomputeEventHash(MutationEvent e) throws SQLException {
        try (var ps = connection.prepareStatement(
                "select stir_audit.compute_event_hash(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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

    /** The live row's current digest, or null if no row with this exact PK exists right now.
     * Entity key predicate compares every PK column as text - deliberately type-agnostic (uuid,
     * int, text all have a stable text form) since Phase 1 targets pilot-scale volume, not
     * index-optimal reconciliation queries. */
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
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getBytes(1);
            }
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

    /** Distinct entity keys whose LATEST recorded event is not a DELETE - the reconciler's set of
     * "this row should currently exist" claims, to check against the live table for a silent
     * disappearance the trigger should have caught. */
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

    /** Every currently-live PK for a covered table, as canonical strings in the same "col=val;..."
     * shape mutation_event stores - used to detect a row that exists with zero audit trail at all
     * (the other half of reconciliation: an insert the trigger somehow never fired for). */
    List<String> liveEntityKeys(String tableName, List<String> pkColumns) throws SQLException {
        String cols = String.join(",", pkColumns.stream().map(c -> "\"" + c + "\"").toList());
        String concatExpr = pkColumns.stream().map(c -> "'" + c + "=' || \"" + c + "\"::text").reduce((a, b) -> a + " || ';' || " + b).orElseThrow();
        String sql = "select " + concatExpr + " as k from stir." + quoteIdent(tableName) + " t";
        var out = new ArrayList<String>();
        try (var ps = connection.prepareStatement(sql); var rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString("k"));
        }
        return out;
    }

    void upsertVerificationResult(UUID auditEventId, String ruleVersion, VerificationVerdict verdict, String reason) throws SQLException {
        try (var ps = connection.prepareStatement("""
                insert into stir_audit.verification_result (audit_event_id, verifier_rule_version, result, reason, verified_at)
                values (?, ?, ?, ?, now())
                on conflict (audit_event_id, verifier_rule_version) do update set result = excluded.result, reason = excluded.reason, verified_at = now()
                """)) {
            ps.setObject(1, auditEventId); ps.setString(2, ruleVersion); ps.setString(3, verdict.name()); ps.setString(4, reason);
            ps.executeUpdate();
        }
    }

    boolean hasVerificationResult(UUID auditEventId, String ruleVersion) throws SQLException {
        try (var ps = connection.prepareStatement("select 1 from stir_audit.verification_result where audit_event_id = ? and verifier_rule_version = ?")) {
            ps.setObject(1, auditEventId); ps.setString(2, ruleVersion);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    void insertSecurityIncident(UUID tenantId, String domain, UUID auditEventId, String reasonCode, String evidenceJson) throws SQLException {
        try (var ps = connection.prepareStatement(
                "insert into stir_audit.security_incident (tenant_id, domain, audit_event_id, reason_code, evidence) values (?,?,?,?,?::jsonb)")) {
            ps.setObject(1, tenantId); ps.setString(2, domain); ps.setObject(3, auditEventId);
            ps.setString(4, reasonCode); ps.setString(5, evidenceJson);
            ps.executeUpdate();
        }
    }

    void listen(String channel) throws SQLException {
        try (var st = connection.createStatement()) { st.execute("LISTEN " + channel); }
    }

    /** True proof stir_auditor cannot even reach idax_app's own tables it has no business
     * reading (defense-in-depth self-check, not a security control by itself). */
    boolean canSelect(String schemaQualifiedTable) throws SQLException {
        try (var ps = connection.prepareStatement("select has_table_privilege(current_user, ?, 'SELECT')")) {
            ps.setString(1, schemaQualifiedTable);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getBoolean(1); }
        }
    }

    /** Generic scalar helper for the domain rules' own structural queries (signature counts, vote
     * duplicates, event-history checks) - all read-only, all against stir.* tables stir_auditor
     * already has SELECT on via its own cross-tenant RLS policy. */
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

    @Override public void close() throws SQLException { connection.close(); }
}
