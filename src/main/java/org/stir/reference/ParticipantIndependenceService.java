package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stir.economic.OstrisClient;
import org.stir.economic.StirOstrisException;

/**
 * osTRIS stays the sole authority for identity/continuity (GOVERNANCE_CAPTURE_THREAT_MODEL.md,
 * PARTICIPANT_INDEPENDENCE.md). This is a minimal STIR-side projection: a publisher can only
 * TRIGGER a refresh, never author or alter its result - refresh() persists exactly, and only, what
 * osTRIS's own protocol response says, idempotently and monotonically keyed on osTRIS's own
 * community_sequence. No raw osTRIS risk_subject_id is ever stored, only a one-way digest
 * (clusterRef) - just enough to recognize two participants share a cluster, not enough to
 * reconstruct osTRIS's own correlation key from a copy of this table alone.
 * ReferenceService.snapshot() never calls osTRIS live - it only reads what refresh() already
 * persisted, via forUsers() below.
 */
@Service @Transactional
public class ParticipantIndependenceService {
    private final JdbcTemplate db;
    private final OstrisClient ostris;
    public ParticipantIndependenceService(JdbcTemplate db, OstrisClient ostris) { this.db=db; this.ostris=ostris; }

    public record Info(String status, String clusterRef, Long communitySequence, Instant fetchedAt) {}

    /** Triggered explicitly by a community-authorized publisher; the publisher decides WHEN to ask
     * osTRIS again, never WHAT the answer is. */
    public Map<String,Object> refresh(CurrentUser user, UUID targetUserId) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user), tenant=ReferenceService.tenant();
        var communityRows=db.queryForList("select community_id from stir.marketplace_economic_binding where tenant_id=?",tenant);
        if(communityRows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND,"No economic binding for this tenant");
        UUID community=(UUID)communityRows.getFirst().get("community_id");
        var binding=db.queryForList("select participant_id from stir.participant_economic_binding where tenant_id=? and user_id=?",tenant,targetUserId);
        if(binding.isEmpty()) { store(tenant,targetUserId,community,"NO_OSTRIS_BINDING",null,null,actor); return view(tenant,targetUserId); }
        UUID participantId=(UUID)binding.getFirst().get("participant_id");
        try {
            var decision=ostris.privateContinuity(community,participantId);
            if("CONFIRMED".equals(decision.status()) && decision.riskSubjectId()!=null)
                store(tenant,targetUserId,community,"RELATED_CONTINUITY",clusterRef(tenant,community,decision.riskSubjectId()),decision.communitySequence(),actor);
            else if("CONTESTED".equals(decision.status()))
                store(tenant,targetUserId,community,"IDENTITY_CONTINUITY_PENDING",null,decision.communitySequence(),actor);
            else // REJECTED: osTRIS never affirmatively certifies independence, only ever confirms/contests/rejects
                 // one specific relatedness claim - a REJECTED claim is not an independence certificate either.
                store(tenant,targetUserId,community,"INDEPENDENCE_UNKNOWN",null,decision.communitySequence(),actor);
        } catch(StirOstrisException e) {
            if(!"CONTINUITY_NOT_FOUND".equals(e.code)) throw e;
            store(tenant,targetUserId,community,"INDEPENDENCE_UNKNOWN",null,null,actor);
        }
        return view(tenant,targetUserId);
    }
    static String clusterRef(UUID tenant,UUID community,UUID riskSubjectId) {
        return ReferenceService.digest(tenant+":"+community+":"+riskSubjectId);
    }
    private void store(UUID tenant,UUID userId,UUID community,String status,String clusterRef,Long sequence,UUID actor) {
        db.update("insert into stir.participant_independence_projection(tenant_id,user_id,community_id,status,cluster_ref,community_sequence,fetched_at,fetched_by) "+
            "values (?,?,?,?,?,?,?,?) on conflict (tenant_id,user_id) do update set community_id=excluded.community_id,status=excluded.status,"+
            "cluster_ref=excluded.cluster_ref,community_sequence=excluded.community_sequence,fetched_at=excluded.fetched_at,fetched_by=excluded.fetched_by "+
            "where excluded.community_sequence is null or stir.participant_independence_projection.community_sequence is null "+
            "or excluded.community_sequence >= stir.participant_independence_projection.community_sequence",
            tenant,userId,community,status,clusterRef,sequence,Timestamp.from(Instant.now()),actor);
    }
    public Map<String,Object> view(UUID tenant,UUID userId) {
        var rows=db.queryForList("select status,cluster_ref,community_sequence,fetched_at from stir.participant_independence_projection where tenant_id=? and user_id=?",tenant,userId);
        if(rows.isEmpty()) return Map.of("userId",userId,"status","NOT_ASSESSED");
        var r=rows.getFirst();
        return Map.of("userId",userId,"status",r.get("status"),"communitySequence",r.get("community_sequence")==null?"":r.get("community_sequence"),
            "fetchedAt",r.get("fetched_at").toString());
    }
    /** Reads only - snapshot() must never call osTRIS live. maxAgeDays comes from the reference's own
     * ReferencePolicy.freshnessDays; a row older than that is treated as absent (INDEPENDENCE_UNKNOWN
     * upstream), never silently trusted past its own freshness policy. */
    Map<UUID,Info> forUsers(UUID tenant,Collection<UUID> userIds,int maxAgeDays,Instant now) {
        var distinct=new ArrayList<>(new LinkedHashSet<>(userIds));
        if(distinct.isEmpty()) return Map.of();
        Instant floor=now.minus(Duration.ofDays(maxAgeDays));
        String placeholders=String.join(",",Collections.nCopies(distinct.size(),"?"));
        var args=new ArrayList<Object>(); args.add(tenant); args.addAll(distinct); args.add(Timestamp.from(floor));
        var rows=db.queryForList("select user_id,status,cluster_ref,community_sequence,fetched_at from stir.participant_independence_projection "+
            "where tenant_id=? and user_id in ("+placeholders+") and fetched_at>=?",args.toArray());
        var out=new HashMap<UUID,Info>();
        for(var r:rows) out.put((UUID)r.get("user_id"),new Info((String)r.get("status"),(String)r.get("cluster_ref"),
            (Long)r.get("community_sequence"),((Timestamp)r.get("fetched_at")).toInstant()));
        return out;
    }
}
