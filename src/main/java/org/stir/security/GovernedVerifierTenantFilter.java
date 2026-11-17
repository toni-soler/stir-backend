package org.stir.security;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.AppUserResolver;
import es.idynamicsax.idax.tenant.TenantContext;
import es.idynamicsax.idax.tenant.TenantContextFilter;
import es.idynamicsax.idax.tenant.TenantResolver;
import es.idynamicsax.idax.repository.auth.AuthLocalIdentityLookupRepository;
import es.idynamicsax.idax.service.auth.LocalIdentitySubjectPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant binding for the governed verifier. The verified JWT identity alone never selects a tenant:
 * the tenant in the path must equal the token's tenant claim, and the identity must hold an active
 * membership in that tenant as read from the database under that tenant's row-level security. No
 * membership is ever created and no database role is ever assumed. Replaces idax-core's
 * TenantContextFilter only in the governed-verifier profile (see StirSecurityConfig). */
public class GovernedVerifierTenantFilter extends TenantContextFilter {
    private static final Pattern TENANT_PATH = Pattern.compile("^/api/stir/tenants/([0-9a-fA-F-]{36})(/|$)");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public GovernedVerifierTenantFilter(TenantResolver tenants, AppUserResolver users,
                                        AuthLocalIdentityLookupRepository identities, LocalIdentitySubjectPolicy subjects,
                                        DataSource dataSource) {
        super(tenants, users, identities, subjects);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!(SecurityContextHolder.getContext().getAuthentication() != null
                    && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof CurrentUser user)) {
                chain.doFilter(request, response);
                return;
            }
            Matcher path = TENANT_PATH.matcher(request.getRequestURI());
            if (!path.find()) {
                response.sendError(403);
                return;
            }
            UUID pathTenant = UUID.fromString(path.group(1));
            if (user.getTenantId() == null || !user.getTenantId().equals(pathTenant)) {
                response.sendError(403);
                return;
            }
            Map<String, Object> membership = tx.execute(status -> {
                jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, pathTenant.toString());
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "select tu.role from idax_core.tenant_user tu join idax_core.app_user au on au.user_id = tu.user_id "
                        + "where tu.tenant_id = ? and tu.user_id = ? and au.is_active", pathTenant, user.getUserId());
                return rows.isEmpty() ? null : rows.getFirst();
            });
            if (membership == null) {
                response.sendError(403);
                return;
            }
            TenantContext.set(new TenantContext(pathTenant, user.getUsername(), user.getUserId(), "governed-verifier",
                    TenantContext.DbRole.IDAX_APP));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
