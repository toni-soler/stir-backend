package org.stir.config;

import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The governed-state verifier never writes idax-core's permission catalog. The ordinary runtime
 * synchronises that catalog at boot and the verifier only reads it, so its database role keeps no
 * write privilege on the catalog. Removed only in the governed-verifier profile.
 * ModulePermissionCatalogLifecycle is the boot-time writer in idax-core 0.4.0 (it registers the module
 * catalog through ModulePermissionCatalogRegistrar); the older PermissionCatalogLoader is excluded too.
 * Without the lifecycle exclusion, a verifier that boots before the ordinary runtime on an empty
 * database fails with permission denied on idax_permission. */
@Configuration
@Profile("governed-verifier")
public class GovernedVerifierBeanExclusions {
    private static final Set<String> CATALOG_WRITERS = Set.of(
        "es.idynamicsax.idax.service.permission.PermissionCatalogLoader",
        "es.idynamicsax.idax.service.permission.ModulePermissionCatalogLifecycle");

    @Bean
    static BeanDefinitionRegistryPostProcessor excludePermissionCatalogWriter() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                for (String name : registry.getBeanDefinitionNames()) {
                    // @Bean factory-method definitions have no bean class name; Set.of(...).contains(null) would throw
                    String className = registry.getBeanDefinition(name).getBeanClassName();
                    if (className != null && CATALOG_WRITERS.contains(className)) {
                        registry.removeBeanDefinition(name);
                    }
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            }
        };
    }
}
