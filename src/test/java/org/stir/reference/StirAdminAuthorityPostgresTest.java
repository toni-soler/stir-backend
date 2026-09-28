package org.stir.reference;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Savepoint;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** Direct PostgreSQL authority checks; HTTP authorization alone cannot protect these states. */
@Testcontainers
class StirAdminAuthorityPostgresTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void platformAdminHasNoDirectStirMutationAfterCleanMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create role idax_app; create role idax_admin");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stir").locations("classpath:db/migration-stir").load().migrate();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            List<String> tables = db.queryForList("select tablename from pg_tables where schemaname='stir' and tablename <> 'flyway_schema_history' order by tablename", String.class);
            assertTrue(tables.size() > 30, "The test must cover the full STIR schema, not a handpicked pair");
            for (String table : tables) {
                String qualified = "stir." + table;
                for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                    assertFalse(db.queryForObject("select has_table_privilege('idax_admin', ?, ?)", Boolean.class, qualified, privilege), qualified + " " + privilege);
                }
                for (String privilege : List.of("INSERT", "UPDATE", "REFERENCES")) {
                    assertFalse(db.queryForObject("select has_any_column_privilege('idax_admin', ?, ?)", Boolean.class, qualified, privilege), qualified + " column " + privilege);
                }
            }
            db.execute("set local role idax_admin");
            db.queryForObject("select set_config('app.tenant_id', '00000000-0000-0000-0000-000000000001', true)", String.class);
            for (String table : List.of("market_constitution", "constitutional_signature", "market_integrity_case",
                    "market_integrity_case_event", "reference_proposal", "ordinary_vote", "reference_consent_event",
                    "retention_policy", "constitutional_webauthn_challenge")) {
                Savepoint savepoint = connection.setSavepoint();
                String sql = "insert into stir." + table + " default values";
                Exception failure = assertThrows(Exception.class, () -> db.execute(sql), sql);
                String databaseError = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
                assertTrue(databaseError.contains("permission denied"), table + ": " + databaseError);
                connection.rollback(savepoint);
            }
            connection.rollback();
        }
    }
}
