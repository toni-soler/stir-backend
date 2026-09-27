package org.stir.reference;

import es.idynamicsax.idax.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Purpose-specific consent for one party's contribution of one Agreement-sourced observation to
 * Community Value Reference evidence. Grant/decline is captured automatically at Agreement
 * acceptance time (the pre-existing bilateral shareReferenceObservation flow) - the only new,
 * human-triggered action here is withdrawal, which a party performs on their own consent, never on
 * the other party's, and never on the Agreement/observation/snapshot history itself
 * (CONSENT_RETENTION.md). No generic "accept everything" consent: purpose is explicit, and today
 * there is exactly one real purpose. */
@Service @Transactional
public class ConsentService {
    /** Bump only when the consent notice's actual wording/meaning changes materially - this is
     * what a party is recorded as having been shown, not a schema version. */
    public static final int NOTICE_VERSION = 1;
    static final String PURPOSE = "REFERENCE_EVIDENCE_CONTRIBUTION";
    private final JdbcTemplate db;
    public ConsentService(JdbcTemplate db) { this.db=db; }

    /** Called only from ReferenceAcceptanceAdapter, inside the same transaction as
     * ReferenceService.record()/freezeContext(). Idempotent: replaying acceptance for the same
     * observation/party (should never happen in practice - accept() is one-shot per negotiation -
     * but is not assumed) returns the same consent id and never records a second initial event. */
    UUID capture(UUID observationId,UUID definitionId,UUID partyUserId,boolean granted) {
        if(observationId==null) return null;
        UUID tenant=ReferenceService.tenant();
        UUID id=UUID.randomUUID();
        // DO NOTHING (never DO UPDATE) - reference_consent only grants SELECT,INSERT; a lookup on
        // conflict needs no privilege beyond that.
        int inserted=db.update("insert into stir.reference_consent "+
            "(id,tenant_id,observation_id,definition_id,party_user_id,purpose,notice_version,created_at) values (?,?,?,?,?,?,?,?) "+
            "on conflict (tenant_id,observation_id,party_user_id,purpose) do nothing",
            id,tenant,observationId,definitionId,partyUserId,PURPOSE,NOTICE_VERSION,Timestamp.from(Instant.now()));
        UUID consentId=inserted>0?id:db.queryForObject(
            "select id from stir.reference_consent where tenant_id=? and observation_id=? and party_user_id=? and purpose=?",
            UUID.class,tenant,observationId,partyUserId,PURPOSE);
        boolean hasEvent=!db.queryForList("select 1 from stir.reference_consent_event where tenant_id=? and consent_id=?",tenant,consentId).isEmpty();
        if(!hasEvent) db.update("insert into stir.reference_consent_event values (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),tenant,consentId,1,granted?"GRANT":"DECLINE",partyUserId,null,Timestamp.from(Instant.now()));
        return consentId;
    }
    private Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args); if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Consent record not found"); return rows.getFirst();
    }
    private String latestAction(UUID tenant,UUID consentId) {
        var rows=db.queryForList("select action from stir.reference_consent_event where tenant_id=? and consent_id=? order by sequence desc limit 1",tenant,consentId);
        return rows.isEmpty()?null:(String)rows.getFirst().get("action");
    }
    /** Every consent record belonging to the caller, across every observation they were ever a
     * party to - the "which of my past contributions are active, declined, or withdrawn" view. */
    public List<Map<String,Object>> mine(CurrentUser user) {
        UUID tenant=ReferenceService.tenant(), actor=ReferenceService.actor(user);
        return db.queryForList("select c.id,c.observation_id,c.definition_id,c.purpose,c.notice_version,c.created_at, "+
            "d.name as definition_name, o.observed_at, o.source, "+
            "(select action from stir.reference_consent_event where tenant_id=c.tenant_id and consent_id=c.id order by sequence desc limit 1) as status "+
            "from stir.reference_consent c "+
            "join stir.reference_definition d on d.tenant_id=c.tenant_id and d.id=c.definition_id "+
            "join stir.reference_observation o on o.tenant_id=c.tenant_id and o.id=c.observation_id "+
            "where c.tenant_id=? and c.party_user_id=? order by c.created_at desc limit 200",tenant,actor);
    }
    public Map<String,Object> view(CurrentUser user,UUID consentId) {
        UUID tenant=ReferenceService.tenant(), actor=ReferenceService.actor(user);
        var row=one("select * from stir.reference_consent where tenant_id=? and id=?",tenant,consentId);
        if(!actor.equals(row.get("party_user_id"))) throw new AccessDeniedException("Not your consent record");
        var out=new LinkedHashMap<>(row); out.put("status",latestAction(tenant,consentId)); return out;
    }
    /** Withdrawal is a personal right of the consenting party, never gated on community/publisher
     * authority - but it can only ever affect future eligibility, never the Agreement, the
     * observation row, or any already-cached reference_snapshot (ReferenceService.snapshot()'s
     * own daily cache is the immutability boundary, not this method). Idempotent and safe under
     * concurrent/replayed calls: an advisory lock serializes them, and an already-WITHDRAWN
     * consent returns its current state rather than recording a duplicate event. */
    public Map<String,Object> withdraw(CurrentUser user,UUID consentId,String reason) {
        UUID tenant=ReferenceService.tenant(), actor=ReferenceService.actor(user);
        var row=one("select * from stir.reference_consent where tenant_id=? and id=?",tenant,consentId);
        if(!actor.equals(row.get("party_user_id"))) throw new AccessDeniedException("Only the consenting party may withdraw their own consent");
        db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",tenant+":consent:"+consentId);
        String status=latestAction(tenant,consentId);
        if("WITHDRAW".equals(status)) return view(user,consentId);
        if("DECLINE".equals(status)) throw new ResponseStatusException(CONFLICT,"Consent was declined, not granted; nothing to withdraw");
        int nextSeq=db.queryForObject("select coalesce(max(sequence),0)+1 from stir.reference_consent_event where tenant_id=? and consent_id=?",Integer.class,tenant,consentId);
        db.update("insert into stir.reference_consent_event values (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),tenant,consentId,nextSeq,"WITHDRAW",actor,reason,Timestamp.from(Instant.now()));
        return view(user,consentId);
    }
}
