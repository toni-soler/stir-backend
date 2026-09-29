package org.stir.audit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** P1-RA-006 (REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md): the coverage-completeness/fail-closed
 * gate must live in stir-backend's OWN Maven reactor, not only in the separate audit-verifier
 * project (audit-verifier deliberately has its own pom.xml, outside stir-backend's reactor - see
 * its own module comment - so a plain `mvn verify` run from stir-backend alone would never execute
 * audit-verifier's tests at all). This class is the twin, in this reactor, of audit-verifier's
 * VerifierDetectionTest.coverageRegistryClassifiesEveryStirTable, plus the two checks that test did
 * not have: idax_app's UPDATE/DELETE privileges (not just INSERT) are checked against every
 * non-COVERED classification, and a synthetic never-classified table is proven to actually trip the
 * gate, not just proven absent from a schema that happens to already be fully classified. */
@Testcontainers
class StirAuditCoveragePostgresTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private static boolean migrated = false;

    private JdbcTemplate migrate() throws Exception {
        if (!migrated) {
            try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("create role idax_app; create role idax_admin; create role idax_backend login password 'x' inherit; grant idax_app, idax_admin to idax_backend");
            }
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("stir").locations("classpath:db/migration-stir").load().migrate();
            migrated = true;
        }
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        c.setAutoCommit(true);
        return new JdbcTemplate(new SingleConnectionDataSource(c, true));
    }

    /** Same completeness check as audit-verifier's own test, reproduced here so a plain `mvn verify`
     * in THIS reactor alone (no audit-verifier project involved at all) still runs it. */
    @Test void everyRealStirTableHasACoverageRegistryRow() throws Exception {
        var db = migrate();
        Long unclassified = db.queryForObject("""
            select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'stir' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
              and c.oid not in (select table_oid from stir_audit.coverage_registry)
            """, Long.class);
        assertEquals(0L, unclassified, "every real stir.* table must have a coverage_registry row - COVERED, EXCLUDED_JUSTIFIED, or NO_PRIVILEGE_CATALOG");
    }

    /** P1-RA-006's actual defect: V18's classification-time check only looked at idax_app's INSERT
     * privilege. A NO_PRIVILEGE_CATALOG table with UPDATE or DELETE (but no INSERT) granted to
     * idax_app would have been wrongly treated as "no runtime role can ever mutate them" while
     * idax_app could silently mutate existing rows with zero audit coverage. This test checks all
     * three verbs, on every live run, not just once at migration time. */
    @Test void noPrivilegeCatalogTablesHaveNoInsertUpdateOrDeleteGrantedToIdaxApp() throws Exception {
        var db = migrate();
        List<String> catalogTables = db.queryForList(
            "select table_name from stir_audit.coverage_registry where coverage_status = 'NO_PRIVILEGE_CATALOG'", String.class);
        assertFalse(catalogTables.isEmpty(), "this schema must have at least one real NO_PRIVILEGE_CATALOG table for this check to be meaningful, not vacuous");
        for (String table : catalogTables) {
            String qualified = "stir." + table;
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
                Boolean has = db.queryForObject("select has_table_privilege('idax_app', ?, ?)", Boolean.class, qualified, privilege);
                assertFalse(Boolean.TRUE.equals(has), qualified + ": classified NO_PRIVILEGE_CATALOG but idax_app has " + privilege);
            }
        }
    }

    /** P1-RA-006's negative test: a synthetic table nobody ever classified, WITH a real runtime
     * INSERT/UPDATE/DELETE grant to idax_app (so it is a genuine, exploitable governance gap if
     * missed, not a harmless lookup table like category/resource_kind), must actually trip the same
     * "unclassified" query the completeness test above relies on - proving the gate fires on a real
     * unclassified table, not only passing because this schema happens to already be complete. */
    @Test void syntheticUngovernedTableWithRuntimeGrantsTripsTheCoverageGate() throws Exception {
        var db = migrate();
        db.execute("create table stir.synthetic_ungoverned_test_table (id uuid primary key default gen_random_uuid(), tenant_id uuid not null)");
        try {
            db.execute("grant insert, update, delete on stir.synthetic_ungoverned_test_table to idax_app");
            Long unclassified = db.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'stir' and c.relname = 'synthetic_ungoverned_test_table'
                  and c.oid not in (select table_oid from stir_audit.coverage_registry)
                """, Long.class);
            assertEquals(1L, unclassified, "a real table with live idax_app DML grants and no coverage_registry row must trip the fail-closed gate");
        } finally {
            db.execute("drop table stir.synthetic_ungoverned_test_table");
        }
    }

    /** Item 6 (SECOND_REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md): Codex's own reproduction created
     * three temporary tables, `stir.audit_probe_{insert,update,delete}`, each with ONLY the one
     * named grant and no classification, confirming the coverage query caught all three variants
     * independently - not just a table with all three grants combined at once (which the existing
     * `syntheticUngovernedTableWithRuntimeGrantsTripsTheCoverageGate` above already proves via a
     * different mechanism). Reproduced here verb-by-verb for direct traceability against that exact
     * finding. */
    @Test void ungovernedTableWithOnlyOneOfInsertUpdateOrDeleteEachTripsTheGateIndependently() throws Exception {
        var db = migrate();
        for (String verb : List.of("insert", "update", "delete")) {
            String tableName = "audit_probe_" + verb;
            db.execute("create table stir." + tableName + " (id uuid primary key default gen_random_uuid(), tenant_id uuid not null)");
            try {
                db.execute("grant " + verb + " on stir." + tableName + " to idax_app");
                Long unclassified = db.queryForObject("""
                    select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'stir' and c.relname = ? and c.oid not in (select table_oid from stir_audit.coverage_registry)
                    """, Long.class, tableName);
                assertEquals(1L, unclassified, "stir." + tableName + " (only " + verb.toUpperCase() + " granted, no registry row) must trip the fail-closed gate on its own");
            } finally {
                db.execute("drop table stir." + tableName);
            }
        }
    }
}
