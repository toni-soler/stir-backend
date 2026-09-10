// Adapted from public osTRIS 0.3, Apache-2.0; see NOTICE.
package org.stir;

import es.idynamicsax.idax.config.ResourceServerJwtDecoderConfig;
import es.idynamicsax.idax.config.TokenValidatorConfig;
import es.idynamicsax.idax.config.TransactionConfig;
import es.idynamicsax.idax.security.DualTokenValidator;
import es.idynamicsax.idax.security.KeycloakTokenValidator;
import es.idynamicsax.idax.security.LocalTokenValidator;
import es.idynamicsax.idax.tenant.AppUserResolver;
import es.idynamicsax.idax.tenant.DbSessionContextService;
import es.idynamicsax.idax.tenant.RlsTransactionAspect;
import es.idynamicsax.idax.tenant.TenantResolver;
import es.idynamicsax.idax.repository.auth.AuthLocalIdentityLookupJdbcRepository;
import es.idynamicsax.idax.repository.admin.IdentityEnsureExternalUserJdbcRepository;
import es.idynamicsax.idax.repository.admin.IdentityResolveOrCreateExternalUserJdbcRepository;
import es.idynamicsax.idax.service.auth.LocalIdentitySubjectPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EntityScan("org.stir")
@EnableJpaRepositories("org.stir")
@Import({
        ResourceServerJwtDecoderConfig.class,
        TokenValidatorConfig.class,
        LocalTokenValidator.class,
        KeycloakTokenValidator.class,
        DualTokenValidator.class,
        TenantResolver.class,
        AppUserResolver.class,
        IdentityEnsureExternalUserJdbcRepository.class,
        IdentityResolveOrCreateExternalUserJdbcRepository.class,
        AuthLocalIdentityLookupJdbcRepository.class,
        LocalIdentitySubjectPolicy.class,
        DbSessionContextService.class,
        RlsTransactionAspect.class,
        TransactionConfig.class
        ,es.idynamicsax.idax.security.JwtAuthFilter.class
})
@EnableMethodSecurity
public class StirApplication {
    public static void main(String[] args) {
        SpringApplication.run(StirApplication.class, args);
    }
}
