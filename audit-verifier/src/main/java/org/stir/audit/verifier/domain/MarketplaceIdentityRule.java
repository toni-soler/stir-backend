package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** MARKETPLACE_IDENTITY domain: listing, listing_revision, participant_independence_projection.
 * The projection is refreshed FROM osTRIS (PARTICIPANT_INDEPENDENCE.md); this verifier has no
 * osTRIS credential, so a projection mutation is always INDETERMINATE, never a claim that it
 * matches osTRIS's own source of truth right now. */
public final class MarketplaceIdentityRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        if ("participant_independence_projection".equals(event.tableName())) {
            return Reasoned.indeterminate("OSTRIS_SOURCE_NOT_CROSS_CHECKED",
                "participant_independence_projection is refreshed from osTRIS's own risk_subject data; this verifier cannot independently confirm the projection matches osTRIS's current source without an osTRIS credential, which it deliberately does not hold.");
        }
        return Reasoned.structureOnly("MARKETPLACE_IDENTITY domain: " + event.tableName() + " " + event.operation() +
            " - listing/listing_revision author and clone/lineage claims are not cryptographically attested in this schema.");
    }
}
