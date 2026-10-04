package org.stir.config;

import es.idynamicsax.idax.service.permission.PermissionCatalogLoader;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The governed-state verifier never writes idax-core's permission catalog. The ordinary runtime
 * synchronises that catalog at boot and the verifier only reads it, so its database role keeps no
 * write privilege on the catalog. Removed only in the governed-verifier profile. */
@Configuration
@Profile("governed-verifier")
public class GovernedVerifierBeanExclusions {
    @Bean
    static BeanDefinitionRegistryPostProcessor excludePermissionCatalogWriter() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                for (String name : registry.getBeanDefinitionNames()) {
                    if (PermissionCatalogLoader.class.getName().equals(registry.getBeanDefinition(name).getBeanClassName())) {
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
