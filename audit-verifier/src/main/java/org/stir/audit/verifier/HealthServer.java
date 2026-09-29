package org.stir.audit.verifier;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** No framework, no STIR dependency, no UI - just enough for a separate monitor/Docker healthcheck
 * to observe "is this process alive and how stale is its last successful cycle"
 * (GOVERNED_STATE_AUDIT_ARCHITECTURE.md: "health observable por un monitor separado"). Reports
 * unhealthy (503) once staleness exceeds a fixed threshold, never auto-restarts or self-heals. */
final class HealthServer {
    private final AtomicReference<Instant> lastSuccessfulCycle = new AtomicReference<>(null);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private static final long STALE_AFTER_SECONDS = 60;

    void markCycleSuccess() { lastSuccessfulCycle.set(Instant.now()); lastError.set(null); }
    void markCycleError(String message) { lastError.set(message); }

    HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            Instant last = lastSuccessfulCycle.get();
            String error = lastError.get();
            boolean healthy = last != null && java.time.Duration.between(last, Instant.now()).getSeconds() < STALE_AFTER_SECONDS && error == null;
            String body = "{\"healthy\":" + healthy + ",\"lastSuccessfulCycle\":\"" + last + "\",\"lastError\":" +
                (error == null ? "null" : "\"" + error.replace("\"", "'") + "\"") + "}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(healthy ? 200 : 503, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        return server;
    }
}
