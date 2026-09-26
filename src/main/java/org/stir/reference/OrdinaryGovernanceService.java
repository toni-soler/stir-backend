package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/**
 * Ordinary community governance: a real, quorum-based, non-constitutional decision process for
 * publishing a Community Value Reference or changing a ReferencePolicy - explicitly separate from
 * Seven Keys (ORDINARY_GOVERNANCE.md). A community opts in per-community
 * (stir.community_governance_settings); until it does, ReferenceService.publish()/policy() behave
 * exactly as before. Platform SuperAdmin, the Guardian and Seven Keys seats have no vote here by
 * virtue of that role - ReferenceService.requireCommunityAuthority already refuses SuperAdmin, and
 * this service's own electorate (stir.community_governance_member) is a wholly separate, explicit
 * STIR-owned roster nothing else grants membership in automatically.
 */
@Service @Transactional
public class OrdinaryGovernanceService {
    private final JdbcTemplate db;
    private final ReferenceService reference;
    public OrdinaryGovernanceService(JdbcTemplate db, ReferenceService reference) { this.db=db; this.reference=reference; }

    private Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args); if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Not found"); return rows.getFirst();
    }
    private void lock(String key) { db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",key); }

    // --- Settings: opt-in switch, per community ---
    public Map<String,Object> settings(UUID community) {
        var rows=db.queryForList("select * from stir.community_governance_settings where tenant_id=? and community_id=?",ReferenceService.tenant(),community);
        return rows.isEmpty()?Map.of("communityId",community,"ordinaryGovernanceEnabled",false):Map.of("communityId",community,"ordinaryGovernanceEnabled",rows.getFirst().get("ordinary_governance_enabled"));
    }
    public Map<String,Object> setEnabled(CurrentUser user,UUID community,boolean enabled) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        db.update("insert into stir.community_governance_settings values (?,?,?,?,?) on conflict (tenant_id,community_id) do update set "+
            "ordinary_governance_enabled=excluded.ordinary_governance_enabled,updated_at=excluded.updated_at,updated_by=excluded.updated_by",
            ReferenceService.tenant(),community,enabled,Timestamp.from(Instant.now()),actor);
        return settings(community);
    }

    // --- Electorate roster (STIR-owned, never derived from IDAX roles/permissions) ---
    public List<Map<String,Object>> members(UUID community) {
        return db.queryForList("select user_id,added_at,added_by from stir.community_governance_member "+
            "where tenant_id=? and community_id=? and removed_at is null order by added_at",ReferenceService.tenant(),community);
    }
    public Map<String,Object> addMember(CurrentUser user,UUID community,UUID targetUser) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        var existing=db.queryForList("select id from stir.community_governance_member where tenant_id=? and community_id=? and user_id=?",ReferenceService.tenant(),community,targetUser);
        if(!existing.isEmpty()) {
            db.update("update stir.community_governance_member set removed_at=null,removed_by=null,added_at=?,added_by=? where tenant_id=? and id=?",
                Timestamp.from(Instant.now()),actor,ReferenceService.tenant(),existing.getFirst().get("id"));
        } else {
            db.update("insert into stir.community_governance_member values (?,?,?,?,?,?,null,null)",
                UUID.randomUUID(),ReferenceService.tenant(),community,targetUser,Timestamp.from(Instant.now()),actor);
        }
        return Map.of("communityId",community,"userId",targetUser,"member",true);
    }
    public Map<String,Object> removeMember(CurrentUser user,UUID community,UUID targetUser) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        db.update("update stir.community_governance_member set removed_at=?,removed_by=? where tenant_id=? and community_id=? and user_id=? and removed_at is null",
            Timestamp.from(Instant.now()),actor,ReferenceService.tenant(),community,targetUser);
        return Map.of("communityId",community,"userId",targetUser,"member",false);
    }

    // --- Voting policy v0.1: one explicit, versioned, auditable shape - not a DSL ---
    public record PolicyRequest(int quorumNumerator,int quorumDenominator,int approvalNumerator,int approvalDenominator,
        int votingWindowHours,String abstentionRule,String explanation) {}
    public Map<String,Object> setPolicy(CurrentUser user,UUID community,PolicyRequest r) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        if(r.approvalNumerator()*2<=r.approvalDenominator())
            throw new ResponseStatusException(BAD_REQUEST,"Approval threshold must mean a real majority (>50%)");
        if(!Set.of("COUNTS_TOWARD_QUORUM_NOT_APPROVAL","DOES_NOT_COUNT_TOWARD_QUORUM").contains(r.abstentionRule()))
            throw new ResponseStatusException(BAD_REQUEST,"Invalid abstention rule");
        int version=db.queryForObject("select coalesce(max(version),0)+1 from stir.ordinary_governance_policy where tenant_id=? and community_id=?",Integer.class,ReferenceService.tenant(),community);
        var canonicalMap=new LinkedHashMap<String,Object>();
        canonicalMap.put("quorumNumerator",r.quorumNumerator()); canonicalMap.put("quorumDenominator",r.quorumDenominator());
        canonicalMap.put("approvalNumerator",r.approvalNumerator()); canonicalMap.put("approvalDenominator",r.approvalDenominator());
        canonicalMap.put("votingWindowHours",r.votingWindowHours()); canonicalMap.put("abstentionRule",r.abstentionRule());
        canonicalMap.put("version",version); canonicalMap.put("communityId",community.toString());
        String json=ReferenceService.canonical(canonicalMap);
        UUID id=UUID.randomUUID();
        db.update("insert into stir.ordinary_governance_policy values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,ReferenceService.tenant(),community,version,
            r.quorumNumerator(),r.quorumDenominator(),r.approvalNumerator(),r.approvalDenominator(),r.votingWindowHours(),r.abstentionRule(),
            r.explanation(),json,ReferenceService.digest(json),actor,Timestamp.from(Instant.now()));
        return one("select * from stir.ordinary_governance_policy where tenant_id=? and id=?",ReferenceService.tenant(),id);
    }
    public Map<String,Object> currentPolicy(UUID community) {
        var rows=db.queryForList("select * from stir.ordinary_governance_policy where tenant_id=? and community_id=? order by version desc limit 1",ReferenceService.tenant(),community);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"No ordinary governance policy set for this community yet");
        return rows.getFirst();
    }

    // --- Proposal creation: freezes electorate + reference/policy state, never a live recompute later ---
    private List<UUID> activeElectorate(UUID community) {
        return db.queryForList("select user_id from stir.community_governance_member where tenant_id=? and community_id=? and removed_at is null order by user_id",
            ReferenceService.tenant(),community).stream().map(m->(UUID)m.get("user_id")).toList();
    }
    private void requireElector(UUID community,UUID actor) {
        var rows=db.queryForList("select 1 from stir.community_governance_member where tenant_id=? and community_id=? and user_id=? and removed_at is null",
            ReferenceService.tenant(),community,actor);
        if(rows.isEmpty()) throw new ResponseStatusException(FORBIDDEN,"Not a member of this community's ordinary governance electorate");
    }
    private UUID freezeProposal(CurrentUser user,UUID community,UUID definitionId,String type,UUID targetReferenceProposal,
                                 String payloadJson,String referenceStateDigest) {
        var policy=currentPolicy(community);
        var electorate=activeElectorate(community);
        String electorateJson=ReferenceService.canonical(Map.of("members",electorate.stream().map(UUID::toString).sorted().toList()));
        UUID proposalId=UUID.randomUUID();
        Instant opens=Instant.now(), closes=opens.plus(Duration.ofHours((int)policy.get("voting_window_hours")));
        db.update("insert into stir.ordinary_proposal values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            proposalId,ReferenceService.tenant(),community,type,definitionId,targetReferenceProposal,payloadJson,ReferenceService.digest(payloadJson),
            referenceStateDigest,policy.get("id"),policy.get("version"),ReferenceService.digest(electorateJson),
            Timestamp.from(opens),Timestamp.from(closes),"OPEN",null,null,ReferenceService.actor(user),Timestamp.from(opens));
        for(UUID member:electorate) db.update("insert into stir.ordinary_proposal_electorate values (?,?,?)",ReferenceService.tenant(),proposalId,member);
        return proposalId;
    }
    private String policyStateDigest(UUID definitionId) {
        var policy=reference.currentPolicy(definitionId);
        return ReferenceService.digest(ReferenceService.canonical(Map.of("policyId",policy.get("id").toString(),"policyVersion",policy.get("version"))));
    }
    public Map<String,Object> proposePublishReference(CurrentUser user,UUID definitionId,UUID referenceProposalId) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        var definition=reference.definition(definitionId); UUID community=(UUID)definition.get("community_id");
        requireElector(community,actor);
        var refProposal=one("select * from stir.reference_proposal where tenant_id=? and id=? and definition_id=?",ReferenceService.tenant(),referenceProposalId,definitionId);
        if(!db.queryForList("select id from stir.community_reference where tenant_id=? and proposal_id=?",ReferenceService.tenant(),referenceProposalId).isEmpty())
            throw new ResponseStatusException(CONFLICT,"This reference proposal is already published");
        if(!db.queryForList("select id from stir.ordinary_proposal where tenant_id=? and target_reference_proposal_id=? and status in ('OPEN','APPROVED')",ReferenceService.tenant(),referenceProposalId).isEmpty())
            throw new ResponseStatusException(CONFLICT,"An ordinary governance vote is already open or approved for this reference proposal");
        String payload=ReferenceService.canonical(Map.of("referenceProposalId",referenceProposalId.toString(),
            "kind",refProposal.get("kind"),"snapshotId",refProposal.get("snapshot_id").toString()));
        UUID proposal=freezeProposal(user,community,definitionId,"PUBLISH_REFERENCE",referenceProposalId,payload,policyStateDigest(definitionId));
        return proposal(proposal);
    }
    public Map<String,Object> proposePolicyChange(CurrentUser user,UUID definitionId,ReferenceController.PolicyRequest r) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        var definition=reference.definition(definitionId); UUID community=(UUID)definition.get("community_id");
        requireElector(community,actor);
        var payloadMap=new LinkedHashMap<String,Object>();
        payloadMap.put("windowDays",r.windowDays()); payloadMap.put("minimumObservations",r.minimumObservations());
        payloadMap.put("minimumParticipants",r.minimumParticipants()); payloadMap.put("maximumParticipantShare",r.maximumParticipantShare().toPlainString());
        payloadMap.put("freshnessDays",r.freshnessDays()); payloadMap.put("explanation",r.explanation());
        payloadMap.put("minimumIndependenceCoveragePercent",r.minimumIndependenceCoveragePercent());
        String payload=ReferenceService.canonical(payloadMap);
        UUID proposal=freezeProposal(user,community,definitionId,"REFERENCE_POLICY_CHANGE",null,payload,policyStateDigest(definitionId));
        return proposal(proposal);
    }

    // --- Voting ---
    public Map<String,Object> vote(CurrentUser user,UUID proposalId,String choice) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        if(!Set.of("APPROVE","REJECT","ABSTAIN").contains(choice)) throw new ResponseStatusException(BAD_REQUEST,"Invalid vote choice");
        lock(ReferenceService.tenant()+":ordinary-vote:"+proposalId);
        var proposal=lazyClose(proposalId);
        if(!"OPEN".equals(proposal.get("status"))) throw new ResponseStatusException(CONFLICT,"Voting is closed for this proposal");
        if(db.queryForList("select 1 from stir.ordinary_proposal_electorate where tenant_id=? and proposal_id=? and user_id=?",ReferenceService.tenant(),proposalId,actor).isEmpty())
            throw new ResponseStatusException(FORBIDDEN,"Not eligible to vote on this proposal - the electorate was frozen when voting opened");
        if(!db.queryForList("select 1 from stir.ordinary_vote where tenant_id=? and proposal_id=? and voter_id=?",ReferenceService.tenant(),proposalId,actor).isEmpty())
            throw new ResponseStatusException(CONFLICT,"Already voted on this proposal");
        db.update("insert into stir.ordinary_vote values (?,?,?,?,?,?)",UUID.randomUUID(),ReferenceService.tenant(),proposalId,actor,choice,Timestamp.from(Instant.now()));
        return proposal(proposalId);
    }
    public List<Map<String,Object>> votes(UUID proposalId) {
        return db.queryForList("select voter_id,choice,cast_at from stir.ordinary_vote where tenant_id=? and proposal_id=? order by cast_at",ReferenceService.tenant(),proposalId);
    }
    public List<Map<String,Object>> electorate(UUID proposalId) {
        return db.queryForList("select user_id from stir.ordinary_proposal_electorate where tenant_id=? and proposal_id=? order by user_id",ReferenceService.tenant(),proposalId);
    }

    // --- Close: lazy, deterministic, reproducible tally - never an administrative override ---
    private Map<String,Object> lazyClose(UUID proposalId) {
        var proposal=one("select * from stir.ordinary_proposal where tenant_id=? and id=?",ReferenceService.tenant(),proposalId);
        if(!"OPEN".equals(proposal.get("status"))) return proposal;
        if(Instant.now().isBefore(((Timestamp)proposal.get("voting_closes_at")).toInstant())) return proposal;
        int electorateSize=db.queryForObject("select count(*) from stir.ordinary_proposal_electorate where tenant_id=? and proposal_id=?",Integer.class,ReferenceService.tenant(),proposalId);
        var tallies=db.queryForList("select choice,count(*) as n from stir.ordinary_vote where tenant_id=? and proposal_id=? group by choice",ReferenceService.tenant(),proposalId);
        long approve=0,reject=0,abstain=0;
        for(var t:tallies) { long n=((Number)t.get("n")).longValue();
            switch((String)t.get("choice")) { case "APPROVE"->approve=n; case "REJECT"->reject=n; case "ABSTAIN"->abstain=n; } }
        var policy=one("select * from stir.ordinary_governance_policy where tenant_id=? and id=?",ReferenceService.tenant(),proposal.get("governance_policy_id"));
        boolean abstainCountsTowardQuorum="COUNTS_TOWARD_QUORUM_NOT_APPROVAL".equals(policy.get("abstention_rule"));
        long participants=approve+reject+(abstainCountsTowardQuorum?abstain:0);
        int quorumNum=(int)policy.get("quorum_numerator"),quorumDen=(int)policy.get("quorum_denominator");
        boolean quorumMet=electorateSize>0 && participants*quorumDen>=(long)quorumNum*electorateSize;
        String status;
        if(!quorumMet) status="EXPIRED";
        else {
            long approvalDen=approve+reject;
            int approvalNum=(int)policy.get("approval_numerator"),approvalDenPolicy=(int)policy.get("approval_denominator");
            boolean approvalMet=approvalDen>0 && approve*approvalDenPolicy>=(long)approvalNum*approvalDen;
            status=approvalMet?"APPROVED":"REJECTED";
        }
        db.update("update stir.ordinary_proposal set status=?,closed_at=? where tenant_id=? and id=?",status,Timestamp.from(Instant.now()),ReferenceService.tenant(),proposalId);
        return one("select * from stir.ordinary_proposal where tenant_id=? and id=?",ReferenceService.tenant(),proposalId);
    }
    public Map<String,Object> close(UUID proposalId) { return proposal(proposalId); }

    /** True once the definition's reference_policy has moved on from what this proposal was frozen
     * against - status stays whatever the vote itself decided (that already happened and is not
     * rewritten); staleness is a separate, purely computed fact about whether it can still be
     * executed, never persisted as its own status transition (see execute()'s comment). */
    private boolean isStale(Map<String,Object> proposal) {
        // Only meaningful before execution: an already-EXECUTED proposal's historical action stays
        // valid forever regardless of what the policy does afterward - staleness only ever blocks a
        // future execute() call, never questions a past one.
        if(!"APPROVED".equals(proposal.get("status"))) return false;
        String current=policyStateDigest((UUID)proposal.get("definition_id"));
        return !current.equals(proposal.get("reference_state_digest"));
    }

    // --- Read ---
    public Map<String,Object> proposal(UUID proposalId) {
        var p=lazyClose(proposalId);
        var tallies=db.queryForList("select choice,count(*) as n from stir.ordinary_vote where tenant_id=? and proposal_id=? group by choice",ReferenceService.tenant(),proposalId);
        var out=new LinkedHashMap<>(p);
        out.put("electorateSize",db.queryForObject("select count(*) from stir.ordinary_proposal_electorate where tenant_id=? and proposal_id=?",Integer.class,ReferenceService.tenant(),proposalId));
        for(var t:tallies) out.put("votes"+t.get("choice"),t.get("n"));
        out.putIfAbsent("votesAPPROVE",0); out.putIfAbsent("votesREJECT",0); out.putIfAbsent("votesABSTAIN",0);
        out.put("stale",isStale(p));
        return out;
    }
    public List<Map<String,Object>> proposals(UUID community) {
        return db.queryForList("select id,proposal_type,definition_id,status,voting_opens_at,voting_closes_at,created_by,created_at from stir.ordinary_proposal "+
            "where tenant_id=? and community_id=? order by created_at desc limit 200",ReferenceService.tenant(),community);
    }

    // --- Execute: only the exact approved proposal, only once, never an administrative shortcut ---
    public Map<String,Object> execute(CurrentUser user,UUID proposalId) {
        ReferenceService.requireCommunityAuthority(user);
        lock(ReferenceService.tenant()+":ordinary-execute:"+proposalId);
        var proposal=lazyClose(proposalId);
        if(!"APPROVED".equals(proposal.get("status"))) throw new ResponseStatusException(CONFLICT,"Proposal is not approved");
        if(!db.queryForList("select 1 from stir.ordinary_proposal_execution where tenant_id=? and proposal_id=?",ReferenceService.tenant(),proposalId).isEmpty())
            throw new ResponseStatusException(CONFLICT,"Proposal already executed");
        // Deliberately does NOT persist a VOID_STALE transition here: this method runs inside a
        // real @Transactional bean in production, so any write immediately followed by this
        // exception would itself be rolled back with it - staleness is instead a live-computed
        // read (proposal()'s "stale" field, isStale() below), never a status this throw could race.
        if(isStale(proposal))
            throw new ResponseStatusException(CONFLICT,"Underlying reference policy changed since this vote opened; create a new proposal");
        Object result; String type=(String)proposal.get("proposal_type");
        if("PUBLISH_REFERENCE".equals(type)) {
            String decision="Approved by ordinary community governance vote "+proposalId+" (policy v"+proposal.get("governance_policy_version")+")";
            result=reference.publishDirect(user,(UUID)proposal.get("target_reference_proposal_id"),decision);
        } else {
            var payload=ReferenceService.parse((String)proposal.get("payload_json"));
            var r=new ReferenceController.PolicyRequest((int)payload.get("windowDays"),(int)payload.get("minimumObservations"),
                (int)payload.get("minimumParticipants"),new BigDecimal((String)payload.get("maximumParticipantShare")),
                (int)payload.get("freshnessDays"),(String)payload.get("explanation"),null,null,null,null,
                (Integer)payload.get("minimumIndependenceCoveragePercent"));
            result=reference.policyDirect(user,(UUID)proposal.get("definition_id"),r);
        }
        String resultDigest=ReferenceService.digest(ReferenceService.canonical(Map.of("result",result.toString())));
        db.update("insert into stir.ordinary_proposal_execution values (?,?,?,?,?,?)",UUID.randomUUID(),ReferenceService.tenant(),proposalId,ReferenceService.actor(user),Timestamp.from(Instant.now()),resultDigest);
        db.update("update stir.ordinary_proposal set status='EXECUTED',executed_at=? where tenant_id=? and id=?",Timestamp.from(Instant.now()),ReferenceService.tenant(),proposalId);
        return proposal(proposalId);
    }
}
