package org.stir.audit.verifier;

/** Entry point of the separate stir-audit-verifier process. Deliberately does not import, extend,
 * or start anything from org.stir.reference/org.stir.* (stir-backend's own package tree is not
 * even a dependency of this Maven module - see audit-verifier/pom.xml). Its only STIR-side
 * dependency at runtime is a PostgreSQL connection authenticated as stir_auditor. */
public final class Main {
    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        System.out.println("level=INFO component=stir-audit-verifier message=starting jdbc=" + config.jdbcUrl() + " user=" + config.user());

        HealthServer health = new HealthServer();
        health.start(config.healthPort());

        VerifierLoop loop = new VerifierLoop(config, health);
        Runtime.getRuntime().addShutdownHook(new Thread(loop::stop));
        loop.run();
    }
}
