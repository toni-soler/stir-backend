package org.stir.audit.verifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.stir.audit.verifier.domain.DomainRule;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL 17, real V1-V18 migrations, real idax_app/idax_admin roles, the real
 * DomainRuleEngine/ChainVerifier this MVP ships - not a mock of any of it.
 * CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md #7's numbered scenarios, covered here or in
 * StirAuditTriggerPostgresTest (stir-backend's own module, for the DB-role-denial half). */
@Testcontainers
class VerifierDetectionTest {
    @Container static PostgreSQLContainer<?> postgres = AuditTestSupport.newPostgres();
    static Config config;

    @BeforeAll static void migrate() throws SQLException {
        AuditTestSupport.migrate(postgres);
        config = AuditTestSupport.auditorConfig(postgres);
    }

    private Connection admin() throws SQLException { return AuditTestSupport.privilegedConnection(postgres); }

    private UUID insertLegitimateAmendedConstitution(Connection c, UUID tenant, UUID authority, UUID community) throws SQLException {
        // constitutional_authority's own pre-existing seven_seats_at_commit trigger is
        // DEFERRABLE INITIALLY DEFERRED - it only checks at real transaction COMMIT, so the
        // authority insert and its seven seat inserts must share one explicit transaction, not
        // autocommit's one-statement-per-transaction default.
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("insert into stir.constitutional_authority values ('" + authority + "','" + tenant + "','" + community +
                "',7,'" + UUID.randomUUID() + "','pk','ACTIVE',2,now(),'SOFTWARE_ED25519','Ed25519')");
            for (int seat = 1; seat <= 7; seat++) {
                st.execute("insert into stir.constitutional_seat values ('" + tenant + "','" + authority + "'," + seat + ",'" +
                    UUID.randomUUID() + "','" + UUID.randomUUID() + "','pk" + seat + "','ACTIVE','SOFTWARE_ED25519','Ed25519')");
            }
        }
        UUID proposalId = UUID.randomUUID();
        String digest = "amend-digest-" + proposalId;
        try (Statement st = c.createStatement()) {
            st.execute("insert into stir.constitutional_proposal values ('" + proposalId + "','" + tenant + "','" + authority +
                "','" + community + "',2,'AMEND_CONSTITUTION','before','" + digest + "','{}','[]','reason','[]',1,'{}','payload-digest',now())");
            for (int seat = 1; seat <= 7; seat++) {
                st.execute("insert into stir.constitutional_signature values ('" + UUID.randomUUID() + "','" + tenant + "','" + proposalId +
                    "'," + seat + ",'" + UUID.randomUUID() + "','sig" + seat + "',now(),null,null)");
            }
        }
        UUID constitutionId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into stir.market_constitution values (?,?,?,?,2,'{}',?,now())")) {
            ps.setObject(1, constitutionId); ps.setObject(2, tenant); ps.setObject(3, community); ps.setObject(4, authority); ps.setString(5, digest);
            ps.executeUpdate();
        }
        c.commit();
        c.setAutoCommit(true);
        return constitutionId;
    }

    @Test void legitimateAmendmentClassifiesStructureOnlyNeverAuthorized() throws Exception {
        UUID tenant = UUID.randomUUID(), authority = UUID.randomUUID(), community = UUID.randomUUID();
        try (Connection c = admin()) {
            insertLegitimateAmendedConstitution(c, tenant, authority, community);
        }
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500);
            var constitutionEvent = events.stream().filter(e -> "market_constitution".equals(e.tableName()) && tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(constitutionEvent, sql);
            assertEquals(VerificationVerdict.PASS_STRUCTURE_ONLY, verdict.verdict());
            assertNotEquals("PASS_AUTHORIZED", verdict.verdict().name(), "PASS_AUTHORIZED must never exist as a value at all");
        }
    }

    @Test void forgedConstitutionWithoutProposalIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 999999, '{}', 'forged-digest', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500);
            var forged = events.stream().filter(e -> "market_constitution".equals(e.tableName()) && tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(forged, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("CONSTITUTION_WITHOUT_MATCHING_PROPOSAL", verdict.reasonCode());
        }
    }

    private UUID[] insertReferenceChain(Connection c, UUID tenant, UUID community) throws SQLException {
        UUID unitId = UUID.randomUUID(), definitionId = UUID.randomUUID(), observationId = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
            st.execute("insert into stir.reference_definition values ('" + definitionId + "','" + tenant + "','" + community +
                "','" + unitId + "','name','scope','{}','1','unit','ref','" + UUID.randomUUID() + "',now())");
            st.execute("insert into stir.reference_observation (id,tenant_id,definition_id,source,source_id,aggregate_consent,observed_at) values ('" +
                observationId + "','" + tenant + "','" + definitionId + "','AGREEMENT','" + UUID.randomUUID() + "',true,now())");
        }
        return new UUID[]{definitionId, observationId};
    }

    @Test void legitimateSignalUnderReviewFinalClassifiesStructureOnly() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','signal reason','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','review reason','" + decisor + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','final reason','" + decisor + "',now(),3)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 200);
            // sequence() here is the AUDIT stream's own sequence, not case_event's `sequence`
            // column - the audit trigger fires in insertion order, so the last INTEGRITY-domain
            // case_event event fetched for this tenant is the FINAL row (inserted third).
            var finalCandidate = events.stream().filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(finalCandidate, sql);
            assertEquals(VerificationVerdict.PASS_STRUCTURE_ONLY, verdict.verdict());
        }
    }

    @Test void forgedFinalWithoutSignalIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
            }
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
                    st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                        UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','forged','" + UUID.randomUUID() + "',now(),1)");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 200);
            var forged = events.stream().filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(forged, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            // P1-RA-004 rewrite: IntegrityRule now validates the full transition chain rather than
            // just "was there a prior SIGNAL row" - a lone forged FINAL at sequence=1 fails the
            // "first event must be SIGNAL" check before it would ever reach a transition check.
            assertEquals("FIRST_EVENT_NOT_SIGNAL", verdict.reasonCode());
        }
    }

    @Test void idaxAppAndIdaxAdminCannotTouchAuditJournalOrHead() throws Exception {
        try (Connection c = admin()) {
            for (String role : new String[]{"idax_app", "idax_admin"}) {
                try (Statement st = c.createStatement()) { st.execute("set role " + role); }
                for (String attack : new String[]{
                        "insert into stir_audit.mutation_event (tenant_id,domain,sequence,table_oid,table_name,operation,entity_key,entity_key_canonical,row_digest_profile,session_user_name,previous_hash,current_hash,event_format_version) values (gen_random_uuid(),'X',1,1,'x','INSERT','{}','x','x','x','\\x00'::bytea,'\\x00'::bytea,'x')",
                        "delete from stir_audit.mutation_event",
                        "update stir_audit.stream_head set sequence = 99999",
                        "truncate stir_audit.mutation_event",
                        "drop function stir_audit.emit_mutation_event()"}) {
                    try (Statement st = c.createStatement()) {
                        assertThrows(SQLException.class, () -> st.execute(attack), role + " should not be able to: " + attack);
                    }
                }
                try (Statement st = c.createStatement()) { st.execute("reset role"); }
            }
        }
    }

    @Test void chainTamperIsDetected() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'x', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
            // Only the owner/superuser can even reach this row per the FORCE RLS + owner-only
            // policy proven in idaxAppAndIdaxAdminCannotTouchAuditJournalOrHead - this simulates
            // that stronger, explicitly out-of-scope threat (PostgreSQL superuser/owner) to prove
            // the chain math itself, not the role boundary, catches a rewritten hash.
            try (Statement st = c.createStatement()) {
                st.execute("update stir_audit.mutation_event set current_hash = '\\xdeadbeef'::bytea where tenant_id = '" + tenant + "'");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500);
            var tampered = events.stream().filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var chain = new ChainVerifier(sql);
            var result = chain.verifyLink(tampered, null);
            assertInstanceOf(ChainVerifier.Broken.class, result);
            assertEquals("STORED_HASH_MISMATCH", ((ChainVerifier.Broken) result).reasonCode());
        }
    }

    @Test void restartResumesWithoutDuplicateVerdicts() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'restart-test', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        UUID auditEventId;
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500);
            var event = events.stream().filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            auditEventId = event.auditEventId();
            sql.recordVerdictAtomically(auditEventId, "test-rule-v1", VerificationVerdict.NEEDS_BASELINE, "first run", null, null);
            sql.recordVerdictAtomically(auditEventId, "test-rule-v1", VerificationVerdict.NEEDS_BASELINE, "second run (simulated restart)", null, null);
        }
        try (AuditSql sql = new AuditSql(config)) {
            assertTrue(sql.hasVerificationResult(auditEventId, "test-rule-v1"));
            long count = sql.queryLong("select count(*) from stir_audit.verification_result where audit_event_id = ?::uuid and verifier_rule_version = 'test-rule-v1'", auditEventId.toString());
            assertEquals(1, count, "ON CONFLICT DO UPDATE must never produce a second row for the same (audit_event_id, verifier_rule_version)");
        }
    }

    /** The auditor's own read access is deliberately cross-tenant (GOVERNED_STATE_AUDIT_ARCHITECTURE.md);
     * what this proves instead is that a mutation in tenant A never creates or advances any stream
     * belonging to tenant B - isolation of the DATA, not of the auditor's own read scope. */
    @Test void tenantAMutationNeverTouchesTenantBStream() throws Exception {
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenantA, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenantA + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'tenant-a-only', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        try (AuditSql sql = new AuditSql(config)) {
            long headForB = sql.queryLong("select count(*) from stir_audit.stream_head where tenant_id = ?::uuid", tenantB.toString());
            assertEquals(0, headForB, "tenant B must have no stream_head row from tenant A's own mutation");
            long eventsForA = sql.queryLong("select count(*) from stir_audit.mutation_event where tenant_id = ?::uuid", tenantA.toString());
            assertEquals(1, eventsForA);
        }
    }

    @Test void rolledBackMutationLeavesNoEventAndNoHeadAdvance() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (AuditSql sql = new AuditSql(config)) {
            long before = sql.queryLong("select count(*) from stir_audit.mutation_event where tenant_id = ?::uuid", tenant.toString());
            try (Connection c = admin()) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
                    st.execute("set role idax_app");
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'rolled-back', now())");
                }
                c.rollback();
            }
            long after = sql.queryLong("select count(*) from stir_audit.mutation_event where tenant_id = ?::uuid", tenant.toString());
            assertEquals(before, after, "a rolled-back governed mutation must leave zero audit events - the trigger's own INSERT rolls back with it");
            long headRows = sql.queryLong("select count(*) from stir_audit.stream_head where tenant_id = ?::uuid", tenant.toString());
            assertEquals(0, headRows, "no stream_head row should exist for a tenant/domain whose only mutation was rolled back");
        }
    }

    @Test void multirowInsertProducesOneEventPerRowInSequence() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                var sb = new StringBuilder("insert into stir.market_integrity_case values ");
                UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
                for (int i = 0; i < ids.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append("('").append(ids[i]).append("','").append(tenant).append("','").append(refs[0]).append("','").append(refs[1])
                        .append("','CODE','reason','[]','").append(UUID.randomUUID()).append("',now())");
                }
                st.execute(sb.toString());
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var events = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case".equals(e.tableName()) && tenant.equals(e.tenantId())).toList();
            assertEquals(3, events.size(), "a 3-row multirow INSERT must produce exactly 3 mutation_event rows, one per row");
            var sequences = events.stream().map(MutationEvent::sequence).sorted().toList();
            assertEquals(java.util.List.of(1L, 2L, 3L), sequences, "sequence must be contiguous 1,2,3 for this fresh CONSTITUTION-free INTEGRITY stream");
        }
    }

    @Test void sessionReplicationRoleCannotBeSetByRuntimeRoles() throws Exception {
        try (Connection c = admin()) {
            for (String role : new String[]{"idax_app", "idax_admin", "idax_backend"}) {
                try (Statement st = c.createStatement()) { st.execute("set role " + role); }
                try (Statement st = c.createStatement()) {
                    assertThrows(SQLException.class, () -> st.execute("set session_replication_role = replica"), role + " must not be able to disable trigger firing via session_replication_role");
                }
                try (Statement st = c.createStatement()) { st.execute("reset role"); }
            }
        }
    }

    @Test void coverageRegistryClassifiesEveryStirTable() throws Exception {
        try (AuditSql sql = new AuditSql(config)) {
            long unclassified = sql.queryLong("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'stir' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
                  and c.oid not in (select table_oid from stir_audit.coverage_registry)
                """);
            assertEquals(0, unclassified, "every real stir.* table must have a coverage_registry row - COVERED, EXCLUDED_JUSTIFIED, or NO_PRIVILEGE_CATALOG");
        }
    }

    // ===================================================================================
    // P1-RA-001..007 remediation regression tests, per REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md
    // and CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md's remediation order. Each is FOUND -> FIXED ->
    // REVALIDATED BY CLAUDE here; Codex's own independent reaudit is what upgrades this to
    // "independently revalidated", never this file.
    // ===================================================================================

    /** P1-RA-001: fetchUnverifiedEvents is a durable anti-join, not a time/UUID watermark - a
     * transaction that commits AFTER an unrelated, later-arriving stream has already been polled
     * and verified must still be picked up on the very next poll, regardless of commit order. */
    @Test void lateCommittingTransactionAcrossDifferentStreamIsPickedUpOnNextPoll() throws Exception {
        UUID tenantEarly = UUID.randomUUID(), tenantLate = UUID.randomUUID();
        Connection lateConn = admin();
        try {
            lateConn.setAutoCommit(false);
            try (Statement st = lateConn.createStatement()) {
                st.execute("select set_config('app.tenant_id', '" + tenantLate + "', false)");
                st.execute("set role idax_app");
                st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                    "values (gen_random_uuid(), '" + tenantLate + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'late-commit', now())");
            }
            // lateConn's transaction is open and uncommitted here - its event does not exist yet
            // from any other session's point of view.

            try (Connection c = admin()) {
                AuditTestSupport.asIdaxApp(c, tenantEarly, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                            "values (gen_random_uuid(), '" + tenantEarly + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'early-commit', now())");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
            }

            try (AuditSql sql = new AuditSql(config)) {
                var firstPoll = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500);
                assertTrue(firstPoll.stream().anyMatch(e -> tenantEarly.equals(e.tenantId())), "the early, already-committed event must be visible");
                assertTrue(firstPoll.stream().noneMatch(e -> tenantLate.equals(e.tenantId())), "the late transaction has not committed yet - must not be visible");
                for (var e : firstPoll) {
                    if (tenantEarly.equals(e.tenantId())) {
                        sql.recordVerdictAtomically(e.auditEventId(), DomainRuleEngine.RULE_VERSION, VerificationVerdict.PASS_STRUCTURE_ONLY, "test", null, null);
                    }
                }
            }

            lateConn.commit();
            lateConn.setAutoCommit(true);
        } finally {
            lateConn.close();
        }

        try (AuditSql sql = new AuditSql(config)) {
            var secondPoll = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500);
            assertTrue(secondPoll.stream().anyMatch(e -> tenantLate.equals(e.tenantId())),
                "P1-RA-001: a transaction that commits AFTER an unrelated, already-verified later stream must still be picked up on the very next poll - anti-join, not a watermark");
        }
    }

    /** Same-stream concurrency: two genuinely concurrent writers to the SAME (tenant,domain) must
     * still be strictly serialized by the trigger's own stream_head row lock into contiguous
     * sequence numbers - no gap, no duplicate, regardless of which transaction "wins" the race.
     * This is also why a same-stream analogue of the late-commit test above is structurally
     * impossible: the second writer cannot even begin assigning a sequence until the first commits. */
    @Test void concurrentInsertsToSameStreamAreStrictlySerializedWithNoGapOrDuplicateSequence() throws Exception {
        UUID tenant = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                final String digest = "concurrent-same-stream-" + i;
                futures.add(executor.submit(() -> {
                    try (Connection c = admin()) {
                        AuditTestSupport.asIdaxApp(c, tenant, () -> {
                            try (Statement st = c.createStatement()) {
                                st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                                    "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', '" + digest + "', now())");
                            } catch (SQLException e) { throw new RuntimeException(e); }
                        });
                    } catch (SQLException e) { throw new RuntimeException(e); }
                }));
            }
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
        try (AuditSql sql = new AuditSql(config)) {
            var sequences = sql.queryStringList("select sequence::text from stir_audit.mutation_event where tenant_id = ?::uuid order by sequence", tenant.toString());
            assertEquals(List.of("1", "2"), sequences, "two genuinely concurrent transactions to the SAME stream must still serialize into contiguous sequence 1,2");
        }
    }

    /** Verifier-running-concurrently-with-writers: polls the SAME way VerifierLoop's real
     * incremental pass does while writer transactions are still landing, proving no interleaving of
     * a poll with an in-flight writer can ever miss or double-count a committed event. */
    @Test void verifierPassRunningConcurrentlyWithWritersMissesNothing() throws Exception {
        UUID tenant = UUID.randomUUID();
        int totalWrites = 10;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        var seenAuditEventIds = java.util.Collections.synchronizedSet(new HashSet<UUID>());
        try {
            var writeFutures = new ArrayList<Future<?>>();
            for (int i = 0; i < totalWrites; i++) {
                final String digest = "writer-" + i;
                writeFutures.add(executor.submit(() -> {
                    try (Connection c = admin()) {
                        AuditTestSupport.asIdaxApp(c, tenant, () -> {
                            try (Statement st = c.createStatement()) {
                                st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                                    "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', '" + digest + "', now())");
                            } catch (SQLException e) { throw new RuntimeException(e); }
                        });
                    } catch (SQLException e) { throw new RuntimeException(e); }
                }));
            }
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && !writeFutures.stream().allMatch(Future::isDone)) {
                try (AuditSql sql = new AuditSql(config)) {
                    for (var e : sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500)) {
                        if (tenant.equals(e.tenantId()) && seenAuditEventIds.add(e.auditEventId())) {
                            sql.recordVerdictAtomically(e.auditEventId(), DomainRuleEngine.RULE_VERSION, VerificationVerdict.PASS_STRUCTURE_ONLY, "concurrent poll", null, null);
                        }
                    }
                }
            }
            for (var f : writeFutures) f.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
        try (AuditSql sql = new AuditSql(config)) {
            for (var e : sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500)) {
                if (tenant.equals(e.tenantId()) && seenAuditEventIds.add(e.auditEventId())) {
                    sql.recordVerdictAtomically(e.auditEventId(), DomainRuleEngine.RULE_VERSION, VerificationVerdict.PASS_STRUCTURE_ONLY, "final sweep", null, null);
                }
            }
            long totalEvents = sql.queryLong("select count(*) from stir_audit.mutation_event where tenant_id = ?::uuid", tenant.toString());
            assertEquals(totalWrites, totalEvents, "all writer transactions must have committed exactly one event each");
            assertEquals(totalWrites, seenAuditEventIds.size(), "every committed event must be verified exactly once across all concurrent polls plus the final sweep");
        }
    }

    /** P1-RA-002: a failure partway through recordVerdictAtomically (here: a malformed evidence
     * JSON payload that only fails on the SECOND statement inside the transaction, after the verdict
     * upsert already ran) must leave NEITHER the verdict NOR the incident committed - proving the
     * transaction, not any individual statement, is the unit of crash-safety. */
    @Test void failureMidTransactionLeavesNoPartialVerdictOrIncident() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'crash-mid-tx', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        try (AuditSql sql = new AuditSql(config)) {
            var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var malformedIncident = new AuditSql.IncidentToRecord(tenant, event.domain(), event.auditEventId(), "TEST_CRASH", "{not valid json", "crash-mid-tx-" + event.auditEventId());
            assertThrows(SQLException.class, () -> sql.recordVerdictAtomically(
                event.auditEventId(), "crash-mid-tx-rule-v1", VerificationVerdict.VIOLATION, "would-be reason", malformedIncident, null));
            assertFalse(sql.hasVerificationResult(event.auditEventId(), "crash-mid-tx-rule-v1"),
                "a failed transaction must leave NO verification_result row, even though the verdict upsert executed successfully before the failing statement");
            long incidentCount = sql.queryLong("select count(*) from stir_audit.security_incident where audit_event_id = ?::uuid", event.auditEventId().toString());
            assertEquals(0, incidentCount, "a failed transaction must leave NO security_incident row");
        }
    }

    /** P1-RA-002: reprocessing the same event after a simulated crash-and-restart (same
     * audit_event_id, same dedup_key, recorded twice) must never produce a second incident row. */
    @Test void reprocessingAfterSimulatedCrashProducesNoDuplicateIncident() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 999999, '{}', 'forged-dup-test', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        try (AuditSql sql = new AuditSql(config)) {
            var forged = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var incident = new AuditSql.IncidentToRecord(tenant, forged.domain(), forged.auditEventId(), "TEST_DUP", "{\"x\":1}", "dup-crash-test-" + forged.auditEventId());
            sql.recordVerdictAtomically(forged.auditEventId(), "dup-crash-rule-v1", VerificationVerdict.VIOLATION, "first pass (pre-crash)", incident, null);
            sql.recordVerdictAtomically(forged.auditEventId(), "dup-crash-rule-v1", VerificationVerdict.VIOLATION, "second pass (post-restart, reprocessed)", incident, null);
            long incidentCount = sql.queryLong("select count(*) from stir_audit.security_incident where dedup_key = ?", incident.dedupKey());
            assertEquals(1, incidentCount, "the same dedup_key must never produce a second security_incident row, even when the same event is reprocessed after a restart");
        }
    }

    /** P1-RA-004 negative matrix, beyond the rewritten IntegrityRule's own primary case: a gap in
     * a case's event sequence numbering is a violation distinct from an invalid transition. (An
     * actual DUPLICATE sequence for the same case is impossible to even insert - V8's own
     * case_event_order_unique UNIQUE(tenant_id,case_id,sequence) constraint rejects it at the DB
     * layer before the audit trigger or this rule ever sees it; SEQUENCE_GAP_OR_DUPLICATE's "or
     * duplicate" half is therefore defense-in-depth against a hypothetical future schema change,
     * not a reachable Phase 1 attack shape - this test exercises the reachable half, the gap.) */
    @Test void sequenceGapInCaseHistoryIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','signal reason','" + originator + "',now(),1)");
                // sequence jumps 1 -> 3, skipping 2 - a gap, not a duplicate, and the only shape
                // this constraint actually allows to reach the rule.
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','review reason','" + decisor + "',now(),3)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("SEQUENCE_GAP_OR_DUPLICATE", verdict.reasonCode());
        }
    }

    @Test void firstEventIsUnderReviewWithoutPriorSignalIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','review reason','" + decisor + "',now(),1)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(event, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("FIRST_EVENT_NOT_SIGNAL", verdict.reasonCode());
        }
    }

    @Test void eventAfterTerminalStateIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','r','" + decisor + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','f','" + decisor + "',now(),3)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','reopened-after-final','" + decisor + "',now(),4)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("EVENT_AFTER_TERMINAL_STATE", verdict.reasonCode());
        }
    }

    /** P1-RA-004's EXACT original reproduction from REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md:
     * SIGNAL at sequence=1 followed DIRECTLY by FINAL at sequence=2, skipping UNDER_REVIEW entirely,
     * with a decisor different from the originator (so DECISOR_EQUALS_ORIGINATOR cannot be what
     * catches it) - Codex's own repro got PASS_STRUCTURE_ONLY here because the old rule only checked
     * "was the first prior status SIGNAL", never the actual transition. */
    @Test void signalDirectlyToFinalSkippingUnderReviewIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','f','" + decisor + "',now(),2)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("INVALID_TRANSITION", verdict.reasonCode());
        }
    }

    @Test void decisorEqualsOriginatorOnFinalIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','r','" + originator + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','f','" + originator + "',now(),3)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("DECISOR_EQUALS_ORIGINATOR", verdict.reasonCode());
        }
    }

    /** P1-RA-005: every event written since V19 uses the v2 hash, which additionally commits
     * audit_event_id/db_time/txid/session_role_reported/application_name/client_addr/request_id/
     * correlation_id/event_format_version. Tampering ANY one of these fields alone (never touching
     * current_hash itself) must independently break recomputation - proving the v2 hash actually
     * covers each field, not just the ones the v1 hash already covered. */
    @Test void hashV2DetectsTamperInEachIndependentlyCommittedField() throws Exception {
        record FieldTamper(String label, String updateSqlTemplate) {}
        var tampers = List.of(
            new FieldTamper("session_role_reported", "update stir_audit.mutation_event set session_role_reported = 'tampered_role' where audit_event_id = '%s'"),
            new FieldTamper("application_name", "update stir_audit.mutation_event set application_name = 'tampered_app' where audit_event_id = '%s'"),
            new FieldTamper("request_id", "update stir_audit.mutation_event set request_id = 'tampered-request-id' where audit_event_id = '%s'"),
            new FieldTamper("correlation_id", "update stir_audit.mutation_event set correlation_id = 'tampered-correlation-id' where audit_event_id = '%s'"),
            new FieldTamper("db_time", "update stir_audit.mutation_event set db_time = db_time + interval '1 hour' where audit_event_id = '%s'"),
            new FieldTamper("event_format_version", "update stir_audit.mutation_event set event_format_version = 'STIR_AUDIT_EVENT_V1' where audit_event_id = '%s'")
        );
        for (FieldTamper tamper : tampers) {
            UUID tenant = UUID.randomUUID();
            try (Connection c = admin()) {
                AuditTestSupport.asIdaxApp(c, tenant, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                            "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'hash-v2-field-" + tamper.label() + "', now())");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
            }
            UUID auditEventId;
            try (AuditSql sql = new AuditSql(config)) {
                var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                    .filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
                auditEventId = event.auditEventId();
                assertEquals("STIR_AUDIT_EVENT_V2", event.eventFormatVersion(), "V19-remediated writes must use the v2 hash format");
            }
            try (Connection c = admin(); Statement st = c.createStatement()) {
                st.execute(tamper.updateSqlTemplate().formatted(auditEventId));
            }
            try (AuditSql sql = new AuditSql(config)) {
                var tamperedEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                    .filter(e -> auditEventId.equals(e.auditEventId())).findFirst().orElseThrow();
                var result = new ChainVerifier(sql).verifyLink(tamperedEvent, null);
                assertInstanceOf(ChainVerifier.Broken.class, result, "tampering field '" + tamper.label() + "' alone must break V2 hash verification");
                assertEquals("STORED_HASH_MISMATCH", ((ChainVerifier.Broken) result).reasonCode(), "field '" + tamper.label() + "'");
            }
        }
    }

    /** Inserts a healthy 3-event market_constitution stream for `tenant`, runs a first clean
     * periodic reconciliation pass over it (genesis..3), and persists the checkpoint at 3 - the
     * common "checkpoint already advanced past everything" starting point every P1-RA-007 scenario
     * below tampers against. */
    private String seedThreeEventStreamAndAdvanceCheckpoint(UUID tenant, String digestPrefix) throws Exception {
        try (Connection c = admin()) {
            for (int i = 0; i < 3; i++) {
                final String digest = digestPrefix + i;
                AuditTestSupport.asIdaxApp(c, tenant, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                            "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', '" + digest + "', now())");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
            }
        }
        String domain;
        try (AuditSql sql = new AuditSql(config)) {
            var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            domain = event.domain();
            var firstOutcome = new ChainVerifier(sql).reconcileStreamFrom(tenant, domain, 1);
            assertInstanceOf(ChainVerifier.Ok.class, firstOutcome.result());
            assertEquals(3, firstOutcome.verifiedThroughSequence());
            sql.recordChainIncidentAtomically(null, tenant, domain, firstOutcome.verifiedThroughSequence(), firstOutcome.verifiedHead());
        }
        return domain;
    }

    /** P1-RA-007, "editing a hash": proves the fix above (full genesis walk, not resume-from-
     * checkpoint) actually catches a tamper to an INTERIOR event that the checkpoint has already
     * advanced well past - not just a tamper to the checkpoint's own boundary event or the live
     * head, which a naive resume-from-checkpoint design would still happen to catch by accident
     * (see VerifierLoop's own comment on why resuming from checkpoint was the wrong first draft of
     * this fix). */
    @Test void periodicReconciliationCatchesEditedHashOnInteriorEventAfterCheckpointAdvancedPastIt() throws Exception {
        UUID tenant = UUID.randomUUID();
        String domain = seedThreeEventStreamAndAdvanceCheckpoint(tenant, "reconcile-edit-hash-");

        // Tamper sequence=1 (the INTERIOR/oldest event, nowhere near the checkpoint=3 boundary or
        // the live head) - a superuser/owner-level historical rewrite, matching chainTamperIsDetected's
        // own out-of-scope-threat framing.
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("update stir_audit.mutation_event set current_hash = '\\xdeadbeef'::bytea where tenant_id = '" + tenant + "' and sequence = 1");
        }

        try (AuditSql sql = new AuditSql(config)) {
            var checkpoint = sql.readChainCheckpoint(tenant, domain);
            assertEquals(3, checkpoint.lastVerifiedSequence(), "checkpoint must already have advanced past sequence=1 before the tamper, to actually exercise the gap");
            // The next periodic pass, per the fixed VerifierLoop, always re-walks from sequence 1 -
            // never resumes from the checkpoint - so it must still catch this.
            var outcome = new ChainVerifier(sql).reconcileStreamFrom(tenant, domain, 1);
            assertInstanceOf(ChainVerifier.Broken.class, outcome.result(), "a tamper to an interior event must be caught by the next full-genesis periodic walk even though the checkpoint had already advanced past it");
            assertEquals("STORED_HASH_MISMATCH", ((ChainVerifier.Broken) outcome.result()).reasonCode(),
                "sequence=1 is the very first event walked (previous=null), so its own self-recomputation check fails before the walk ever reaches sequence=2's discontinuity check against it");
        }
    }

    /** P1-RA-007, "deleting an old link as owner": an interior event physically DELETEd (only the
     * table owner/superuser can - stir_auditor/idax_app have no DELETE grant, proven separately by
     * idaxAppAndIdaxAdminCannotTouchAuditJournalOrHead) after the checkpoint has already advanced
     * past it must still be caught by the next full-genesis periodic walk, as a gap. */
    @Test void periodicReconciliationCatchesDeletedInteriorLinkAfterCheckpointAdvancedPastIt() throws Exception {
        UUID tenant = UUID.randomUUID();
        String domain = seedThreeEventStreamAndAdvanceCheckpoint(tenant, "reconcile-delete-link-");

        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("delete from stir_audit.mutation_event where tenant_id = '" + tenant + "' and sequence = 2");
        }

        try (AuditSql sql = new AuditSql(config)) {
            var outcome = new ChainVerifier(sql).reconcileStreamFrom(tenant, domain, 1);
            assertInstanceOf(ChainVerifier.Broken.class, outcome.result(), "a deleted interior link must be caught by the next full-genesis periodic walk even though the checkpoint had already advanced past it");
            assertEquals("SEQUENCE_GAP", ((ChainVerifier.Broken) outcome.result()).reasonCode(),
                "with sequence=2 gone, the walk finds sequence=3 immediately after sequence=1 - a gap, not a hash mismatch");
        }
    }

    /** P1-RA-007's EXACT original reproduction: the FIRST (genesis) event of a stream physically
     * DELETEd, leaving only its later successor - REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md's own
     * repro deleted the earlier INSERT event of a participant_independence_projection INSERT+UPDATE
     * pair, leaving only the UPDATE event; this reproduces the same shape (delete the earliest event,
     * keep a later one) against market_constitution, and confirms CHAIN_% incidents no longer stay
     * silently 0 after the fix. */
    @Test void periodicReconciliationCatchesDeletedGenesisEventAfterCheckpointAdvancedPastIt() throws Exception {
        UUID tenant = UUID.randomUUID();
        String domain = seedThreeEventStreamAndAdvanceCheckpoint(tenant, "reconcile-delete-genesis-");

        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("delete from stir_audit.mutation_event where tenant_id = '" + tenant + "' and sequence = 1");
        }

        try (AuditSql sql = new AuditSql(config)) {
            var outcome = new ChainVerifier(sql).reconcileStreamFrom(tenant, domain, 1);
            assertInstanceOf(ChainVerifier.Broken.class, outcome.result(), "deleting the genesis event of a stream must be caught by the next full-genesis periodic walk even though the checkpoint had already advanced past it - reproducing Codex's exact P1-RA-007 finding shape");
            assertEquals("MISSING_PREDECESSOR", ((ChainVerifier.Broken) outcome.result()).reasonCode(),
                "with sequence=1 gone, the walk's first surviving event is sequence=2, which is not itself sequence=1, so it requires a predecessor that no longer exists");
        }
    }

    /** P1-RA-007, "incorrect head": stream_head tampered directly (bypassing the trigger, superuser-
     * only per the same DB-role proof above) so it no longer matches the last real event's own
     * current_hash - must be caught even though every individual mutation_event row is itself
     * internally self-consistent. */
    @Test void periodicReconciliationCatchesIncorrectStreamHeadAfterCheckpointAdvancedPastIt() throws Exception {
        UUID tenant = UUID.randomUUID();
        String domain = seedThreeEventStreamAndAdvanceCheckpoint(tenant, "reconcile-bad-head-");

        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("update stir_audit.stream_head set head_hash = '\\xdeadbeef'::bytea where tenant_id = '" + tenant + "' and domain = '" + domain + "'");
        }

        try (AuditSql sql = new AuditSql(config)) {
            var outcome = new ChainVerifier(sql).reconcileStreamFrom(tenant, domain, 1);
            assertInstanceOf(ChainVerifier.Broken.class, outcome.result(), "a stream_head tampered independently of its events must be caught even though every mutation_event row is itself internally consistent");
            assertEquals("STREAM_HEAD_MISMATCH", ((ChainVerifier.Broken) outcome.result()).reasonCode());
        }
    }

    /** Verifier kill/restart, against the real production entrypoint (VerifierLoop.runOneIncrementalPassForTest,
     * not a hand-rolled loop): a brand new VerifierLoop + AuditSql instance ("process instance 2",
     * simulating a restart after a crash right after instance 1's pass) re-running the identical
     * pass must not duplicate any verification_result or security_incident row. */
    @Test void verifierLoopRestartProcessesRemainingEventsExactlyOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            for (int i = 0; i < 3; i++) {
                final String digest = "restart-loop-" + i;
                AuditTestSupport.asIdaxApp(c, tenant, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                            "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', '" + digest + "', now())");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
            }
        }
        var health = new HealthServer(config);
        try (AuditSql sql = new AuditSql(config)) {
            new VerifierLoop(config, health).runOneIncrementalPassForTest(sql);
        }
        assertEquals(3, countVerifiedForTenant(tenant), "a single incremental pass must fully process every already-committed event for this stream");

        try (AuditSql sql = new AuditSql(config)) {
            new VerifierLoop(config, health).runOneIncrementalPassForTest(sql);
        }
        assertEquals(3, countVerifiedForTenant(tenant),
            "the restarted instance re-running the same pass over already-verified events must not create duplicate verification_result rows");
    }

    private long countVerifiedForTenant(UUID tenant) throws SQLException {
        try (AuditSql sql = new AuditSql(config)) {
            return sql.queryLong(
                "select count(*) from stir_audit.verification_result vr join stir_audit.mutation_event me on me.audit_event_id = vr.audit_event_id " +
                "where me.tenant_id = ?::uuid and vr.verifier_rule_version = ?", tenant.toString(), DomainRuleEngine.RULE_VERSION);
        }
    }

    /** P1-RA-003: a real two-phase upgrade - migrate only through V17 (before the audit trigger
     * exists), seed a real legacy fixture row directly (exactly how production's own pre-V18 data
     * looks), THEN migrate through V19. baseline_import must suppress any false CRITICAL for that
     * legitimately pre-audit row, while a row inserted AFTER V19 with the trigger deliberately
     * bypassed (session_replication_role='replica', a real Postgres superuser action - see
     * CONSENT_RETENTION.md's own precedent for this exact backdoor, used only for test fixtures)
     * still alerts as LIVE_ROW_WITH_NO_AUDIT_EVENT - proving baseline import is scoped to the
     * baseline moment only, never a blanket suppression. */
    @Test void baselineImportSuppressesLegacyRowsButNotGenuinelyOrphanedOnes() throws Exception {
        try (PostgreSQLContainer<?> freshPg = AuditTestSupport.newPostgres()) {
            freshPg.start();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("create role idax_app; create role idax_admin; create role idax_backend login password 'x' inherit; grant idax_app, idax_admin to idax_backend");
            }
            Flyway.configure().dataSource(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword())
                .schemas("stir").locations("filesystem:../src/main/resources/db/migration-stir").target("17").load().migrate();

            UUID legacyDefId = UUID.randomUUID(), legacyTenant = UUID.randomUUID(), legacyCommunity = UUID.randomUUID(), legacyUnit = UUID.randomUUID();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("insert into stir.reference_definition values ('" + legacyDefId + "','" + legacyTenant + "','" + legacyCommunity +
                    "','" + legacyUnit + "','legacy-name','scope','{}','1','unit','ref','" + UUID.randomUUID() + "',now())");
            }

            Flyway.configure().dataSource(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword())
                .schemas("stir").locations("filesystem:../src/main/resources/db/migration-stir").load().migrate();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("alter role stir_auditor with password '" + AuditTestSupport.STIR_AUDITOR_PASSWORD + "'");
            }
            Config freshConfig = new Config(freshPg.getJdbcUrl(), "stir_auditor", AuditTestSupport.STIR_AUDITOR_PASSWORD, 200L, 0, "test");

            try (AuditSql sql = new AuditSql(freshConfig)) {
                var findings = new Reconciler(sql).reconcileAll();
                assertTrue(findings.stream().noneMatch(f -> f.entityKeyCanonical() != null && f.entityKeyCanonical().contains(legacyDefId.toString())),
                    "a row imported at baseline time must never itself produce a reconciliation finding");
            }

            UUID orphanDefId = UUID.randomUUID();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
                st.execute("insert into stir.reference_definition values ('" + orphanDefId + "','" + legacyTenant + "','" + legacyCommunity +
                    "','" + legacyUnit + "','orphan-name','scope','{}','1','unit','ref','" + UUID.randomUUID() + "',now())");
                st.execute("set session_replication_role = default");
            }
            try (AuditSql sql = new AuditSql(freshConfig)) {
                var findings = new Reconciler(sql).reconcileAll();
                assertTrue(findings.stream().anyMatch(f -> "LIVE_ROW_WITH_NO_AUDIT_EVENT".equals(f.reasonCode())
                        && f.entityKeyCanonical() != null && f.entityKeyCanonical().contains(orphanDefId.toString())),
                    "a row inserted AFTER the baseline (trigger deliberately bypassed) with no matching mutation_event must still be flagged - baseline_import is scoped to the baseline moment only");
            }
        }
    }

    /** P1-R2-002, "V16 -> audit activation": pre-existing data from BEFORE the audit trigger existed
     * at all (not merely before V19, the original split design's boundary) must still classify as
     * LEGACY_UNVERIFIED with zero false CRITICAL, now that baseline capture lives inside V18 itself. */
    @Test void v16DataMigratesThroughActivationAsLegacyUnverifiedWithoutFalseCritical() throws Exception {
        try (PostgreSQLContainer<?> freshPg = AuditTestSupport.newPostgres()) {
            freshPg.start();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("create role idax_app; create role idax_admin; create role idax_backend login password 'x' inherit; grant idax_app, idax_admin to idax_backend");
            }
            Flyway.configure().dataSource(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword())
                .schemas("stir").locations("filesystem:../src/main/resources/db/migration-stir").target("16").load().migrate();

            UUID legacyDefId = UUID.randomUUID(), legacyTenant = UUID.randomUUID(), legacyCommunity = UUID.randomUUID(), legacyUnit = UUID.randomUUID();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                // No trigger exists at all at V16 - a plain INSERT as postgres is enough, no
                // session_replication_role trick needed (that trick is only for AFTER activation).
                st.execute("insert into stir.reference_definition values ('" + legacyDefId + "','" + legacyTenant + "','" + legacyCommunity +
                    "','" + legacyUnit + "','v16-legacy-name','scope','{}','1','unit','ref','" + UUID.randomUUID() + "',now())");
            }

            Flyway.configure().dataSource(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword())
                .schemas("stir").locations("filesystem:../src/main/resources/db/migration-stir").load().migrate();
            try (Connection c = DriverManager.getConnection(freshPg.getJdbcUrl(), freshPg.getUsername(), freshPg.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("alter role stir_auditor with password '" + AuditTestSupport.STIR_AUDITOR_PASSWORD + "'");
            }
            Config freshConfig = new Config(freshPg.getJdbcUrl(), "stir_auditor", AuditTestSupport.STIR_AUDITOR_PASSWORD, 200L, 0, "test");

            try (AuditSql sql = new AuditSql(freshConfig)) {
                assertTrue(sql.baselineImportedKeys(sql.fetchCoveredTables().stream()
                        .filter(t -> "reference_definition".equals(t.tableName())).findFirst().orElseThrow().tableOid())
                    .stream().anyMatch(k -> k.contains(legacyDefId.toString())), "the V16-era row must be captured as LEGACY_UNVERIFIED baseline");
                var findings = new Reconciler(sql).reconcileAll();
                assertTrue(findings.stream().noneMatch(f -> f.entityKeyCanonical() != null && f.entityKeyCanonical().contains(legacyDefId.toString())),
                    "V16 data migrated through activation must produce zero false CRITICAL");
            }
        }
    }

    /** P1-R2-001: the exact false-CRITICAL reproduction from SECOND_REVALIDATION_GOVERNED_STATE_
     * AUDIT_PHASE1.md - a fully legitimate SIGNAL(originator)->UNDER_REVIEW(decisor)->FINAL(decisor)
     * backlog, processed through the REAL production pipeline
     * (VerifierLoop.runOneIncrementalPassForTest, not a direct call to DomainRuleEngine.evaluate on
     * a single hand-picked event) must classify all three events PASS_STRUCTURE_ONLY with ZERO
     * incidents. The first remediation round's own regression test only ever evaluated the LAST
     * event in isolation, which is exactly why it never caught that the SIGNAL event itself used to
     * get a false VIOLATION/DECISOR_EQUALS_ORIGINATOR from the old rule's history/actor mismatch. */
    @Test void fullBacklogOfLegitimateSignalUnderReviewFinalProducesZeroIncidents() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','signal reason','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','review reason','" + decisor + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','final reason','" + decisor + "',now(),3)");
            }
        }
        var health = new HealthServer(config);
        try (AuditSql sql = new AuditSql(config)) {
            new VerifierLoop(config, health).runOneIncrementalPassForTest(sql);
        }
        try (AuditSql sql = new AuditSql(config)) {
            var results = sql.queryStringList(
                "select cev.status || ':' || vr.result from stir_audit.mutation_event me " +
                "join stir_audit.verification_result vr on vr.audit_event_id = me.audit_event_id " +
                "join stir.market_integrity_case_event cev on cev.id::text = me.entity_key ->> 'id' " +
                "where me.tenant_id = ?::uuid and me.table_name = 'market_integrity_case_event' order by cev.sequence",
                tenant.toString());
            assertEquals(List.of("SIGNAL:PASS_STRUCTURE_ONLY", "UNDER_REVIEW:PASS_STRUCTURE_ONLY", "FINAL:PASS_STRUCTURE_ONLY"), results,
                "processing the FULL backlog of a legitimate case must classify every event PASS_STRUCTURE_ONLY - the SIGNAL event specifically must never come back VIOLATION");
            long incidentCount = sql.queryLong("select count(*) from stir_audit.security_incident where tenant_id = ?::uuid", tenant.toString());
            assertEquals(0, incidentCount, "P1-R2-001: a fully legitimate backlog must produce ZERO incidents");
        }
    }

    /** P1-R2-001 negative matrix addition: "reorder" - UNDER_REVIEW displaced to AFTER a terminal
     * FINAL instead of before it (a minimal 3-event shape, distinct from
     * eventAfterTerminalStateIsViolation's 4-event one). */
    @Test void reorderedUnderReviewAfterFinalIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','f','" + decisor + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','reordered','" + decisor + "',now(),3)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("EVENT_AFTER_TERMINAL_STATE", verdict.reasonCode());
        }
    }

    @Test void secondTerminalStateAfterFirstIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','r','" + decisor + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','FINAL','f','" + decisor + "',now(),3)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','DISMISSED','second-terminal','" + decisor + "',now(),4)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("EVENT_AFTER_TERMINAL_STATE", verdict.reasonCode());
        }
    }

    @Test void eventAfterDismissedIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID(), decisor = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','r','" + decisor + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','DISMISSED','d','" + decisor + "',now(),3)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','reopened-after-dismissed','" + decisor + "',now(),4)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("EVENT_AFTER_TERMINAL_STATE", verdict.reasonCode());
        }
    }

    @Test void decisorEqualsOriginatorOnDismissedIsViolation() throws Exception {
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID();
        UUID caseId = UUID.randomUUID(), originator = UUID.randomUUID();
        try (Connection c = admin()) {
            UUID[] refs = insertReferenceChain(c, tenant, community);
            try (Statement st = c.createStatement()) {
                st.execute("insert into stir.market_integrity_case values ('" + caseId + "','" + tenant + "','" + refs[0] + "','" + refs[1] +
                    "','SIGNAL_CODE','reason','[]','" + originator + "',now())");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','SIGNAL','s','" + originator + "',now(),1)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','UNDER_REVIEW','r','" + originator + "',now(),2)");
                st.execute("insert into stir.market_integrity_case_event (id,tenant_id,case_id,status,reason,actor_id,recorded_at,sequence) values ('" +
                    UUID.randomUUID() + "','" + tenant + "','" + caseId + "','DISMISSED','d','" + originator + "',now(),3)");
            }
        }
        try (AuditSql sql = new AuditSql(config)) {
            var lastEvent = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).reduce((a, b) -> b).orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(lastEvent, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("DECISOR_EQUALS_ORIGINATOR", verdict.reasonCode());
        }
    }

    /** ChainVerifier race (item 3, second reaudit): manually replicates reconcileStreamFrom's own
     * two reads (head, then events) via the same exposed snapshot API, with a REAL writer commit to
     * the SAME stream injected strictly between them on a separate connection - proving the shared
     * REPEATABLE READ snapshot is not fooled, and that the deferred event reconciles cleanly next
     * pass with no false incident. */
    @Test void chainVerifierSnapshotIsNotRacedByWriterCommittingDuringSameStreamReconciliation() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'race-seed', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        String domain;
        try (AuditSql sql = new AuditSql(config)) {
            var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            domain = event.domain();
        }

        try (AuditSql readerSql = new AuditSql(config)) {
            readerSql.beginRepeatableReadSnapshot();
            var headBefore = readerSql.currentStreamHead(tenant, domain);
            assertEquals(1L, (long) headBefore.getKey(), "snapshot's first read must see exactly the one seeded event");

            try (Connection c = admin()) {
                AuditTestSupport.asIdaxApp(c, tenant, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                            "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'race-during-gap', now())");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
            }

            var eventsInSnapshot = readerSql.fetchStreamFrom(tenant, domain, 1);
            readerSql.endRepeatableReadSnapshot();
            assertEquals(1, eventsInSnapshot.size(),
                "the events-read, sharing the SAME snapshot as the head-read, must NOT see a writer commit that landed strictly between them");
        }

        try (AuditSql sql = new AuditSql(config)) {
            var outcome = new ChainVerifier(sql).reconcileStreamFrom(tenant, domain, 1);
            assertInstanceOf(ChainVerifier.Ok.class, outcome.result(), "the deferred event must reconcile cleanly on the very next pass, not raise a false incident from the earlier race window");
            assertEquals(2L, outcome.verifiedThroughSequence());
        }
    }

    /** Same race proof, repeated cross-tenant ("otro tenant" per the reaudit order): a writer commit
     * to a DIFFERENT tenant's stream during the reader's open snapshot must not affect the tenant
     * actually being reconciled, and both streams must reconcile cleanly afterward. */
    @Test void chainVerifierSnapshotIsNotRacedByWriterCommittingToDifferentTenantDuringReconciliation() throws Exception {
        UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenantA, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenantA + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'race-seed-a', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        String domain;
        try (AuditSql sql = new AuditSql(config)) {
            var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> tenantA.equals(e.tenantId())).findFirst().orElseThrow();
            domain = event.domain();
        }

        try (AuditSql readerSql = new AuditSql(config)) {
            readerSql.beginRepeatableReadSnapshot();
            var headBefore = readerSql.currentStreamHead(tenantA, domain);
            assertEquals(1L, (long) headBefore.getKey());

            try (Connection c = admin()) {
                AuditTestSupport.asIdaxApp(c, tenantB, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                            "values (gen_random_uuid(), '" + tenantB + "', gen_random_uuid(), gen_random_uuid(), 1, '{}', 'race-seed-b', now())");
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
            }

            var eventsForA = readerSql.fetchStreamFrom(tenantA, domain, 1);
            readerSql.endRepeatableReadSnapshot();
            assertEquals(1, eventsForA.size(), "tenant A's stream must be entirely unaffected by tenant B's concurrent commit to a different stream");
        }

        try (AuditSql sql = new AuditSql(config)) {
            var chain = new ChainVerifier(sql);
            assertInstanceOf(ChainVerifier.Ok.class, chain.reconcileStreamFrom(tenantA, domain, 1).result());
            assertInstanceOf(ChainVerifier.Ok.class, chain.reconcileStreamFrom(tenantB, domain, 1).result());
        }
    }

    /** Item 4 (second reaudit) + P1-R3-001 (third reaudit) framing: a persistent, structural
     * incident (Reconciler-discovered, rediscovered fresh every single cycle since it is a standing
     * condition, not a one-time event) keeps the BEST_EFFORT_CRITICAL_LOG line quiet on rediscovery
     * across many cycles - a diagnostic convenience, never the proof the incident exists. The
     * durable, canonical signal is `security_incident` itself, verified here via
     * `HealthServer.querySecurityStatus` (the same DB-backed query `/security-status` uses),
     * queried completely independently of whatever this process's log happened to print. */
    @Test void persistentIncidentStaysQuietInBestEffortLogButRemainsDurablyCriticalInSecurityStatus() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID orphanId = UUID.randomUUID();
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute("insert into stir.reference_definition values ('" + orphanId + "','" + tenant + "','" + UUID.randomUUID() +
                "','" + UUID.randomUUID() + "','orphan-name','scope','{}','1','unit','ref','" + UUID.randomUUID() + "',now())");
            st.execute("set session_replication_role = default");
        }
        var health = new HealthServer(config);
        var loop = new VerifierLoop(config, health);
        var criticalLinesForThisIncident = new ArrayList<String>();
        java.io.PrintStream original = System.out;
        try {
            var captured = new java.io.ByteArrayOutputStream();
            System.setOut(new java.io.PrintStream(captured));
            for (int cycle = 0; cycle < 4; cycle++) {
                try (AuditSql sql = new AuditSql(config)) {
                    loop.runOneIncrementalPassForTest(sql);
                }
            }
            System.out.flush();
            for (String line : captured.toString().split("\\R")) {
                if (line.contains("CRITICAL") && line.contains(orphanId.toString())) criticalLinesForThisIncident.add(line);
            }
        } finally {
            System.setOut(original);
        }
        assertEquals(1, criticalLinesForThisIncident.size(),
            "the best-effort log should stay quiet on rediscovery of the same persistent condition (diagnostic convenience only, not a durability guarantee): " + criticalLinesForThisIncident);
        var status = HealthServer.querySecurityStatus(config);
        assertEquals("CRITICAL_SECURITY_INCIDENT", status.securityState());
        assertTrue(status.openIncidentCount() >= 1, "the durable, DB-backed security status must keep reporting the incident as open regardless of the best-effort log");
    }

    /** P1-RA-002 "after commit" fault-injection boundary (item 5, second reaudit): the OTHER half of
     * the atomicity guarantee failureMidTransactionLeavesNoPartialVerdictOrIncident already proves -
     * once recordVerdictAtomically's transaction genuinely commits, BOTH the verdict AND the
     * incident must be present together, never one without the other. */
    @Test void successfulTransactionLeavesBothVerdictAndIncidentPersistedTogether() throws Exception {
        UUID tenant = UUID.randomUUID();
        try (Connection c = admin()) {
            AuditTestSupport.asIdaxApp(c, tenant, () -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into stir.market_constitution (id, tenant_id, community_id, authority_id, version, canonical_json, digest_sha256, activated_at) " +
                        "values (gen_random_uuid(), '" + tenant + "', gen_random_uuid(), gen_random_uuid(), 999999, '{}', 'after-commit-test', now())");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }
        try (AuditSql sql = new AuditSql(config)) {
            var event = sql.fetchUnverifiedEvents(DomainRuleEngine.RULE_VERSION, 500).stream()
                .filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var incident = new AuditSql.IncidentToRecord(tenant, event.domain(), event.auditEventId(), "TEST_AFTER_COMMIT", "{\"x\":1}", "after-commit-" + event.auditEventId());
            boolean isNew = sql.recordVerdictAtomically(event.auditEventId(), "after-commit-rule-v1", VerificationVerdict.VIOLATION, "test", incident, null);
            assertTrue(isNew, "a genuinely new incident must report isNew=true");
            assertTrue(sql.hasVerificationResult(event.auditEventId(), "after-commit-rule-v1"), "after a successful commit, the verdict must be present");
            long incidentCount = sql.queryLong("select count(*) from stir_audit.security_incident where audit_event_id = ?::uuid", event.auditEventId().toString());
            assertEquals(1, incidentCount, "after a successful commit, the incident must be present too - both, never one without the other");
        }
    }
}
