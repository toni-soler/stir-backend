package org.stir.audit.verifier;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.stir.audit.verifier.domain.DomainRule;

/** Durable polling loop. Two DELIBERATELY separate passes per cycle, matching
 * CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md's remediation instructions:
 *   1. Incremental event processing (fetchUnverifiedEvents - P1-RA-001: an anti-join against
 *      verification_result, never a time/UUID watermark, so a late-committing transaction is
 *      picked up on the very next cycle regardless of what any other stream did meanwhile).
 *   2. Periodic FULL chain reconciliation (every PERIODIC_RECONCILE_EVERY_N_CYCLES cycles -
 *      P1-RA-007: re-walks each stream from its own durable checkpoint through the live
 *      stream_head, catching historical tampering the incremental pass has already scrolled past).
 * verdict+incident+cursor-advance is one atomic transaction (P1-RA-002): a crash at any point
 * leaves nothing partially persisted, and reprocessing an already-fully-persisted event is a
 * cheap no-op (hasVerificationResult skip) rather than a correctness requirement. */
final class VerifierLoop {
    private final Config config;
    private final String ruleVersion;
    private final HealthServer health;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private long cycleCount = 0;
    private static final int PERIODIC_RECONCILE_EVERY_N_CYCLES = 10;

    VerifierLoop(Config config, HealthServer health) {
        this.config = config;
        this.ruleVersion = DomainRuleEngine.RULE_VERSION;
        this.health = health;
    }

    void stop() { running.set(false); }

    void run() {
        while (running.get()) {
            try (var sql = new AuditSql(config)) {
                sql.listen("stir_audit_mutation"); // best-effort; the trigger does not NOTIFY in Phase 1
                runIncrementalPass(sql);
                cycleCount++;
                if (cycleCount % PERIODIC_RECONCILE_EVERY_N_CYCLES == 0) runPeriodicChainReconciliation(sql);
                // Persistent/open incident count for /health, always refreshed AFTER both passes so
                // it reflects anything the periodic pass just added this cycle too - deliberately
                // separate from the CRITICAL log gating above: this is allowed, and meant, to stay
                // "open" every cycle without re-triggering a new external alert (see runIncrementalPass).
                health.setOpenIncidentCount(sql.countIncidents());
                health.markCycleSuccess();
            } catch (Exception e) {
                health.markCycleError(e.toString());
                System.err.println("level=ERROR component=stir-audit-verifier message=cycle_failed detail=" + e);
            }
            try { Thread.sleep(config.pollIntervalMillis()); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); running.set(false); }
        }
    }

    /** Runs the incremental pass exactly once, synchronously, for tests that need a single
     * deterministic cycle rather than the sleep-looping run(). */
    void runOneIncrementalPassForTest(AuditSql sql) throws SQLException { runIncrementalPass(sql); }
    void runOnePeriodicReconciliationForTest(AuditSql sql) throws SQLException { runPeriodicChainReconciliation(sql); }

    private void runIncrementalPass(AuditSql sql) throws SQLException {
        var engine = new DomainRuleEngine();
        var chain = new ChainVerifier(sql);
        int reportedThisCycle = 0;

        List<MutationEvent> batch;
        do {
            batch = sql.fetchUnverifiedEvents(ruleVersion, 500);
            for (MutationEvent event : batch) {
                MutationEvent predecessor = event.sequence() > 1 ? sql.fetchEventAtOrNull(event.tenantId(), event.domain(), event.sequence() - 1) : null;
                ChainVerifier.Result chainResult = chain.verifyLink(event, predecessor);

                VerificationVerdict finalVerdict;
                String reason;
                if (chainResult instanceof ChainVerifier.Broken broken) {
                    finalVerdict = VerificationVerdict.VIOLATION;
                    reason = "CHAIN_" + broken.reasonCode() + ": " + broken.detail();
                } else {
                    DomainRule.Reasoned semantic = engine.evaluate(event, sql);
                    finalVerdict = semantic.verdict();
                    reason = semantic.reasonCode() + ": " + semantic.detail();
                }

                AuditSql.IncidentToRecord incident = null;
                if (finalVerdict == VerificationVerdict.VIOLATION) {
                    String reasonCode = reason.split(":", 2)[0];
                    String dedupKey = event.auditEventId() + ":" + ruleVersion + ":" + reasonCode;
                    String evidence = "{\"table\":\"" + event.tableName() + "\",\"operation\":\"" + event.operation() +
                        "\",\"entityKey\":\"" + jsonEscape(event.entityKeyCanonical()) + "\",\"reason\":\"" + jsonEscape(reason) + "\"}";
                    incident = new AuditSql.IncidentToRecord(event.tenantId(), event.domain(), event.auditEventId(), reasonCode, evidence, dedupKey);
                    reportedThisCycle++;
                }
                // P1-RA-002: verdict + incident + cursor advance, one transaction. A crash at any
                // point in between leaves nothing committed; fetchUnverifiedEvents finds this exact
                // event again next cycle since no verification_result row exists for it yet.
                boolean isNewIncident = sql.recordVerdictAtomically(event.auditEventId(), ruleVersion, finalVerdict, reason, incident, config.cursorName());
                // Alert-dedup fix: fetchUnverifiedEvents anti-joins on verification_result, so in
                // practice this exact branch only ever sees a genuinely new event - isNewIncident is
                // checked anyway for the same reason the Reconciler/chain-reconciliation branches
                // below need it: a uniform, always-correct "only log what wasn't already known" rule,
                // not one that quietly depends on fetchUnverifiedEvents' own anti-join to hold.
                if (incident != null && isNewIncident) {
                    System.out.println("level=CRITICAL component=stir-audit-verifier reason_code=" + incident.reasonCode() +
                        " tenant_id=" + incident.tenantId() + " domain=" + incident.domain() + " audit_event_id=" + incident.auditEventId() +
                        " dedup_key=" + incident.dedupKey() + " evidence=" + incident.evidenceJson());
                    System.out.flush();
                }
            }
        } while (batch.size() == 500);

        // Reconciler stays a per-cycle pass (cheap relative to a full chain re-hash): it only
        // compares row existence against mutation_event's own claims, not per-link hash math. Unlike
        // the incremental pass above, THIS is exactly the case Codex's second reaudit caught: a
        // structural condition (a row with no event) is persistent and gets rediscovered every
        // cycle, so dedup_key alone (a DB-level no-op) is not enough - the CRITICAL log line itself
        // must only fire when insertSecurityIncidentTx() reports a genuinely new row.
        var reconciler = new Reconciler(sql);
        for (Reconciler.Finding finding : reconciler.reconcileAll()) {
            String dedupKey = "RECONCILE:" + finding.tableOid() + ":" + finding.entityKeyCanonical() + ":" + finding.reasonCode();
            String evidence = "{\"table\":\"" + finding.tableName() + "\",\"entityKey\":\"" +
                jsonEscape(finding.entityKeyCanonical()) + "\",\"detail\":\"" + jsonEscape(finding.detail()) + "\"}";
            var incident = new AuditSql.IncidentToRecord(null, null, null, finding.reasonCode(), evidence, dedupKey);
            boolean isNewIncident = sql.recordIncidentOnly(incident);
            if (isNewIncident) {
                System.out.println("level=CRITICAL component=stir-audit-verifier reason_code=" + finding.reasonCode() +
                    " table=" + finding.tableName() + " dedup_key=" + dedupKey + " evidence=" + evidence);
                System.out.flush();
            }
        }
        if (reportedThisCycle > 0) System.out.println("level=INFO component=stir-audit-verifier cycle_violations=" + reportedThisCycle);
    }

    /** P1-RA-007: re-walks each stream from GENESIS (sequence 1) through the live stream_head on
     * every periodic pass - deliberately NOT resumed from the durable checkpoint. Resuming from
     * checkpoint+1 was the first draft of this fix and was wrong: reconcileStreamFrom only
     * re-fetches the checkpoint's own boundary event fresh from the DB, so a tamper to an INTERIOR
     * already-checkpointed event (neither the checkpoint boundary nor the live head) would never be
     * re-examined by any later pass once the checkpoint had advanced past it - silently reopening
     * exactly the gap P1-RA-007 exists to close. A full genesis walk costs O(chain length) per
     * periodic pass (see VALIDATION_GOVERNED_STATE_AUDIT_MVP.md's performance section for the
     * measured cost and the documented Phase-2-scale tradeoff); the checkpoint table itself is now
     * pure observability/bookkeeping ("last sequence a full walk verified clean through as of
     * last_run_at"), never a resume point that skips re-verifying historical links. A clean walk
     * advances the checkpoint; a broken one does not (so the same break is re-detected, and
     * re-deduplicated by dedup_key, on every later periodic pass until a human resolves it per
     * AUDIT_RUNBOOK.md - no auto-correction). */
    private void runPeriodicChainReconciliation(AuditSql sql) throws SQLException {
        var chain = new ChainVerifier(sql);
        for (Map.Entry<UUID, String> stream : sql.allStreams()) {
            UUID tenant = stream.getKey();
            String domain = stream.getValue();
            var checkpoint = sql.readChainCheckpoint(tenant, domain);
            var outcome = chain.reconcileStreamFrom(tenant, domain, 1);

            if (outcome.result() instanceof ChainVerifier.Broken broken) {
                String dedupKey = tenant + ":" + domain + ":CHAIN_RECONCILE:" + broken.reasonCode() + ":" + (outcome.verifiedThroughSequence() + 1);
                String evidence = "{\"tenant\":\"" + tenant + "\",\"domain\":\"" + domain + "\",\"detail\":\"" + jsonEscape(broken.detail()) + "\"}";
                var incident = new AuditSql.IncidentToRecord(tenant, domain, null, "CHAIN_RECONCILE_" + broken.reasonCode(), evidence, dedupKey);
                // Do NOT advance the checkpoint past a break - the same gap must be re-detected
                // (and re-deduplicated) on every later cycle until resolved. The break itself is a
                // persistent condition re-discovered every periodic pass, exactly the alert-storm
                // shape Codex's second reaudit found here specifically - only log when genuinely new.
                boolean isNewIncident = sql.recordChainIncidentAtomically(incident, tenant, domain, checkpoint.lastVerifiedSequence(), checkpoint.lastVerifiedHead());
                if (isNewIncident) {
                    System.out.println("level=CRITICAL component=stir-audit-verifier reason_code=CHAIN_RECONCILE_" + broken.reasonCode() +
                        " tenant_id=" + tenant + " domain=" + domain + " dedup_key=" + dedupKey + " evidence=" + evidence);
                    System.out.flush();
                }
            } else {
                sql.recordChainIncidentAtomically(null, tenant, domain, outcome.verifiedThroughSequence(), outcome.verifiedHead());
            }
        }
    }

    private static String jsonEscape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
