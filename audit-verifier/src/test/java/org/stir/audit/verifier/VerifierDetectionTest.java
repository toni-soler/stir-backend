package org.stir.audit.verifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
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
            var events = sql.fetchEventsAfter(null, null, 500);
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
            var events = sql.fetchEventsAfter(null, null, 500);
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
            var events = sql.fetchEventsAfter(null, null, 200);
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
            var events = sql.fetchEventsAfter(null, null, 200);
            var forged = events.stream().filter(e -> "market_integrity_case_event".equals(e.tableName()) && tenant.equals(e.tenantId())).findFirst().orElseThrow();
            var verdict = new DomainRuleEngine().evaluate(forged, sql);
            assertEquals(VerificationVerdict.VIOLATION, verdict.verdict());
            assertEquals("FINAL_WITHOUT_PRIOR_HISTORY", verdict.reasonCode());
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
            var events = sql.fetchEventsAfter(null, null, 500);
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
            var events = sql.fetchEventsAfter(null, null, 500);
            var event = events.stream().filter(e -> tenant.equals(e.tenantId())).findFirst().orElseThrow();
            auditEventId = event.auditEventId();
            sql.upsertVerificationResult(auditEventId, "test-rule-v1", VerificationVerdict.NEEDS_BASELINE, "first run");
            sql.upsertVerificationResult(auditEventId, "test-rule-v1", VerificationVerdict.NEEDS_BASELINE, "second run (simulated restart)");
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
            var events = sql.fetchEventsAfter(null, null, 500).stream()
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
}
