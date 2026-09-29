package org.stir.audit.verifier.domain;

import java.sql.SQLException;
import java.util.Map;
import org.stir.audit.verifier.AuditSql;
import org.stir.audit.verifier.MutationEvent;

/** ORDINARY domain: community_governance_settings, community_governance_member,
 * ordinary_governance_policy, ordinary_proposal, ordinary_proposal_electorate, ordinary_vote,
 * ordinary_proposal_execution, community_seed.
 * GOVERNED_STATE_AUDIT_ARCHITECTURE.md rule 3: "Los votos son registros DB sin firmas: se puede
 * comprobar matemática, no consentimiento real del votante." Never PASS_CRYPTO here. */
public final class OrdinaryRule implements DomainRule {
    @Override public Reasoned evaluate(MutationEvent event, AuditSql sql) throws SQLException {
        Map<String, String> pk = AuditSql.parseEntityKeyCanonical(event.entityKeyCanonical());

        if ("ordinary_vote".equals(event.tableName()) && "INSERT".equals(event.operation())) {
            String voteId = pk.get("id");
            String proposalId = sql.queryStringOrNull("select proposal_id::text from stir.ordinary_vote where id = ?::uuid", voteId);
            String voterId = sql.queryStringOrNull("select voter_id::text from stir.ordinary_vote where id = ?::uuid", voteId);
            long votesFromThisVoter = sql.queryLong("select count(*) from stir.ordinary_vote where proposal_id = ?::uuid and voter_id = ?::uuid", proposalId, voterId);
            if (votesFromThisVoter > 1) {
                return Reasoned.violation("DUPLICATE_VOTE", "voter " + voterId + " has " + votesFromThisVoter + " ordinary_vote rows for proposal " + proposalId + " - one-vote-per-elector is violated.");
            }
            return Reasoned.structureOnly("Exactly one recorded vote for this (proposal, voter) pair - voter identity/consent itself is a DB claim, not cryptographically attested.");
        }

        if ("ordinary_proposal_execution".equals(event.tableName()) && "INSERT".equals(event.operation())) {
            String execId = pk.get("id");
            String proposalId = sql.queryStringOrNull("select proposal_id::text from stir.ordinary_proposal_execution where id = ?::uuid", execId);
            String proposalStatus = sql.queryStringOrNull("select status from stir.ordinary_proposal where id = ?::uuid", proposalId);
            if (proposalStatus == null) {
                return Reasoned.violation("EXECUTION_WITHOUT_PROPOSAL", "ordinary_proposal_execution " + execId + " references proposal " + proposalId + " which does not exist.");
            }
            if (!"APPROVED".equalsIgnoreCase(proposalStatus) && !"EXECUTED".equalsIgnoreCase(proposalStatus)) {
                return Reasoned.violation("EXECUTION_WITHOUT_APPROVAL", "ordinary_proposal_execution " + execId + " executes proposal " + proposalId + " whose status is '" + proposalStatus + "', not an approved/executed state.");
            }
            return Reasoned.structureOnly("Execution " + execId + " corresponds to proposal " + proposalId + " in status '" + proposalStatus + "' - quorum/vote math not independently recomputed against electorate snapshot in Phase 1.");
        }

        if ("community_seed".equals(event.tableName()) && "INSERT".equals(event.operation())) {
            String seedId = pk.get("id");
            String proposalId = sql.queryStringOrNull("select proposal_id::text from stir.community_seed where id = ?::uuid", seedId);
            if (proposalId == null) {
                return Reasoned.violation("SEED_WITHOUT_PROPOSAL", "community_seed " + seedId + " has no proposal_id at all.");
            }
            long executions = sql.queryLong("select count(*) from stir.ordinary_proposal_execution where proposal_id = ?::uuid", proposalId);
            if (executions == 0) {
                return Reasoned.violation("SEED_WITHOUT_EXECUTION", "community_seed " + seedId + " claims proposal_id=" + proposalId + " but that proposal has no ordinary_proposal_execution row - MULTI_SOURCE_VALUE_EVIDENCE.md requires community_seed to come only from an approved-proposal execution.");
            }
            return Reasoned.structureOnly("community_seed " + seedId + " is linked to an executed ordinary_proposal.");
        }

        return Reasoned.structureOnly("Chain integrity is the only claim made for " + event.tableName() + " " + event.operation() + " events in Phase 1.");
    }
}
