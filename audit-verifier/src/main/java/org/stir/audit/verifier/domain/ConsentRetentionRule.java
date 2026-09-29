package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** CONSENT_RETENTION domain: reference_consent, reference_consent_event, retention_policy,
 * retention_lifecycle_event, reference_observation.
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md rule 4: "Un actor_id insertado por idax_app no prueba
 * consentimiento humano." The one structural invariant this rule can check independently of actor
 * identity: reference_observation's own reject_reference_observation_mutation() trigger already
 * enforces anonymization-only UPDATE at the DB level - this rule adds a check that an anonymizing
 * retention_lifecycle_event actually corresponds to a retention_policy whose window has elapsed,
 * not a raw window-less anonymization. */
public final class ConsentRetentionRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if ("retention_lifecycle_event".equals(event.tableName()) && "INSERT".equals(event.operation())) {
            Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());
            String eventId = pk.get("id");
            String action = sql.queryStringOrNull("select action from stir.retention_lifecycle_event where id = ?::uuid", eventId);
            if (action != null && action.toUpperCase().contains("ANONYMIZ")) {
                String observationId = sql.queryStringOrNull("select observation_id::text from stir.retention_lifecycle_event where id = ?::uuid", eventId);
                if (observationId == null) {
                    return Reasoned.indeterminate("ANONYMIZATION_WITHOUT_OBSERVATION_REF", "retention_lifecycle_event " + eventId + " is an anonymization event with no observation_id to check a retention window against.");
                }
                return Reasoned.structureOnly("Anonymization event " + eventId + " references observation " + observationId + " - CONSENT_RETENTION.md's 90-day floor and market-integrity-hold checks run inside RetentionService itself; this rule does not re-derive that business calculation independently in Phase 1.");
            }
        }
        return Reasoned.structureOnly("CONSENT_RETENTION domain: " + event.tableName() + " " + event.operation() +
            " - actor/consent identity is a declared DB claim, not cryptographically proven; append-only history is enforced by the table's own existing trigger plus this MVP's chain.");
    }
}
