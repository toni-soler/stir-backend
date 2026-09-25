package org.stir.reference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.stir.negotiation.CanonicalJson;
import static org.springframework.http.HttpStatus.*;

/** Isolated local bounded context. Only its explicit acceptance adapter knows marketplace entities. */
@Service @Transactional
public class ReferenceService {
    private final JdbcTemplate db;
    private static final ObjectMapper JSON=new ObjectMapper();
    public ReferenceService(JdbcTemplate db) { this.db=db; }
    static UUID tenant() {
        var c=TenantContext.get(); if(c==null || c.getTenantId()==null) throw new AccessDeniedException("Tenant required");
        return c.getTenantId();
    }
    static UUID actor(CurrentUser user) {
        if(user==null || user.isService() || user.getUserId()==null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }
    /** PLATFORM ADMINISTRATION MUST NEVER BE INTERPRETED AS COMMUNITY GOVERNANCE AUTHORITY
     * (GOVERNANCE_CAPTURE_THREAT_MODEL.md). idax-core's PermissionService grants every stir.*
     * permission string to a platform superuser unconditionally, so @PreAuthorize alone cannot
     * be the boundary for a community-governed mutation. This is that boundary: the single,
     * reused check every sensitive mutation in this bounded context (a reference definition, its
     * policy, a proposal, a publication, a market integrity signal/decision) must pass, regardless
     * of which controller or future caller reaches it. A platform SuperAdmin has no constitutional
     * seat, no Guardian role and no community role of their own - being SuperAdmin never
     * substitutes for one. Ordinary public/community reads do not call this; only mutation
     * authority is gated here, not all of STIR. */
    static void requireCommunityAuthority(CurrentUser user) {
        actor(user);
        if(user.isSuperuser()) throw new AccessDeniedException("Platform administration does not grant community governance authority");
    }
    static Map<String,Object> parse(String json) {
        try { return JSON.readValue(json,new TypeReference<Map<String,Object>>(){}); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored reference JSON",e); }
    }
    static String canonical(Map<String,Object> value) { return new String(CanonicalJson.canonicalBytes(value),StandardCharsets.UTF_8); }
    static String digest(String value) { return CanonicalJson.sha256Hex(value.getBytes(StandardCharsets.UTF_8)); }
    private Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args); if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Reference not found"); return rows.getFirst();
    }
    public List<Map<String,Object>> definitions() {
        return db.queryForList("select * from stir.reference_definition where tenant_id=? order by name,id limit 200",tenant());
    }
    public Map<String,Object> binding() {
        var row=one("select community_id from stir.marketplace_economic_binding where tenant_id=?",tenant());
        return Map.of("communityId",row.get("community_id"));
    }
    public Map<String,Object> definition(UUID id) {
        return one("select * from stir.reference_definition where tenant_id=? and id=?",tenant(),id);
    }
    private void lock(UUID id) {
        definition(id);
        db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",tenant()+":"+id);
    }
    private Map<String,Object> constitution(UUID community) {
        var rows=db.queryForList("select version,canonical_json,digest_sha256 from stir.market_constitution where tenant_id=? and community_id=? order by version desc limit 1",tenant(),community);
        if(rows.isEmpty()) return Map.of("version",0,"canonical_json",canonical(SevenKeysService.initialConstitution()),
            "digest_sha256",digest(canonical(SevenKeysService.initialConstitution())));
        return rows.getFirst();
    }
    public Map<String,Object> create(CurrentUser user, ReferenceController.DefinitionRequest r) {
        requireCommunityAuthority(user);
        var binding=one("select community_id,unit_id from stir.marketplace_economic_binding where tenant_id=?",tenant());
        UUID id=UUID.randomUUID();
        // Explicit existing community/unit binding, never tenant identity or a fiat conversion.
        db.update("insert into stir.reference_definition values (?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant(),binding.get("community_id"),binding.get("unit_id"),
            r.name().trim(),r.scope().trim(),canonical(new TreeMap<>(r.attributes())),r.quantityBasis(),r.quantityUnit().trim(),
            binding.get("unit_id").toString(),actor(user),Timestamp.from(Instant.now()));
        var constitution=constitution((UUID)binding.get("community_id"));
        var bounds=parse((String)constitution.get("canonical_json"));
        policy(user,id,new ReferenceController.PolicyRequest(90,
            Math.max(5,((Number)bounds.get("minimumObservationFloor")).intValue()),
            Math.max(6,((Number)bounds.get("minimumParticipantFloor")).intValue()),
            new BigDecimal("0.40"),30,"Initial conservative agreement-only policy"));
        return definition(id);
    }
    public Map<String,Object> policy(CurrentUser user,UUID id,ReferenceController.PolicyRequest r) {
        requireCommunityAuthority(user);
        lock(id); if(r.freshnessDays()>r.windowDays()) throw new ResponseStatusException(BAD_REQUEST,"Freshness exceeds window");
        if(r.independenceChecksRequired()!=null || r.concentrationChecksRequired()!=null ||
           r.provenanceRequired()!=null || r.forceReference()!=null)
            throw new ResponseStatusException(CONFLICT,"Protected mutations require constitutional authority");
        var d=definition(id); var bounds=parse((String)constitution((UUID)d.get("community_id")).get("canonical_json"));
        if(r.minimumObservations()<((Number)bounds.get("minimumObservationFloor")).intValue() ||
           r.minimumParticipants()<((Number)bounds.get("minimumParticipantFloor")).intValue() ||
           r.maximumParticipantShare().compareTo(new BigDecimal((String)bounds.get("maximumParticipantShareCeiling")))>0)
            throw new ResponseStatusException(CONFLICT,"Operational policy crosses constitutional bounds");
        int version=db.queryForObject("select coalesce(max(version),0)+1 from stir.reference_policy where tenant_id=? and definition_id=?",Integer.class,tenant(),id);
        UUID policy=UUID.randomUUID();
        db.update("insert into stir.reference_policy values (?,?,?,?,?,?,?,?,?,?,?,?)",policy,tenant(),id,version,r.windowDays(),r.minimumObservations(),r.minimumParticipants(),r.maximumParticipantShare(),r.freshnessDays(),r.explanation(),actor(user),Timestamp.from(Instant.now()));
        return one("select * from stir.reference_policy where tenant_id=? and id=?",tenant(),policy);
    }
    public Map<String,Object> currentPolicy(UUID id) {
        definition(id); return one("select * from stir.reference_policy where tenant_id=? and definition_id=? order by version desc limit 1",tenant(),id);
    }
    public Map<String,Object> snapshot(UUID id) {
        lock(id); var d=definition(id); var p=currentPolicy(id);
        // Policy changes take effect at the next UTC cut; no caller-controlled subcohort/time filters.
        Instant cutoff=Instant.now().truncatedTo(ChronoUnit.DAYS);
        var existing=db.queryForList("select * from stir.reference_snapshot where tenant_id=? and definition_id=? and cutoff=? order by created_at limit 1",tenant(),id,Timestamp.from(cutoff));
        if(!existing.isEmpty()) return publicSnapshot(existing.getFirst());
        var observations=db.query("select * from stir.reference_observation where tenant_id=? and definition_id=? and observed_at < ? order by id",
            (rs,n)->new EvidenceAnalysis.Observation(rs.getObject("id",UUID.class),rs.getString("source"),rs.getObject("participant_a",UUID.class),rs.getObject("participant_b",UUID.class),
                rs.getBigDecimal("amount"),rs.getBigDecimal("quantity"),rs.getString("quantity_unit"),rs.getString("unit_ref"),rs.getBoolean("aggregate_consent"),rs.getTimestamp("observed_at").toInstant()),tenant(),id,Timestamp.from(cutoff));
        var constitutional=constitution((UUID)d.get("community_id"));
        var bounds=parse((String)constitutional.get("canonical_json"));
        var policy=new EvidenceAnalysis.Policy((int)p.get("window_days"),(int)p.get("minimum_observations"),(int)p.get("minimum_participants"),
            (BigDecimal)p.get("maximum_participant_share"),(int)p.get("freshness_days"),3,new BigDecimal("0.50"),
            Boolean.TRUE.equals(bounds.get("independenceChecksRequired")),Boolean.TRUE.equals(bounds.get("concentrationChecksRequired")));
        // Only FINAL case events before the daily cutoff can change eligibility; raw Agreement remains untouched.
        var finalExclusions=new HashMap<UUID,String>();
        var cases=db.queryForList("select c.observation_id,c.signal_code,e.status from stir.market_integrity_case c join lateral ("+
            "select status from stir.market_integrity_case_event where tenant_id=c.tenant_id and case_id=c.id and recorded_at < ? order by sequence desc limit 1"+
            ") e on true where c.tenant_id=? and c.definition_id=?",Timestamp.from(cutoff),tenant(),id);
        for(var item:cases) if("FINAL".equals(item.get("status")))
            finalExclusions.put((UUID)item.get("observation_id"),"FINAL_INTEGRITY_FINDING:"+item.get("signal_code"));
        var result=EvidenceAnalysis.analyze(observations,policy,(BigDecimal)d.get("quantity_basis"),(String)d.get("quantity_unit"),(String)d.get("unit_ref"),cutoff,finalExclusions);
        var summary=new LinkedHashMap<>(result.summary()); UUID snapshotId=UUID.randomUUID();
        summary.put("id",snapshotId.toString()); summary.put("definitionId",id.toString()); summary.put("policyId",p.get("id").toString());
        summary.put("policyVersion",p.get("version")); summary.put("format","STIR-REFERENCE-EVIDENCE-JCS-1");
        summary.put("minimumObservations",policy.minimumObservations()); summary.put("minimumParticipants",policy.minimumParticipants());
        summary.put("maximumAllowedParticipantShare",policy.maximumParticipantShare().toPlainString());
        summary.put("constitutionVersion",constitutional.get("version"));
        summary.put("constitutionDigest",constitutional.get("digest_sha256"));
        String json=canonical(summary);
        // Private membership/exclusion evidence is never a community API response.
        String evidence=canonical(Map.of("included",result.included(),"exclusions",result.exclusions()));
        db.update("insert into stir.reference_snapshot values (?,?,?,?,?,?,?,?,?)",snapshotId,tenant(),id,p.get("id"),Timestamp.from(cutoff),json,digest(json),evidence,Timestamp.from(Instant.now()));
        return publicSnapshot(one("select * from stir.reference_snapshot where tenant_id=? and id=?",tenant(),snapshotId));
    }
    private Map<String,Object> publicSnapshot(Map<String,Object> row) {
        var result=parse((String)row.get("canonical_json")); result.put("digestSha256",row.get("digest_sha256")); return result;
    }
    /** Private, publisher-only view: raw observations with their current integrity case status.
     * Never exposed through the public snapshot - this is the only place STIR shows an observation's
     * own participants/amount, gated behind stir.references.publish precisely because of that. */
    public List<Map<String,Object>> observations(UUID id) {
        definition(id);
        return db.queryForList("select o.id,o.source,o.source_id,o.participant_a,o.participant_b,o.amount,o.quantity,"+
            "o.quantity_unit,o.unit_ref,o.aggregate_consent,o.observed_at,c.id as case_id,e.status as case_status,c.signal_code "+
            "from stir.reference_observation o "+
            "left join lateral (select id,signal_code from stir.market_integrity_case where tenant_id=o.tenant_id and observation_id=o.id order by created_at desc limit 1) c on true "+
            "left join lateral (select status from stir.market_integrity_case_event where tenant_id=o.tenant_id and case_id=c.id order by sequence desc limit 1) e on true "+
            "where o.tenant_id=? and o.definition_id=? order by o.observed_at desc limit 200",tenant(),id);
    }
    /** Private, publisher-only reconstruction of today's snapshot: which raw observations were
     * REFERENCE-ELIGIBLE vs EXCLUDED, and the exact reason code for each exclusion. Ensures today's
     * snapshot exists first (same idempotent daily-cutoff cache as snapshot()/propose()). */
    public Map<String,Object> evidenceManifest(UUID id) {
        var current=snapshot(id);
        var row=one("select evidence_json from stir.reference_snapshot where tenant_id=? and id=?",tenant(),UUID.fromString((String)current.get("id")));
        var manifest=parse((String)row.get("evidence_json"));
        manifest.put("snapshotId",current.get("id"));
        return manifest;
    }
    public Map<String,Object> propose(CurrentUser user, UUID id, ReferenceController.ProposalRequest r) {
        requireCommunityAuthority(user);
        definition(id);
        boolean qualitative="QUALITATIVE".equals(r.kind());
        if(qualitative ? r.lowerValue()!=null || r.upperValue()!=null : r.lowerValue()==null || r.upperValue()==null || r.lowerValue().compareTo(r.upperValue())>0)
            throw new ResponseStatusException(BAD_REQUEST,"Invalid reference range");
        if("VALUE".equals(r.kind()) && r.lowerValue().compareTo(r.upperValue())!=0) throw new ResponseStatusException(BAD_REQUEST,"Value requires equal bounds");
        var snapshot=snapshot(id);
        if(Set.of("VALUE","BAND").contains(r.kind()) && !"SUFFICIENT_DATA".equals(snapshot.get("status")))
            throw new ResponseStatusException(CONFLICT,"Use an explicit convention or qualitative reference with insufficient data");
        UUID proposal=UUID.randomUUID();
        db.update("insert into stir.reference_proposal values (?,?,?,?,?,?,?,?,?,?,?,?)",proposal,tenant(),id,UUID.fromString((String)snapshot.get("id")),r.kind(),r.lowerValue(),r.upperValue(),r.explanation(),r.origin(),r.validDays(),actor(user),Timestamp.from(Instant.now()));
        return one("select * from stir.reference_proposal where tenant_id=? and id=?",tenant(),proposal);
    }
    public Map<String,Object> publish(CurrentUser user,UUID proposal,String decision) {
        requireCommunityAuthority(user);
        var p=one("select * from stir.reference_proposal where tenant_id=? and id=?",tenant(),proposal);
        UUID id=(UUID)p.get("definition_id"); lock(id);
        var prior=db.queryForList("select id from stir.community_reference where tenant_id=? and proposal_id=?",tenant(),proposal);
        if(!prior.isEmpty()) throw new ResponseStatusException(CONFLICT,"Proposal already published");
        // Evidence-based publication must still satisfy today's evidence, not just its proposal date.
        if(Set.of("VALUE","BAND").contains(p.get("kind"))) {
            var latest=snapshot(id);
            if(!"SUFFICIENT_DATA".equals(latest.get("status")) || !p.get("snapshot_id").toString().equals(latest.get("id")))
                throw new ResponseStatusException(CONFLICT,"Evidence changed; create a new proposal");
        }
        int version=db.queryForObject("select coalesce(max(version),0)+1 from stir.community_reference where tenant_id=? and definition_id=?",Integer.class,tenant(),id);
        Instant now=Instant.now(); UUID ref=UUID.randomUUID();
        db.update("insert into stir.community_reference values (?,?,?,?,?,?,?,?,?)",ref,tenant(),id,proposal,version,actor(user),decision,Timestamp.from(now),Timestamp.from(now.plus(Duration.ofDays((int)p.get("valid_days")))));
        return current(id);
    }
    public List<Map<String,Object>> history(UUID id) {
        definition(id); return db.queryForList("select r.*,p.kind,p.lower_value,p.upper_value,p.explanation,p.origin,p.snapshot_id from stir.community_reference r join stir.reference_proposal p on p.tenant_id=r.tenant_id and p.id=r.proposal_id where r.tenant_id=? and r.definition_id=? order by r.version desc",tenant(),id);
    }
    public List<Map<String,Object>> proposals(UUID id) {
        definition(id); return db.queryForList("select p.* from stir.reference_proposal p where tenant_id=? and definition_id=? order by proposed_at desc limit 100",tenant(),id);
    }
    public Map<String,Object> current(UUID id) {
        var history=history(id); if(history.isEmpty()) return null;
        var latest=history.getFirst();
        return ((Timestamp)latest.get("valid_until")).toInstant().isAfter(Instant.now())?latest:null;
    }
    public Map<String,Object> view(UUID id) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("definition",definition(id)); result.put("reference",current(id));
        result.put("evidence",snapshot(id)); result.put("policy",currentPolicy(id));
        @SuppressWarnings("unchecked") var published=(Map<String,Object>)result.get("reference");
        result.put("publicationEvidence",published==null?null:publicSnapshot(one("select * from stir.reference_snapshot where tenant_id=? and id=?",tenant(),published.get("snapshot_id"))));
        return result;
    }
    /** Party-only endpoint; membership and exclusion causes never leak to a reference publisher. */
    public Map<String,Object> agreementContext(CurrentUser user, UUID agreement) {
        one("select id from stir.agreement where tenant_id=? and id=? and (initiator_id=? or owner_id=?)",tenant(),agreement,actor(user),actor(user));
        var rows=db.queryForList("select canonical_json,digest_sha256 from stir.reference_context_snapshot where tenant_id=? and agreement_id=?",tenant(),agreement);
        if(rows.isEmpty()) return null;
        return Map.of("canonicalJson",rows.getFirst().get("canonical_json"),"digestSha256",rows.getFirst().get("digest_sha256"));
    }
    public void record(UUID definition,String source,UUID sourceId,UUID a,UUID b,BigDecimal amount,BigDecimal quantity,String quantityUnit,String unit,boolean consent,Instant at) {
        if(definition==null) return;
        db.update("insert into stir.reference_observation values (?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,source,source_id) do nothing",
            UUID.randomUUID(),tenant(),definition,source,sourceId,a,b,amount,quantity,quantityUnit,unit,consent,Timestamp.from(at));
    }
    public void freezeContext(UUID definition,UUID agreement,String contractualDigest,boolean consent) {
        if(definition==null) return;
        var context=view(definition);
        // Convert JDBC values to strings/maps before JCS: timestamps and UUIDs have explicit representations.
        Map<String,Object> fields=plain(context);
        fields.put("format","STIR-REFERENCE-CONTEXT-JCS-1"); fields.put("agreementId",agreement.toString());
        fields.put("contractualDigestSha256",contractualDigest); fields.put("contractual",false);
        fields.put("captureMeaning","CURRENT_AT_ACCEPTANCE_NOT_PROOF_OF_DISPLAY");
        fields.put("aggregateConsent",consent); fields.put("capturedAt",Instant.now().toString()); fields.put("nonce",UUID.randomUUID().toString());
        String json=canonical(fields);
        db.update("insert into stir.reference_context_snapshot values (?,?,?,?,?,?)",UUID.randomUUID(),tenant(),agreement,json,digest(json),Timestamp.from(Instant.now()));
    }
    private static Map<String,Object> plain(Map<String,Object> source) {
        var out=new LinkedHashMap<String,Object>(); source.forEach((k,v)->out.put(k,plainValue(v))); return out;
    }
    private static Object plainValue(Object v) {
        if(v instanceof Map<?,?> m) { var out=new LinkedHashMap<String,Object>(); m.forEach((k,x)->out.put(k.toString(),plainValue(x))); return out; }
        if(v instanceof Timestamp t) return t.toInstant().toString();
        if(v instanceof UUID || v instanceof BigDecimal) return v.toString(); return v;
    }
}
