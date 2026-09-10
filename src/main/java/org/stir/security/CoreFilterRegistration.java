package org.stir.security;

import es.idynamicsax.idax.security.JwtAuthFilter;
import es.idynamicsax.idax.tenant.TenantContextFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** Execute security filters exactly once, within the Spring Security chain. */
@Configuration
public class CoreFilterRegistration {
    @Bean FilterRegistrationBean<JwtAuthFilter> coreRegistration(JwtAuthFilter filter){var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;}
    @Bean FilterRegistrationBean<StirJwtAuthFilter> stirRegistration(StirJwtAuthFilter filter){var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;}
    @Bean FilterRegistrationBean<TenantContextFilter> tenantRegistration(TenantContextFilter filter){var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;}
}
