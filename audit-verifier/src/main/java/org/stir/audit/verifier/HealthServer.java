package org.stir.audit.verifier;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** No framework, no STIR dependency, no UI - just enough for a separate monitor/Docker healthcheck.
 * Deliberately exposes TWO SEPARATE endpoints, per P1-R3-001 (Codex's third reaudit) - never mixed
 * into one:
 *
 *   /health          - liveness ONLY: "is this process alive and how stale is its last successful
 *                       cycle". In-memory, cheap, never touches the database. Reports unhealthy
 *                       (503) once staleness exceeds a fixed threshold; never auto-restarts or
 *                       self-heals.
 *   /security-status - the durable, canonical security state, per P1-R3-001's explicit architecture
 *                       decision: `stir_audit.security_incident` (not this process's memory, not
 *                       whether a CRITICAL log line was ever printed) is the source of truth. Every
 *                       request queries the database directly and fresh - a brand new process that
 *                       has never run a single VerifierLoop cycle yet still reports
 *                       CRITICAL_SECURITY_INCIDENT correctly if a durable incident already exists,
 *                       because it never relies on in-memory state accumulated by that process.
 *
 * Keeping these separate is deliberate, not an oversight: an orchestrator that conflates "a security
 * incident is open" with "this process is unhealthy" would restart the auditor in a loop trying to
 * "cure" a condition that a process restart can never fix (the incident lives in the database, not
 * in the process). /health's 200/503 status never depends on security_incident in any way. */
final class HealthServer {
    private final Config config;
    private final AtomicReference<Instant> lastSuccessfulCycle = new AtomicReference<>(null);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private static final long STALE_AFTER_SECONDS = 60;

    HealthServer(Config config) { this.config = config; }

    void markCycleSuccess() { lastSuccessfulCycle.set(Instant.now()); lastError.set(null); }
    void markCycleError(String message) { lastError.set(message); }

    /** P1-R3-001: `CLEAR` iff zero durable incidents exist right now; `CRITICAL_SECURITY_INCIDENT`
     * otherwise. Queried fresh against the database on every call - never cached, never derived from
     * this process's own in-memory history, so it is correct even immediately after a restart with
     * zero completed VerifierLoop cycles. */
    record SecurityStatus(String securityState, long openIncidentCount, String latestIncidentId,
                           String latestIncidentDedupKey, String latestIncidentReasonCode,
                           String latestIncidentDetectedAt, String dbTime) {}

    static SecurityStatus querySecurityStatus(Config config) throws SQLException {
        try (Connection c = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password())) {
            long count;
            try (var ps = c.prepareStatement("select count(*) from stir_audit.security_incident");
                 var rs = ps.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
            }
            String latestId = null, latestDedupKey = null, latestReasonCode = null, latestDetectedAt = null;
            // detected_at desc, incident_id desc as tiebreak: security_incident has no dedicated
            // sequence/high-water column (deliberately not added just for this - see P1-R3-001
            // remediation notes, "opcionalmente... si existe de forma limpia" was judged not to
            // apply cleanly enough to justify a new migration for an optional field).
            try (var ps = c.prepareStatement(
                    "select incident_id::text, dedup_key, reason_code, detected_at::text " +
                    "from stir_audit.security_incident order by detected_at desc, incident_id desc limit 1");
                 var rs = ps.executeQuery()) {
                if (rs.next()) {
                    latestId = rs.getString(1);
                    latestDedupKey = rs.getString(2);
                    latestReasonCode = rs.getString(3);
                    latestDetectedAt = rs.getString(4);
                }
            }
            String dbTime;
            try (var ps = c.prepareStatement("select now()::text"); var rs = ps.executeQuery()) {
                rs.next();
                dbTime = rs.getString(1);
            }
            String state = count > 0 ? "CRITICAL_SECURITY_INCIDENT" : "CLEAR";
            return new SecurityStatus(state, count, latestId, latestDedupKey, latestReasonCode, latestDetectedAt, dbTime);
        }
    }

    HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            Instant last = lastSuccessfulCycle.get();
            String error = lastError.get();
            boolean healthy = last != null && java.time.Duration.between(last, Instant.now()).getSeconds() < STALE_AFTER_SECONDS && error == null;
            String body = "{\"healthy\":" + healthy + ",\"lastSuccessfulCycle\":\"" + last + "\",\"lastError\":" +
                (error == null ? "null" : "\"" + error.replace("\"", "'") + "\"") + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(healthy ? 200 : 503, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.createContext("/security-status", exchange -> {
            try {
                SecurityStatus status = querySecurityStatus(config);
                String body = "{\"securityState\":\"" + status.securityState() + "\"" +
                    ",\"openIncidentCount\":" + status.openIncidentCount() +
                    ",\"latestIncidentId\":" + jsonString(status.latestIncidentId()) +
                    ",\"latestIncidentDedupKey\":" + jsonString(status.latestIncidentDedupKey()) +
                    ",\"latestIncidentReasonCode\":" + jsonString(status.latestIncidentReasonCode()) +
                    ",\"latestIncidentDetectedAt\":" + jsonString(status.latestIncidentDetectedAt()) +
                    ",\"dbTime\":" + jsonString(status.dbTime()) + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                // Always 200 when the query itself succeeded - CRITICAL_SECURITY_INCIDENT is
                // reported IN the body, never as an HTTP error status (see class javadoc: a
                // security incident must never look like "this process is unhealthy").
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (SQLException e) {
                // A genuinely different operational condition (DB unreachable, credentials revoked,
                // etc.) - this process is up but cannot determine security status at all. 503 here
                // means "ask again / investigate the DB path", never "no incidents".
                String body = "{\"error\":\"security status query failed: " + jsonEscape(e.getMessage()) + "\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        server.start();
        return server;
    }

    private static String jsonString(String s) { return s == null ? "null" : "\"" + jsonEscape(s) + "\""; }
    private static String jsonEscape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
