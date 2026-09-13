package org.stir.storage;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness must distinguish "app started" from "storage reachable" - a photo upload otherwise
 * fails mysteriously instead of the orchestrator ever noticing the dependency was down. */
@Component
public class StorageHealthIndicator implements HealthIndicator {
    private final ObjectStorageService storage;
    public StorageHealthIndicator(ObjectStorageService storage) { this.storage = storage; }

    @Override
    public Health health() {
        return storage.isReachable()
            ? Health.up().withDetail("bucket", storage.bucket()).build()
            : Health.down().withDetail("bucket", storage.bucket()).build();
    }
}
