package org.stir.reference;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;

/** Minimal idax_core membership tables for tests. Column names and types match the vendored
 * idax-core V1 definitions for the columns STIR's community-authority check reads; the real RLS
 * policy on idax_core.tenant_user is not reproduced here (tenant_id is filtered explicitly). */
final class IdaxCoreMembershipFixture {
    private IdaxCoreMembershipFixture() {}

    static void install(PostgreSQLContainer<?> db) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
             var s = c.createStatement()) {
            s.execute("create schema if not exists idax_core");
            s.execute("create table if not exists idax_core.app_user (user_id uuid primary key, is_active boolean not null default true)");
            s.execute("create table if not exists idax_core.tenant_user (tenant_id uuid not null, user_id uuid not null references idax_core.app_user(user_id), role varchar(30) not null default 'user', created_at timestamptz not null default now(), primary key(tenant_id,user_id))");
            s.execute("grant usage on schema idax_core to idax_app, idax_admin");
            s.execute("grant select on idax_core.app_user, idax_core.tenant_user to idax_app, idax_admin");
        }
    }

    /** Inserts membership as superuser inside the test transaction (rolled back with it), then restores the caller role. */
    static void member(Connection superuser, UUID tenant, UUID user, String role) throws SQLException {
        String previousRole;
        try (var s = superuser.createStatement(); var r = s.executeQuery("select current_setting('role')")) {
            r.next(); previousRole = r.getString(1);
            s.execute("reset role");
        }
        try (var s = superuser.prepareStatement("insert into idax_core.app_user(user_id) values (?) on conflict do nothing")) {
            s.setObject(1, user); s.executeUpdate();
        }
        try (var s = superuser.prepareStatement("insert into idax_core.tenant_user(tenant_id,user_id,role) values (?,?,?)")) {
            s.setObject(1, tenant); s.setObject(2, user); s.setString(3, role); s.executeUpdate();
        }
        if (!"none".equals(previousRole)) {
            try (var s = superuser.createStatement()) { s.execute("set local role " + previousRole); }
        }
    }
}
