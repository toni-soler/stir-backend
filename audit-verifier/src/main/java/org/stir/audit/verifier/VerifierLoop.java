package org.stir.audit.verifier;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.stir.audit.verifier.domain.DomainRule;

/** Durable polling loop with LISTEN/NOTIFY as a latency optimization only (NOTIFY is not a durable
 * queue - GOVERNED_STATE_AUDIT_ARCHITECTURE.md is explicit about this). Every cycle: fetch new
 * events since the durable cursor, verify chain + domain rule per event, persist an idempotent
 * verdict, report VIOLATION incidents, advance the cursor only after all of that succeeds. A crash
 * mid-cycle simply re-processes the same batch next time - verification_result's own
 * (audit_event_id, verifier_rule_version) primary key with ON CONFLICT DO UPDATE makes that safe,
 * and re-reporting the same incident reason for the same event is idempotent by construction
 * (security_incident rows are evidence, not a dedup key - a genuine re-detection after a crash is
 * expected to look identical, and this loop does not insert a second incident row for an event it
 * already reported once in this same batch, tracked in-memory for that single cycle only). */
final class VerifierLoop {
    private final Config config;
    private final String ruleVersion;
    private final HealthServer health;
    private final AtomicBoolean running = new AtomicBoolean(true);

    VerifierLoop(Config config, HealthServer health) {
        this.config = config;
        this.ruleVersion = DomainRuleEngine.RULE_VERSION;
        this.health = health;
    }

    void stop() { running.set(false); }

    void run() {
        while (running.get()) {
            try (var sql = new AuditSql(config)) {
                sql.listen("stir_audit_mutation"); // best-effort; the trigger does not NOTIFY in Phase 1, reserved for a later latency optimization
                runCycle(sql);
                health.markCycleSuccess();
            } catch (Exception e) {
                health.markCycleError(e.toString());
                System.err.println("level=ERROR component=stir-audit-verifier message=cycle_failed detail=" + e);
            }
            try { Thread.sleep(config.pollIntervalMillis()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); running.set(false); }
        }
    }

    private void runCycle(AuditSql sql) throws SQLException {
        var engine = new DomainRuleEngine();
        var chain = new ChainVerifier(sql);
        var incidents = new IncidentReporter(sql, System.out);

        AuditSql.CursorState cursor = sql.readCursor(config.cursorName());
        OffsetDateTime afterDbTime = cursor.lastDbTime();
        UUID afterEventId = cursor.lastAuditEventId();

        List<MutationEvent> batch;
        int reportedThisCycle = 0;
        do {
            batch = sql.fetchEventsAfter(afterDbTime, afterEventId, 500);
            for (MutationEvent event : batch) {
                if (sql.hasVerificationResult(event.auditEventId(), ruleVersion)) {
                    afterDbTime = event.dbTime(); afterEventId = event.auditEventId();
                    continue; // already processed in a prior cycle that crashed before advancing the cursor
                }
                VerificationVerdict finalVerdict;
                String reason;

                MutationEvent predecessor = event.sequence() > 1 ? sql.fetchEventAtOrNull(event.tenantId(), event.domain(), event.sequence() - 1) : null;
                ChainVerifier.Result chainResult;
                try { chainResult = chain.verifyLink(event, predecessor); }
                catch (SQLException e) { throw e; }

                if (chainResult instanceof ChainVerifier.Broken broken) {
                    finalVerdict = VerificationVerdict.VIOLATION;
                    reason = "CHAIN_" + broken.reasonCode() + ": " + broken.detail();
                } else {
                    DomainRule.Reasoned semantic = engine.evaluate(event, sql);
                    finalVerdict = semantic.verdict();
                    reason = semantic.reasonCode() + ": " + semantic.detail();
                }

                sql.upsertVerificationResult(event.auditEventId(), ruleVersion, finalVerdict, reason);
                if (finalVerdict == VerificationVerdict.VIOLATION) {
                    String evidence = "{\"table\":\"" + event.tableName() + "\",\"operation\":\"" + event.operation() +
                        "\",\"entityKey\":\"" + event.entityKeyCanonical().replace("\"", "'") + "\",\"reason\":\"" + reason.replace("\"", "'") + "\"}";
                    incidents.report(event.tenantId(), event.domain(), event.auditEventId(), reason.split(":")[0], evidence);
                    reportedThisCycle++;
                }
                afterDbTime = event.dbTime(); afterEventId = event.auditEventId();
                sql.writeCursor(config.cursorName(), afterEventId);
            }
        } while (batch.size() == 500);

        // Full reconciliation is comparatively expensive (a live-row scan per covered table), so it
        // runs once per cycle rather than per event, after the incremental pass above.
        var reconciler = new Reconciler(sql);
        for (Reconciler.Finding finding : reconciler.reconcileAll()) {
            String evidence = "{\"table\":\"" + finding.tableName() + "\",\"entityKey\":\"" +
                finding.entityKeyCanonical().replace("\"", "'") + "\",\"detail\":\"" + finding.detail().replace("\"", "'") + "\"}";
            incidents.report(null, null, null, finding.reasonCode(), evidence);
        }
        if (reportedThisCycle > 0) System.out.println("level=INFO component=stir-audit-verifier cycle_violations=" + reportedThisCycle);
    }
}
