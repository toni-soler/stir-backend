package org.stir.audit.verifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/** Applies the real V1-V18 migrations from stir-backend's own src/main/resources (a relative path
 * to the sibling module, not a copy) against a fresh Testcontainers Postgres, creates the same
 * bare idax_app/idax_admin/idax_backend roles every stir-backend PostgresTest already creates, and
 * sets stir_auditor's password the same way provision-audit.sh does in the real deploy (ALTER ROLE
 * after migration, never inline in the migration itself). */
final class AuditTestSupport {
    static final String STIR_AUDITOR_PASSWORD = "test-only-auditor-password";

    static PostgreSQLContainer<?> newPostgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }

    static void migrate(PostgreSQLContainer<?> postgres) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute("create role idax_app; create role idax_admin; create role idax_backend login password 'x' inherit; grant idax_app, idax_admin to idax_backend");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stir").locations("filesystem:../src/main/resources/db/migration-stir").load().migrate();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute("alter role stir_auditor with password '" + STIR_AUDITOR_PASSWORD + "'");
        }
    }

    static Config auditorConfig(PostgreSQLContainer<?> postgres) {
        return new Config(postgres.getJdbcUrl(), "stir_auditor", STIR_AUDITOR_PASSWORD, 200L, 0, "test");
    }

    /** A privileged connection for building fixtures and for the "idax_app forged this directly"
     * attack simulations - same idax_backend + SET ROLE idax_app pattern the real AUD-012
     * reproduction used, and the same pattern StirAdminAuthorityPostgresTest already uses. */
    static Connection privilegedConnection(PostgreSQLContainer<?> postgres) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        c.setAutoCommit(true);
        return c;
    }

    static void asIdaxApp(Connection c, UUID tenant, Runnable body) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
            st.execute("set role idax_app");
        }
        try { body.run(); }
        finally { try (Statement st = c.createStatement()) { st.execute("reset role"); } }
    }
}
