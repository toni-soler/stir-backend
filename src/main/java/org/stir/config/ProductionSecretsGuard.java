package org.stir.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fails startup, not silently: a "production" profile with a blank/placeholder storage secret
 * would otherwise start up looking healthy and then fail (or silently use insecure defaults) the
 * first time a real request needs it. STIR never invents an ephemeral secret to paper over a
 * missing one - that would be lost on restart and give a false sense of durability.
 */
@Component
@ConditionalOnProperty(name = "stir.production-checks.enabled", havingValue = "true")
public class ProductionSecretsGuard {
    @Value("${storage.access-key:}") String storageAccessKey;
    @Value("${storage.secret-key:}") String storageSecretKey;

    @PostConstruct
    void verify() {
        require(storageAccessKey, "storage.access-key");
        require(storageSecretKey, "storage.secret-key");
    }

    private void require(String value, String property) {
        if (value == null || value.isBlank())
            throw new IllegalStateException("Refusing to start with stir.production-checks.enabled=true: "
                + property + " is blank. Set a real secret (never a default/placeholder) before running in production.");
    }
}
