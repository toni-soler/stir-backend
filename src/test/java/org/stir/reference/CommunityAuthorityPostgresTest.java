package org.stir.reference;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BOOTSTRAP COMMUNITY AUTHORITY (governance disabled: tenant owner/admin) and ORDINARY GOVERNANCE
 * ROSTER AUTHORITY (governance enabled: active stir.community_governance_member only, no fallback
 * to tenant role). Platform permission and SuperAdmin never confer community authority. */
@Testcontainers
class CommunityAuthorityPostgresTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc;
    UUID tenant = UUID.randomUUID(), community = UUID.randomUUID(), otherTenant = UUID.randomUUID();

    @BeforeAll static void migrate() throws Exception {
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()); var s = c.createStatement()) {
            s.execute("create role idax_app; create role idax_admin");
        }
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).schemas("stir").locations("classpath:db/migration-stir").load().migrate();
        IdaxCoreMembershipFixture.install(db);
    }

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())", tenant, community, UUID.randomUUID());
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())", otherTenant, UUID.randomUUID(), UUID.randomUUID());
        jdbc.execute("set local role idax_app");
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)", String.class, tenant.toString());
        TenantContext.set(new TenantContext(tenant, null, UUID.randomUUID(), "test", TenantContext.DbRole.IDAX_APP));
    }

    @AfterEach void close() throws Exception {
        connection.rollback(); connection.close(); TenantContext.clear();
    }

    private CurrentUser user(UUID id, boolean superuser, boolean service) {
        CurrentUser u = mock(CurrentUser.class);
        when(u.getUserId()).thenReturn(id);
        when(u.isSuperuser()).thenReturn(superuser);
        when(u.isService()).thenReturn(service);
        return u;
    }

    private UUID member(String role) throws Exception {
        UUID id = UUID.randomUUID();
        IdaxCoreMembershipFixture.member(connection, tenant, id, role);
        return id;
    }

    private void enableGovernance(UUID by) {
        jdbc.update("insert into stir.community_governance_settings values (?,?,true,now(),?)", tenant, community, by);
    }

    private void rosterMember(UUID id) {
        jdbc.update("insert into stir.community_governance_member values (?,?,?,?,now(),?,null,null)", UUID.randomUUID(), tenant, community, id, id);
    }

    private void assertAllowed(CurrentUser u) {
        assertDoesNotThrow(() -> ReferenceService.requireCommunityAuthority(jdbc, u, community));
    }

    private void assertDenied(CurrentUser u) {
        assertThrows(AccessDeniedException.class, () -> ReferenceService.requireCommunityAuthority(jdbc, u, community));
    }

    // ----- governance DISABLED: bootstrap authority is tenant owner/admin only -----

    @Test void disabledTenantOwnerIsAllowed() throws Exception {
        assertAllowed(user(member("owner"), false, false));
    }

    @Test void disabledTenantAdminIsAllowed() throws Exception {
        assertAllowed(user(member("admin"), false, false));
    }

    @Test void disabledTenantUserIsDenied() throws Exception {
        assertDenied(user(member("user"), false, false));
    }

    @Test void disabledReadonlyIsDenied() throws Exception {
        assertDenied(user(member("readonly"), false, false));
    }

    @Test void disabledServiceRoleIsDenied() throws Exception {
        assertDenied(user(member("service"), false, true));
    }

    @Test void superAdminWithoutCommunityMembershipIsDenied() {
        assertDenied(user(UUID.randomUUID(), true, false));
    }

    @Test void superAdminWithoutMembershipIsDeniedWhenGovernanceEnabled() {
        UUID admin = UUID.randomUUID();
        enableGovernance(admin);
        rosterMember(UUID.randomUUID());
        assertDenied(user(admin, true, false));
    }

    @Test void tenantAOwnerActingOnTenantBIsDenied() throws Exception {
        UUID ownerA = member("owner");
        TenantContext.set(new TenantContext(otherTenant, null, ownerA, "test", TenantContext.DbRole.IDAX_APP));
        jdbc.queryForObject("select set_config('app.tenant_id',?,true)", String.class, otherTenant.toString());
        assertThrows(AccessDeniedException.class, () -> ReferenceService.requireCommunityAuthority(jdbc, user(ownerA, false, false), null));
    }

    @Test void requestedCommunityMustBeTheTenantBoundCommunity() throws Exception {
        UUID owner = member("owner");
        assertThrows(AccessDeniedException.class,
            () -> ReferenceService.requireCommunityAuthority(jdbc, user(owner, false, false), UUID.randomUUID()));
    }

    @Test void serviceDenialIsEnforcedThroughARealMutation() throws Exception {
        UUID user = member("user");
        var service = new ReferenceService(jdbc, null);
        assertThrows(AccessDeniedException.class, () -> service.create(user(user, false, false),
            new ReferenceController.DefinitionRequest("Bread", "500g", Map.of("weight", "500g"), java.math.BigDecimal.ONE, "loaf")));
    }

    // ----- governance ENABLED: roster authority, no fallback to tenant role -----

    @Test void enabledActiveRosterMemberIsAllowed() throws Exception {
        UUID member = member("user");
        enableGovernance(member);
        rosterMember(member);
        assertAllowed(user(member, false, false));
    }

    @Test void enabledTenantOwnerNotOnRosterIsDenied() throws Exception {
        UUID owner = member("owner");
        UUID otherMember = member("user");
        enableGovernance(otherMember);
        rosterMember(otherMember);
        assertDenied(user(owner, false, false));
    }

    @Test void enabledTenantAdminNotOnRosterIsDenied() throws Exception {
        UUID admin = member("admin");
        UUID otherMember = member("user");
        enableGovernance(otherMember);
        rosterMember(otherMember);
        assertDenied(user(admin, false, false));
    }

    @Test void enabledRemovedRosterMemberIsDenied() throws Exception {
        UUID member = member("user");
        enableGovernance(member);
        rosterMember(member);
        jdbc.update("update stir.community_governance_member set removed_at=now(), removed_by=? where tenant_id=? and user_id=?", member, tenant, member);
        assertDenied(user(member, false, false));
    }

    @Test void enabledSuperAdminTenantOwnerWithoutRosterIsDenied() throws Exception {
        UUID superOwner = member("owner");
        UUID otherMember = member("user");
        enableGovernance(otherMember);
        rosterMember(otherMember);
        CurrentUser u = user(superOwner, true, false);
        assertDenied(u);
    }

    @Test void disabledGovernanceDoesNotAdoptRosterOnlyFallback() throws Exception {
        UUID member = member("user");
        rosterMember(member);
        assertDenied(user(member, false, false));
    }

    @Test void rosterIsNotAutoEnrolledFromTenantMembership() throws Exception {
        UUID owner = member("owner");
        enableGovernance(owner);
        rosterMember(owner);
        UUID admin = member("admin");
        Integer count = jdbc.queryForObject("select count(*) from stir.community_governance_member where tenant_id=? and user_id=?", Integer.class, tenant, admin);
        assertEquals(0, count);
        assertDenied(user(admin, false, false));
    }
}
