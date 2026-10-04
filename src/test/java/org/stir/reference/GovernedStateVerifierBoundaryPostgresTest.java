package org.stir.reference;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** AUD-012 constitutional vector at the database layer, executed against the real migration chain
 * through V20 with the ordinary runtime roles as they exist in production (idax_backend inherits
 * idax_app). Each attack runs as the ordinary credential and must fail with insufficient privilege,
 * never silently succeed. */
@Testcontainers
class GovernedStateVerifierBoundaryPostgresTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    static final UUID TENANT = UUID.randomUUID();
    static final UUID OTHER_TENANT = UUID.randomUUID();
    static final UUID COMMUNITY = UUID.randomUUID();
    static final UUID AUTHORITY = UUID.randomUUID();
    Connection superuser;

    @BeforeAll static void migrate() throws Exception {
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()); var s = c.createStatement()) {
            s.execute("create role idax_app; create role idax_admin; create role idax_backend login password 'x' inherit; grant idax_app to idax_backend");
        }
        IdaxCoreMembershipFixture.install(db);
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
            .schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }

    @BeforeEach void setup() throws Exception {
        superuser = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        superuser.setAutoCommit(false);
        try (var s = superuser.createStatement()) {
            s.execute("insert into idax_core.tenant(tenant_id) values ('" + TENANT + "'), ('" + OTHER_TENANT + "')");
            s.execute("insert into stir.marketplace_economic_binding values ('" + TENANT + "','" + COMMUNITY + "','" + UUID.randomUUID() + "',now())");
            s.execute("insert into stir.constitutional_authority (id, tenant_id, community_id, threshold, guardian_credential_id, guardian_public_key, guardian_status, next_sequence, created_at) values ('" + AUTHORITY + "','" + TENANT + "','" + COMMUNITY + "',7,'" + UUID.randomUUID() + "',repeat('A',43),'ACTIVE',1,now())");
            for (int ordinal = 1; ordinal <= 7; ordinal++) {
                s.execute("insert into stir.constitutional_seat values ('" + TENANT + "','" + AUTHORITY + "'," + ordinal + ",'" + UUID.randomUUID() + "','" + UUID.randomUUID() + "',repeat('B',43),'ACTIVE')");
            }
        }
    }

    @AfterEach void close() throws Exception {
        superuser.rollback(); superuser.close();
    }

    private void asRole(String role, String tenant) throws SQLException {
        try (var s = superuser.createStatement()) {
            s.execute("set local role " + role);
            s.execute("select set_config('app.tenant_id', '" + tenant + "', true)");
        }
    }

    /** Each attempt runs inside a savepoint so the seeded state survives and the role is restored. */
    private void assertDeniedAs(String role, String sql) throws SQLException {
        var sp = superuser.setSavepoint();
        try {
            asRole(role, TENANT.toString());
            try (var s = superuser.createStatement()) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute(sql), role + " must be denied: " + sql);
                assertEquals("42501", e.getSQLState(), role + " denied with insufficient privilege: " + sql);
            }
        } finally {
            superuser.rollback(sp);
        }
    }

    private void assertAllowedAs(String role, String sql) throws SQLException {
        var sp = superuser.setSavepoint();
        try {
            asRole(role, TENANT.toString());
            try (var s = superuser.createStatement()) { s.execute(sql); }
        } finally {
            superuser.rollback(sp);
        }
    }

    // ----- ordinary credentials cannot mutate effective constitutional state -----

    @Test void ordinaryRuntimeCannotInsertAMarketConstitution() throws SQLException {
        for (String role : new String[] {"idax_app", "idax_backend"}) {
            assertDeniedAs(role, "insert into stir.market_constitution values ('" + UUID.randomUUID() + "','" + TENANT + "','" + COMMUNITY + "','" + AUTHORITY + "',1,'{}','" + "a".repeat(64) + "',now())");
        }
    }

    @Test void ordinaryRuntimeCannotReplaceASeatPublicKeyOrCredential() throws SQLException {
        for (String role : new String[] {"idax_app", "idax_backend"}) {
            assertDeniedAs(role, "update stir.constitutional_seat set public_key = repeat('C',43) where tenant_id = '" + TENANT + "' and ordinal = 1");
            assertDeniedAs(role, "update stir.constitutional_seat set credential_id = '" + UUID.randomUUID() + "' where tenant_id = '" + TENANT + "' and ordinal = 1");
            assertDeniedAs(role, "update stir.constitutional_seat set controller_id = '" + UUID.randomUUID() + "' where tenant_id = '" + TENANT + "' and ordinal = 1");
            assertDeniedAs(role, "update stir.constitutional_seat set status = 'EMERGENCY_SUSPENDED' where tenant_id = '" + TENANT + "' and ordinal = 1");
        }
    }

    @Test void ordinaryRuntimeCannotChangeGuardianOrSequence() throws SQLException {
        for (String role : new String[] {"idax_app", "idax_backend"}) {
            assertDeniedAs(role, "update stir.constitutional_authority set guardian_public_key = repeat('D',43) where tenant_id = '" + TENANT + "'");
            assertDeniedAs(role, "update stir.constitutional_authority set next_sequence = 999 where tenant_id = '" + TENANT + "'");
            assertDeniedAs(role, "update stir.constitutional_authority set guardian_credential_id = '" + UUID.randomUUID() + "' where tenant_id = '" + TENANT + "'");
        }
    }

    @Test void ordinaryRuntimeCannotFabricateOrDeleteAuthorityHistory() throws SQLException {
        for (String role : new String[] {"idax_app", "idax_backend"}) {
            assertDeniedAs(role, "insert into stir.market_governance_event (id, tenant_id, authority_id, sequence, event_type, payload_json, previous_digest, digest_sha256, recorded_at) values ('" + UUID.randomUUID() + "','" + TENANT + "','" + AUTHORITY + "',1,'FABRICATED','{}',null,'" + "c".repeat(64) + "',now())");
            assertDeniedAs(role, "delete from stir.constitutional_seat where tenant_id = '" + TENANT + "'");
            assertDeniedAs(role, "delete from stir.constitutional_proposal where tenant_id = '" + TENANT + "'");
        }
    }

    @Test void ordinaryRuntimeKeepsReadAccessToConstitutionalState() throws SQLException {
        for (String role : new String[] {"idax_app", "idax_backend"}) {
            asRole(role, TENANT.toString());
            try (var s = superuser.createStatement(); var r = s.executeQuery("select count(*) from stir.constitutional_seat")) {
                assertTrue(r.next());
                assertEquals(7, r.getInt(1), role + " reads the seats");
            }
        }
    }

    // ----- the verifier is the only writer, scoped to its tenant -----

    @Test void verifierRoleCanWriteItsOwnTenantOnly() throws SQLException {
        assertAllowedAs("idax_governed_verifier", "insert into stir.market_constitution values ('" + UUID.randomUUID() + "','" + TENANT + "','" + COMMUNITY + "','" + AUTHORITY + "',1,'{}','" + "a".repeat(64) + "',now())");
        asRole("idax_governed_verifier", OTHER_TENANT.toString());
        try (var s = superuser.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.execute("insert into stir.market_constitution values ('" + UUID.randomUUID() + "','" + TENANT + "','" + COMMUNITY + "','" + AUTHORITY + "',2,'{}','" + "b".repeat(64) + "',now())"));
            assertEquals("42501", e.getSQLState());
        }
    }

    @Test void verifierCanReadMembershipOfItsTenantOnly() throws SQLException {
        superuser.createStatement().execute("insert into idax_core.app_user(user_id) values ('" + UUID.fromString("00000000-0000-0000-0000-000000000001") + "') on conflict do nothing");
        superuser.createStatement().execute("insert into idax_core.tenant_user(tenant_id,user_id,role) values ('" + TENANT + "','00000000-0000-0000-0000-000000000001','owner')");
        asRole("idax_governed_verifier", TENANT.toString());
        try (var s = superuser.createStatement(); var r = s.executeQuery("select role from idax_core.tenant_user where user_id = '00000000-0000-0000-0000-000000000001'")) {
            assertTrue(r.next());
            assertEquals("owner", r.getString(1));
        }
        asRole("idax_governed_verifier", OTHER_TENANT.toString());
        try (var s = superuser.createStatement(); var r = s.executeQuery("select count(*) from idax_core.tenant_user where user_id = '00000000-0000-0000-0000-000000000001'")) {
            assertTrue(r.next());
            assertEquals(0, r.getInt(1), "membership of another tenant is invisible to the verifier");
        }
    }

    // ----- role boundary: no inheritance or assumption path in either direction -----

    @Test void ordinaryRolesDoNotInheritOrAssumeTheVerifierRole() throws SQLException {
        try (var s = superuser.createStatement(); var r = s.executeQuery(
            "select pg_has_role('idax_backend','idax_governed_verifier','MEMBER') or pg_has_role('idax_app','idax_governed_verifier','MEMBER')"
            + " or pg_has_role('idax_admin','idax_governed_verifier','MEMBER')")) {
            assertTrue(r.next());
            assertFalse(r.getBoolean(1));
        }
        try (var s = superuser.createStatement(); var r = s.executeQuery(
            "select rolinherit from pg_roles where rolname = 'idax_governed_verifier'")) {
            assertTrue(r.next());
            assertFalse(r.getBoolean(1), "verifier must be NOINHERIT");
        }
    }

    @Test void ordinaryRuntimeCannotAssumeTheVerifierRole() throws SQLException {
        var sp = superuser.setSavepoint();
        try {
            asRole("idax_backend", TENANT.toString());
            try (var s = superuser.createStatement()) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute("set local role idax_governed_verifier"));
                assertEquals("42501", e.getSQLState());
            }
        } finally {
            superuser.rollback(sp);
        }
    }
}
