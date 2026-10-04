package org.stir.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;

/** The governed-state verifier must not run idax-core's boot-time permission catalog writers: on an empty
 * database it would fail with permission denied on idax_permission when it starts before the ordinary runtime. */
class GovernedVerifierBeanExclusionsTest {
    private static final String LIFECYCLE = "es.idynamicsax.idax.service.permission.ModulePermissionCatalogLifecycle";
    private static final String LOADER = "es.idynamicsax.idax.service.permission.PermissionCatalogLoader";
    private static final String REGISTRAR = "es.idynamicsax.idax.service.permission.ModulePermissionCatalogRegistrar";

    private static void register(DefaultListableBeanFactory factory, String name, String className) {
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClassName(className);
        factory.registerBeanDefinition(name, definition);
    }

    @Test
    void removesEveryPermissionCatalogWriterAndKeepsTheReadPathBeans() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        register(factory, "lifecycle", LIFECYCLE);
        register(factory, "loader", LOADER);
        register(factory, "registrar", REGISTRAR);
        // a @Bean factory method has no bean class name: the exclusion must skip it, not fail
        factory.registerBeanDefinition("factoryMethodBean", new GenericBeanDefinition());

        BeanDefinitionRegistryPostProcessor exclusion = GovernedVerifierBeanExclusions.excludePermissionCatalogWriter();
        exclusion.postProcessBeanDefinitionRegistry(factory);

        assertFalse(factory.containsBeanDefinition("lifecycle"), "the boot-time catalog writer must not exist in the verifier");
        assertFalse(factory.containsBeanDefinition("loader"), "the legacy catalog loader must not exist in the verifier");
        assertTrue(factory.containsBeanDefinition("registrar"), "unrelated permission beans stay available for reads");
        assertTrue(factory.containsBeanDefinition("factoryMethodBean"), "beans without a class name are left alone");
    }
}
