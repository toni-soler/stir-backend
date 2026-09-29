package org.stir.audit.verifier;

import java.util.UUID;

/** Test-only subprocess entry point for P1-R3-001's exact crash reproduction
 * (SECOND/THIRD_REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md's own "CrashAfterCommitProbe" pattern):
 * persist a `security_incident` row via the REAL production method
 * (`AuditSql.recordIncidentOnly`), confirm its commit, then `Runtime.halt(137)` BEFORE any
 * BEST_EFFORT_CRITICAL_LOG line - simulating a crash in the exact post-commit/pre-log window
 * `VerifierLoop`'s own code leaves open by design (the transaction already committed; only the
 * best-effort log write after it is lost).
 *
 * Launched as a genuinely separate JVM by `SecurityStatusCrashReproductionTest` (never in-process -
 * `Runtime.halt()` would otherwise kill the test runner itself), reusing the current test
 * classpath, so this exercises the real `AuditSql`/`Config` production classes, not a stand-in. */
public final class CrashAfterCommitProbe {
    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0], user = args[1], password = args[2];
        UUID tenant = UUID.fromString(args[3]);
        String domain = args[4];
        String dedupKey = args[5];
        String reasonCode = args[6];
        Config config = new Config(jdbcUrl, user, password, 200L, 0, "crash-probe");
        try (AuditSql sql = new AuditSql(config)) {
            var incident = new AuditSql.IncidentToRecord(tenant, domain, null, reasonCode, "{\"probe\":\"P1-R3-001\"}", dedupKey);
            boolean isNew = sql.recordIncidentOnly(incident);
            // Printed and flushed BEFORE the halt so the parent process can confirm the commit
            // actually happened - this line is the probe's own bookkeeping, not the
            // BEST_EFFORT_CRITICAL_LOG line VerifierLoop would print; that one is deliberately never
            // reached here.
            System.out.println("POST_COMMIT=" + isNew);
            System.out.flush();
        }
        Runtime.getRuntime().halt(137);
    }
}
