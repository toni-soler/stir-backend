// Adapted from public osTRIS 0.3, Apache-2.0; see NOTICE.
package org.stir.config;

import es.idynamicsax.idax.domain.IdaxPermission;
import es.idynamicsax.idax.repository.IdaxPermissionRepository;
import es.idynamicsax.idax.service.permission.ModulePermissionCatalogDescriptor;
import es.idynamicsax.idax.service.permission.ModulePermissionCatalogLifecycle;
import es.idynamicsax.idax.service.permission.ModulePermissionCatalogParser;
import es.idynamicsax.idax.service.permission.ModulePermissionCatalogRegistrar;
import es.idynamicsax.idax.service.permission.PermissionService;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackageClasses = IdaxPermission.class)
@EnableJpaRepositories(
        basePackageClasses = IdaxPermissionRepository.class,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = IdaxPermissionRepository.class),
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "es\\.idynamicsax\\.idax\\.repository\\.(?!IdaxPermissionRepository$).*")
)
@Import({
        ModulePermissionCatalogParser.class,
        ModulePermissionCatalogRegistrar.class,
        ModulePermissionCatalogLifecycle.class,
        PermissionService.class
})
public class StirPermissionCatalogConfiguration {
    @Bean
    ModulePermissionCatalogDescriptor stirPermissionCatalogDescriptor() {
        return new ModulePermissionCatalogDescriptor("stir");
    }
}
