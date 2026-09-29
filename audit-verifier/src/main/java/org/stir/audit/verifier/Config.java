package org.stir.audit.verifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Every value here is read from environment/files the auditor container alone receives -
 * STIR_AUDITOR_* - never STIR_WEBAUTHN_*, STIR_JWT_*, OSTRIS_* or idax_backend's own secret.
 * CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md #6: "El auditor no recibe secretos WebAuthn, JWT
 * privados, osTRIS ni credenciales de backend; backend/extensiones no reciben credencial audit." */
public record Config(String jdbcUrl, String user, String password, long pollIntervalMillis,
                      int healthPort, String cursorName) {
    public static Config fromEnv() {
        String url = require("STIR_AUDITOR_JDBC_URL");
        String user = require("STIR_AUDITOR_USER");
        String password = readPassword();
        long pollMillis = Long.parseLong(System.getenv().getOrDefault("STIR_AUDITOR_POLL_INTERVAL_MS", "2000"));
        int healthPort = Integer.parseInt(System.getenv().getOrDefault("STIR_AUDITOR_HEALTH_PORT", "9090"));
        String cursorName = System.getenv().getOrDefault("STIR_AUDITOR_CURSOR_NAME", "primary");
        return new Config(url, user, password, pollMillis, healthPort, cursorName);
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing required env var " + name);
        return v;
    }

    /** Password comes from a file path (Docker secret convention, e.g. /run/secrets/stir_auditor_password
     * mounted read-only), never inline in an env var, mirroring how every other STIR secret in this
     * stack is injected (compose.yml's `secrets:` blocks, .local/secrets/* files). */
    private static String readPassword() {
        String passwordFile = System.getenv("STIR_AUDITOR_PASSWORD_FILE");
        if (passwordFile != null && !passwordFile.isBlank()) {
            try {
                return Files.readString(Path.of(passwordFile)).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Could not read STIR_AUDITOR_PASSWORD_FILE=" + passwordFile, e);
            }
        }
        // Local/dev-only fallback, never used by the compose.yml service (which always sets
        // STIR_AUDITOR_PASSWORD_FILE) - kept so `mvn test`/a bare `java -jar` run against a local
        // Postgres does not force a throwaway file onto disk just to exercise the verifier.
        return require("STIR_AUDITOR_PASSWORD");
    }
}
