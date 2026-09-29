package org.stir.audit.verifier;

import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** The only writer of stir_audit.security_incident, and the only place a CRITICAL log line is
 * emitted for a VIOLATION - always from the auditor's own process/container, never proxied
 * through STIR's own logs (GOVERNED_STATE_AUDIT_ARCHITECTURE.md: "emitir log estructurado desde
 * el contenedor auditor"). No auto-correction, no freeze, no rotation - this only records and
 * shouts; a human follows AUDIT_RUNBOOK.md from here. */
final class IncidentReporter {
    private final AuditSql sql;
    private final PrintStream out;
    IncidentReporter(AuditSql sql, PrintStream out) { this.sql = sql; this.out = out; }

    void report(UUID tenantId, String domain, UUID auditEventId, String reasonCode, String evidenceJson) throws SQLException {
        sql.insertSecurityIncident(tenantId, domain, auditEventId, reasonCode, evidenceJson);
        // Structured, single-line, grep-able: level=CRITICAL first field, machine-parseable JSON
        // tail. This line is the "canal fiable" health monitors and log shippers key off.
        out.println("level=CRITICAL component=stir-audit-verifier reason_code=" + reasonCode +
            " tenant_id=" + tenantId + " domain=" + domain + " audit_event_id=" + auditEventId +
            " at=" + Instant.now() + " evidence=" + evidenceJson);
        out.flush();
    }
}
