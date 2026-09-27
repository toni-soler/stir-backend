package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Versioned, community-scoped retention policy for reference_observation, plus the one real
 * lifecycle transition it can trigger: anonymization. Deliberately not a legal/compliance engine -
 * only what STIR's own observation lifecycle needs (CONSENT_RETENTION.md). Distinguishes three
 * states that must never collapse into one: NOT_ELIGIBLE_FOR_NEW_USE (a consent/eligibility
 * question, decided in ReferenceService/EvidenceAnalysis) is a different axis entirely from
 * MUST_BE_RETAINED (a hold, because market integrity evidence still needs the identifiers) and
 * ELIGIBLE_FOR_ANONYMIZATION/ANONYMIZED (this service's own states). An observation can be
 * simultaneously NOT_ELIGIBLE_FOR_NEW_USE (say, consent withdrawn) and MUST_BE_RETAINED (an open
 * integrity case) - retention never deletes what integrity still needs, and consent withdrawal
 * never deletes anything by itself. */
@Service @Transactional
public class RetentionService {
    /** Code-level floor, not a Seven Keys constitutional field: retention_period_days can never go
     * below this, full stop, the same fail-closed style as REPLACE_CONTROLLER - deliberately not
     * wired into the sacred constitution schema (CONSENT_RETENTION.md explains why). */
    public static final int MINIMUM_RETENTION_PERIOD_DAYS = 90;
    private static final String BASIS = "COMMUNITY_REFERENCE_AND_INTEGRITY_HISTORY";
    private final JdbcTemplate db;
    private final ReferenceService references;
    public RetentionService(JdbcTemplate db, ReferenceService references) { this.db=db; this.references=references; }
    private static UUID tenant() { return ReferenceService.tenant(); }
    private static UUID actor(CurrentUser user) { return ReferenceService.actor(user); }
    private Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args); if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Not found"); return rows.getFirst();
    }
    public record PolicyRequest(int retentionPeriodDays, String explanation) {}

    public Map<String,Object> currentPolicy(UUID communityId) {
        var rows=db.queryForList("select * from stir.retention_policy where tenant_id=? and community_id=? order by version desc limit 1",tenant(),communityId);
        if(rows.isEmpty()) { var out=new LinkedHashMap<String,Object>(); out.put("version",0); out.put("retention_period_days",MINIMUM_RETENTION_PERIOD_DAYS);
            out.put("basis",BASIS); out.put("explanation","No explicit policy set yet; using the floor default."); return out; }
        return rows.getFirst();
    }
    public Map<String,Object> setPolicy(CurrentUser user,UUID communityId,PolicyRequest r) {
        ReferenceService.requireCommunityAuthority(user);
        references.requireDirectMutationAllowed(communityId);
        return setPolicyDirect(user,communityId,r);
    }
    /** Bypasses the ordinary-governance gate - only for OrdinaryGovernanceService.execute()'s
     * approved-proposal path (re-verified there). Never call from an ordinary controller entry point. */
    Map<String,Object> setPolicyDirect(CurrentUser user,UUID communityId,PolicyRequest r) {
        if(r.retentionPeriodDays()<MINIMUM_RETENTION_PERIOD_DAYS)
            throw new ResponseStatusException(CONFLICT,"Retention period below the floor of "+MINIMUM_RETENTION_PERIOD_DAYS+" days");
        int version=db.queryForObject("select coalesce(max(version),0)+1 from stir.retention_policy where tenant_id=? and community_id=?",Integer.class,tenant(),communityId);
        UUID id=UUID.randomUUID();
        db.update("insert into stir.retention_policy values (?,?,?,?,?,?,?,?,?)",id,tenant(),communityId,version,r.retentionPeriodDays(),BASIS,r.explanation(),actor(user),Timestamp.from(Instant.now()));
        return one("select * from stir.retention_policy where tenant_id=? and id=?",tenant(),id);
    }
    private int periodDays(Map<String,Object> policy) { return ((Number)policy.get("retention_period_days")).intValue(); }

    /** Live-computed, never persisted as a status column - same "compute on read" convention as
     * OrdinaryGovernanceService.lazyClose()/isStale(). hold=true means MUST_BE_RETAINED regardless
     * of how much time has passed; anonymizedAt!=null means the transition already happened. */
    public record RetentionStatus(UUID observationId,String status,Instant retainUntil,boolean hold,String holdReason,Instant anonymizedAt) {}

    private RetentionStatus statusFor(UUID communityId,Map<String,Object> observation) {
        UUID obsId=(UUID)observation.get("id");
        Instant observedAt=((Timestamp)observation.get("observed_at")).toInstant();
        Timestamp anonymizedAtRow=(Timestamp)observation.get("anonymized_at");
        if(anonymizedAtRow!=null) return new RetentionStatus(obsId,"ANONYMIZED",null,false,null,anonymizedAtRow.toInstant());
        boolean hasCase=!db.queryForList("select 1 from stir.market_integrity_case where tenant_id=? and observation_id=?",tenant(),obsId).isEmpty();
        Instant retainUntil=observedAt.plus(Duration.ofDays(periodDays(currentPolicy(communityId))));
        if(hasCase) return new RetentionStatus(obsId,"MUST_BE_RETAINED",retainUntil,true,"MARKET_INTEGRITY_CASE_LINKED",null);
        if(Instant.now().isBefore(retainUntil)) return new RetentionStatus(obsId,"RETAINED",retainUntil,false,null,null);
        return new RetentionStatus(obsId,"ELIGIBLE_FOR_ANONYMIZATION",retainUntil,false,null,null);
    }
    public RetentionStatus statusFor(UUID observationId) {
        var obs=one("select * from stir.reference_observation where tenant_id=? and id=?",tenant(),observationId);
        var communityId=(UUID)references.definition((UUID)obs.get("definition_id")).get("community_id");
        return statusFor(communityId,obs);
    }
    /** Publisher-facing review list, definition-scoped like ReferenceService.observations(): every
     * observation whose retention window has elapsed and which currently has no market-integrity
     * hold - i.e., what anonymize() would actually accept right now. Listing never anonymizes by
     * itself; nothing here is silent or automatic. */
    public List<Map<String,Object>> dueForAnonymization(UUID definitionId) {
        var definition=references.definition(definitionId);
        UUID communityId=(UUID)definition.get("community_id");
        var observations=db.queryForList("select * from stir.reference_observation where tenant_id=? and definition_id=? and anonymized_at is null order by observed_at",tenant(),definitionId);
        var out=new ArrayList<Map<String,Object>>();
        for(var o:observations) {
            var status=statusFor(communityId,o);
            if("ELIGIBLE_FOR_ANONYMIZATION".equals(status.status()))
                out.add(Map.of("observationId",o.get("id"),"source",o.get("source"),"observedAt",o.get("observed_at"),"retainUntil",status.retainUntil().toString()));
        }
        return out;
    }
    /** The one real, explicit, audited deletion-lifecycle action: removes participant identifiers
     * from an observation that has genuinely finished its retention window and carries no
     * market-integrity hold. Never automatic, never silent, never reversible - and it never touches
     * any other column, any Agreement, or any already-cached reference_snapshot. */
    public Map<String,Object> anonymize(CurrentUser user,UUID observationId,String reason) {
        ReferenceService.requireCommunityAuthority(user);
        var obs=one("select * from stir.reference_observation where tenant_id=? and id=?",tenant(),observationId);
        var definition=references.definition((UUID)obs.get("definition_id"));
        references.requireDirectMutationAllowed((UUID)definition.get("community_id"));
        var status=statusFor((UUID)definition.get("community_id"),obs);
        if(!"ELIGIBLE_FOR_ANONYMIZATION".equals(status.status()))
            throw new ResponseStatusException(CONFLICT,"Not eligible for anonymization: "+status.status()+(status.hold()?" ("+status.holdReason()+")":""));
        Instant now=Instant.now(); UUID actorId=actor(user);
        int updated=db.update("update stir.reference_observation set participant_a=null,participant_b=null,anonymized_at=?,anonymized_by=? where tenant_id=? and id=? and anonymized_at is null",
            Timestamp.from(now),actorId,tenant(),observationId);
        if(updated==0) throw new ResponseStatusException(CONFLICT,"Already anonymized");
        db.update("insert into stir.retention_lifecycle_event values (?,?,?,?,?,?,?)",UUID.randomUUID(),tenant(),observationId,"ANONYMIZED",actorId,reason,Timestamp.from(now));
        return one("select * from stir.reference_observation where tenant_id=? and id=?",tenant(),observationId);
    }
}
