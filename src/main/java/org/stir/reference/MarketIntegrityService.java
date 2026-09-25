package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Private review record; a signal is not a finding and only FINAL affects future evidence cuts. */
@Service @Transactional
public class MarketIntegrityService {
    private final JdbcTemplate db;
    public MarketIntegrityService(JdbcTemplate db) {this.db=db;}
    public record SignalRequest(UUID observationId,String signalCode,String reason,List<String> evidenceRefs) {}
    public record DecisionRequest(String status,String reason) {}
    private Map<String,Object> one(UUID id) {
        var rows=db.queryForList("select c.*,e.status,e.actor_id as decided_by,e.reason as current_reason from stir.market_integrity_case c "+
            "join lateral (select status,actor_id,reason from stir.market_integrity_case_event where tenant_id=c.tenant_id and case_id=c.id order by sequence desc limit 1) e on true "+
            "where c.tenant_id=? and c.id=?",ReferenceService.tenant(),id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Integrity case not found");
        return rows.getFirst();
    }
    public Map<String,Object> signal(CurrentUser user,SignalRequest input) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        if(input==null || input.observationId()==null || input.signalCode()==null ||
           !Set.of("REPEATED_RELATIONSHIP","HIGH_COUNTERPARTY_CONCENTRATION","RELATED_PARTICIPANT_CLUSTER",
               "CIRCULAR_ACTIVITY","OUTLIER_PENDING_REVIEW","OTHER_EXPLAINED_SIGNAL").contains(input.signalCode()) ||
           input.reason()==null || input.reason().isBlank() || input.reason().length()>2000 || input.evidenceRefs()==null)
            throw new ResponseStatusException(BAD_REQUEST,"Explicit signal and evidence required");
        var observations=db.queryForList("select definition_id from stir.reference_observation where tenant_id=? and id=?",
            ReferenceService.tenant(),input.observationId());
        if(observations.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Observation not found");
        UUID id=UUID.randomUUID(), definition=(UUID)observations.getFirst().get("definition_id");
        db.update("insert into stir.market_integrity_case values (?,?,?,?,?,?,?,?,?)",
            id,ReferenceService.tenant(),definition,input.observationId(),input.signalCode(),input.reason(),
            ReferenceService.canonical(Map.of("refs",input.evidenceRefs())),actor,Timestamp.from(Instant.now()));
        event(id,"SIGNAL",input.reason(),actor);
        return one(id);
    }
    private void event(UUID caseId,String status,String reason,UUID actor) {
        int sequence=db.queryForObject("select coalesce(max(sequence),0)+1 from stir.market_integrity_case_event where tenant_id=? and case_id=?",
            Integer.class,ReferenceService.tenant(),caseId);
        db.update("insert into stir.market_integrity_case_event values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),
            ReferenceService.tenant(),caseId,status,reason,actor,Timestamp.from(Instant.now()),sequence);
    }
    public Map<String,Object> decide(CurrentUser user,UUID id,DecisionRequest input) {
        ReferenceService.requireCommunityAuthority(user);
        UUID actor=ReferenceService.actor(user);
        db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",ReferenceService.tenant()+":integrity:"+id);
        var c=one(id);
        String status=input.status();
        if(!Set.of("UNDER_REVIEW","FINAL","DISMISSED").contains(status) || input.reason()==null ||
           input.reason().isBlank() || input.reason().length()>2000)
            throw new ResponseStatusException(BAD_REQUEST,"Invalid review decision");
        String current=(String)c.get("status");
        if(!(("SIGNAL".equals(current) && "UNDER_REVIEW".equals(status)) ||
             ("UNDER_REVIEW".equals(current) && Set.of("FINAL","DISMISSED").contains(status))))
            throw new ResponseStatusException(CONFLICT,"Invalid case transition");
        if(Set.of("FINAL","DISMISSED").contains(status) && actor.equals(c.get("created_by")))
            throw new ResponseStatusException(CONFLICT,"Signal originator cannot decide own case");
        event(id,status,input.reason(),actor);
        return one(id);
    }
    public List<Map<String,Object>> cases(UUID definition) {
        return db.queryForList("select c.id,c.observation_id,c.signal_code,e.status,c.reason,c.evidence_refs_json,c.created_by,e.actor_id as decided_by,c.created_at "+
            "from stir.market_integrity_case c join lateral (select status,actor_id from stir.market_integrity_case_event where tenant_id=c.tenant_id and case_id=c.id order by sequence desc limit 1) e on true "+
            "where c.tenant_id=? and c.definition_id=? order by c.created_at desc limit 100",
            ReferenceService.tenant(),definition);
    }
    public List<Map<String,Object>> history(UUID caseId) {
        one(caseId);
        return db.queryForList("select sequence,status,reason,actor_id,recorded_at from stir.market_integrity_case_event where tenant_id=? and case_id=? order by sequence",
            ReferenceService.tenant(),caseId);
    }
}
