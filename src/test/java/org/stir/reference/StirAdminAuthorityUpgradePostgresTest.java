package org.stir.reference;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** V16 data must survive the V17 authority correction without rewriting history. */
@Testcontainers
class StirAdminAuthorityUpgradePostgresTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void v16ToV17PreservesExistingHistoryAndRevokesAdminWrites() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create role idax_app; create role idax_admin");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stir").locations("classpath:db/migration-stir").target(MigrationVersion.fromVersion("16")).load().migrate();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var db = new JdbcTemplate(dataSource);
        UUID tenant = UUID.randomUUID(), community = UUID.randomUUID(), id = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("set local role idax_admin");
                statement.execute("select set_config('app.tenant_id', '" + tenant + "', true)");
                statement.execute("insert into stir.market_constitution values ('" + id + "','" + tenant + "','" + community +
                    "','" + UUID.randomUUID() + "',1,'{}','" + "0".repeat(64) + "',now())");
            }
            connection.commit();
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("stir").locations("classpath:db/migration-stir").load().migrate();
        assertEquals(1, db.queryForObject("select count(*) from stir.market_constitution where id=?", Integer.class, id));
        assertFalse(db.queryForObject("select has_table_privilege('idax_admin','stir.market_constitution','INSERT')", Boolean.class));
        assertFalse(db.queryForObject("select has_table_privilege('idax_admin','stir.market_integrity_case_event','INSERT')", Boolean.class));
        assertEquals("17", db.queryForObject("select version from stir.flyway_schema_history where success order by installed_rank desc limit 1", String.class));
    }
}
