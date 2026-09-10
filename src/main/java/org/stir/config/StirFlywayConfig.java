// Adapted from public osTRIS 0.3, Apache-2.0; see NOTICE.
package org.stir.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="stir.migrations.enabled", havingValue="true")
public class StirFlywayConfig {
    @Bean
    Flyway flywayStir(DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas("stir")
                .locations("classpath:db/migration-stir").baselineOnMigrate(true).load();
        flyway.migrate();
        return flyway;
    }
}
