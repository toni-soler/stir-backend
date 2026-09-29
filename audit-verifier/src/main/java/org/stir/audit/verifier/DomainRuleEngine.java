package org.stir.audit.verifier;

import java.sql.SQLException;
import java.util.Map;
import org.stir.audit.verifier.domain.*;

/** Dispatches an event to its domain's rule purely by the `domain` field the trigger itself
 * stamped (from coverage_registry at insert time) - never re-derives domain from table_name here,
 * so a coverage_registry misclassification would be caught by StirAuditCoverageRegistryTest, not
 * silently reinterpreted by the verifier. */
final class DomainRuleEngine {
    static final String RULE_VERSION = "stir-audit-verifier-domain-rules-v1";

    private final Map<String, DomainRule> rulesByDomain = Map.of(
        "CONSTITUTION", new ConstitutionRule(),
        "INTEGRITY", new IntegrityRule(),
        "ORDINARY", new OrdinaryRule(),
        "REFERENCE", new ReferenceRule(),
        "CONSENT_RETENTION", new ConsentRetentionRule(),
        "AGREEMENT_ECONOMIC", new AgreementEconomicRule(),
        "MARKETPLACE_IDENTITY", new MarketplaceIdentityRule()
    );

    DomainRule.Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        DomainRule rule = rulesByDomain.get(event.domain());
        if (rule == null) {
            return DomainRule.Reasoned.indeterminate("UNKNOWN_DOMAIN", "No domain rule registered for '" + event.domain() + "'.");
        }
        return rule.evaluate(event, sql);
    }
}
