package org.stir.audit.verifier;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** P1-R3-001 (THIRD_REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md): "alert delivery is at-most-once
 * and may be zero". Codex's own reproduction used an isolated harness (`CrashAfterCommitProbe.java`)
 * that called the real `AuditSql.recordIncidentOnly()`, confirmed `POST_COMMIT=true`, then
 * `Runtime.halt(137)` before any CRITICAL log line - then started the real jar and observed zero
 * new CRITICAL log lines for that incident, even though the row and `/health`'s conceptual
 * equivalent (openIncidentCount) were both durable.
 *
 * This test closes exactly that reproduction against the real production entry points: a genuinely
 * separate JVM subprocess (`CrashAfterCommitProbe`) commits an incident and halts before logging,
 * then a brand new `Main` subprocess (the real HTTP server, the real `VerifierLoop`, launched with
 * zero prior in-memory state) is polled over real HTTP at `/security-status` - which must report
 * CRITICAL_SECURITY_INCIDENT with this exact incident's fingerprint, persistently across repeated
 * polls, regardless of whether any CRITICAL log line was ever printed for it. Per the architecture
 * decision this finding drove: `security_incident` is the durable canonical security alert; a log
 * line is BEST_EFFORT_CRITICAL_LOG, never GUARANTEED_ALERT_DELIVERY - this test proves the former
 * without depending on the latter. */
@Testcontainers
class SecurityStatusCrashReproductionTest {
    @Container static PostgreSQLContainer<?> postgres = AuditTestSupport.newPostgres();
    static Config config;
    static String classpath;
    static String javaBin;

    @BeforeAll static void setup() throws SQLException {
        AuditTestSupport.migrate(postgres);
        config = AuditTestSupport.auditorConfig(postgres);
        classpath = System.getProperty("java.class.path");
        javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    @Test void crashAfterIncidentCommitButBeforeCriticalLogStillLeavesDurableCriticalSecurityStatus() throws Exception {
        UUID tenant = UUID.randomUUID();
        String domain = "CONSTITUTION";
        String dedupKey = "crash-repro-" + UUID.randomUUID();
        String reasonCode = "P1_R3_001_CRASH_REPRO";

        // 1. The probe: a genuinely separate JVM, real AuditSql.recordIncidentOnly(), confirmed
        // commit, halt(137) strictly before any CRITICAL log line.
        Process probe = new ProcessBuilder(javaBin, "-cp", classpath, "org.stir.audit.verifier.CrashAfterCommitProbe",
                postgres.getJdbcUrl(), "stir_auditor", AuditTestSupport.STIR_AUDITOR_PASSWORD,
                tenant.toString(), domain, dedupKey, reasonCode)
            .redirectErrorStream(true).start();
        String probeOutput = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean probeExited = probe.waitFor(30, TimeUnit.SECONDS);
        assertTrue(probeExited, "the probe subprocess must exit on its own within the timeout");
        assertTrue(probeOutput.contains("POST_COMMIT=true"), "the probe must confirm it committed the incident before halting: " + probeOutput);
        assertFalse(probeOutput.contains("CRITICAL"), "the probe must halt strictly BEFORE any CRITICAL log line: " + probeOutput);
        assertEquals(137, probe.exitValue(), "the probe must have actually Runtime.halt(137)'d, not exited normally");

        // 2. Confirm directly against the DB, independent of any process: the incident is durable.
        try (AuditSql sql = new AuditSql(config)) {
            long count = sql.queryLong("select count(*) from stir_audit.security_incident where dedup_key = ?", dedupKey);
            assertEquals(1, count, "the incident row must be durably present after the probe's crash, with zero in-memory state from any process involved");
        }

        // 3. The "restart": a brand new Main subprocess - the real production HTTP server, zero
        // prior VerifierLoop cycles, zero in-memory knowledge that this specific incident exists.
        int healthPort = findFreePort();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classpath, "org.stir.audit.verifier.Main");
        pb.environment().put("STIR_AUDITOR_JDBC_URL", postgres.getJdbcUrl());
        pb.environment().put("STIR_AUDITOR_USER", "stir_auditor");
        pb.environment().put("STIR_AUDITOR_PASSWORD", AuditTestSupport.STIR_AUDITOR_PASSWORD);
        pb.environment().put("STIR_AUDITOR_HEALTH_PORT", String.valueOf(healthPort));
        pb.environment().put("STIR_AUDITOR_POLL_INTERVAL_MS", "300");
        pb.redirectErrorStream(true);
        Process auditor = pb.start();
        var auditorOutput = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var reader = auditor.inputReader()) {
                int ch;
                while ((ch = reader.read()) != -1) auditorOutput.append((char) ch);
            } catch (Exception ignored) { }
        });
        drain.setDaemon(true);
        drain.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + healthPort + "/security-status"))
                .timeout(Duration.ofSeconds(2)).GET().build();

            String lastBody = null;
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200 && resp.body().contains("CRITICAL_SECURITY_INCIDENT")) {
                        lastBody = resp.body();
                        break;
                    }
                } catch (Exception retryable) {
                    // HTTP port not bound yet - retry.
                }
                Thread.sleep(300);
            }
            assertNotNull(lastBody, "a fresh auditor instance, with zero prior cycles, must expose CRITICAL_SECURITY_INCIDENT via /security-status without ever having printed a CRITICAL log for this crash-recovered incident. Auditor output so far: " + auditorOutput);
            assertTrue(lastBody.contains(dedupKey), "the security-status response must surface this exact incident's fingerprint (dedup_key): " + lastBody);
            assertTrue(lastBody.contains(reasonCode), "the security-status response must surface this incident's reason code: " + lastBody);
            assertFalse(auditorOutput.toString().contains(dedupKey) && auditorOutput.toString().contains("CRITICAL"),
                "the fresh auditor process must NOT have printed a CRITICAL log line for THIS incident - it was already committed by the probe before this process ever started, exactly proving the log is best-effort and the DB/security-status is the durable channel");

            // 4. Persistence across repeated, independent polls - not a one-shot fluke.
            for (int i = 0; i < 3; i++) {
                Thread.sleep(400);
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, resp.statusCode());
                assertTrue(resp.body().contains("CRITICAL_SECURITY_INCIDENT"), "poll #" + i + ": " + resp.body());
                assertTrue(resp.body().contains("\"openIncidentCount\":") && !resp.body().contains("\"openIncidentCount\":0"), "poll #" + i + ": " + resp.body());
            }

            // 5. /health (liveness) must stay entirely unaffected by the open security incident -
            // the two endpoints must never be conflated (see HealthServer's own class javadoc).
            HttpResponse<String> healthResp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + healthPort + "/health")).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healthResp.statusCode(), "liveness must report healthy=200 regardless of the open security incident: " + healthResp.body());
            assertFalse(healthResp.body().contains("openIncidentCount"), "/health must not even mention incident counts - that belongs exclusively to /security-status: " + healthResp.body());
        } finally {
            auditor.destroy();
            if (!auditor.waitFor(10, TimeUnit.SECONDS)) auditor.destroyForcibly();
        }
    }

    /** Baseline/control, no crash involved: a completely clean database (no incidents at all) must
     * report CLEAR, and openIncidentCount=0 - proving /security-status doesn't just always say
     * CRITICAL, and that CLEAR is a real, reachable state. */
    @Test void securityStatusReportsClearWhenNoIncidentsExist() throws Exception {
        try (PostgreSQLContainer<?> freshPg = AuditTestSupport.newPostgres()) {
            freshPg.start();
            AuditTestSupport.migrate(freshPg);
            Config freshConfig = AuditTestSupport.auditorConfig(freshPg);
            var status = HealthServer.querySecurityStatus(freshConfig);
            assertEquals("CLEAR", status.securityState());
            assertEquals(0, status.openIncidentCount());
            assertNull(status.latestIncidentId());
        }
    }

    private static int findFreePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
