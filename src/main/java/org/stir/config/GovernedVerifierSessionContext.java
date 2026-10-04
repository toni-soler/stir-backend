package org.stir.config;

import es.idynamicsax.idax.tenant.DbSessionContextService;
import es.idynamicsax.idax.tenant.TenantContext;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The governed verifier logs in as idax_governed_verifier and must keep that identity: idax-core's
 * session service also issues SET LOCAL ROLE for the caller's DbRole, which would require the verifier
 * to be a member of idax_app. This replacement applies the same tenant and user session values
 * (idax_core.set_tenant and app.user_id) and never changes role. Active only in the governed-verifier
 * profile. */
@Configuration
@Profile("governed-verifier")
public class GovernedVerifierSessionContext {
    @Bean
    @Primary
    DbSessionContextService governedVerifierSessionContext(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new DbSessionContextService(dataSource) {
            @Override
            public void applyFromTenantContext() {
                TenantContext context = TenantContext.get();
                if (context == null || context.getTenantId() == null) {
                    return;
                }
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw new IllegalStateException("Tenant context must be applied inside a transaction");
                }
                try {
                    jdbc.queryForObject("select idax_core.set_tenant(?)", Object.class, context.getTenantId().toString());
                    if (context.getAppUserId() != null) {
                        jdbc.queryForObject("select set_config('app.user_id', ?, true)", String.class, context.getAppUserId().toString());
                    }
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("Failed to apply RLS session context", failure);
                }
            }
        };
    }
}
