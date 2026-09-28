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

/** STIR-local constitutional authority. Signatures authorize exact immutable proposals, never HTTP
 * roles. WEBAUTHN_HARDWARE_CUSTODY.md: a seat/guardian credential may be the original same-device
 * SOFTWARE_ED25519 credential or a WEBAUTHN/hardware-backed one - CredentialEnvelope generalizes
 * "how a credential proved it signed this exact payload" without changing the payload itself, and
 * every credentialType/algorithm/envelope field added here is purely additive: a request that omits
 * them (every existing frontend/test call site) resolves to SOFTWARE_ED25519/Ed25519, byte-for-byte
 * the same behavior as before this increment. */
@Service @Transactional
public class SevenKeysService {
    private final JdbcTemplate db;
    private final WebAuthnCredentialService webauthn;
    public SevenKeysService(JdbcTemplate db, WebAuthnCredentialService webauthn) { this.db = db; this.webauthn = webauthn; }

    private static final String LEGACY_TYPE = "SOFTWARE_ED25519", LEGACY_ALGORITHM = "Ed25519";
    private static String resolveType(String credentialType) { return credentialType == null ? LEGACY_TYPE : credentialType; }
    private static String resolveAlgorithm(String algorithm) { return algorithm == null ? LEGACY_ALGORITHM : algorithm; }

    /** Verifies `envelope` proves possession of `publicKey` (credentialType/algorithm as stored on
     * the seat/authority row, or as declared by a not-yet-bound credential during bootstrap/
     * rotation/appointment) over the exact domain-separated `message`. For a WEBAUTHN credential
     * this also enforces the advanced sign_count (native WebAuthn clone/replay detection) and, when
     * `consumeReplayProtection` is true, persists it - the one place in this class with a real side
     * effect purely as part of verification, mirrored by WebAuthnCredentialService.recordSignCount
     * running in the same transaction. `consumeReplayProtection` must be true for every FIRST-time
     * verification of a freshly submitted envelope (bootstrap, sign(), suspend(), and the guardian/
     * new-key possession signatures activate() itself collects fresh) and false only when
     * re-verifying an envelope that was already consumed once before and is now merely being
     * re-checked for validity (activate()'s re-verification of already-stored seat signatures) -
     * otherwise the counter check would compare the already-bumped stored count against itself and
     * wrongly reject a legitimate, previously-accepted signature. */
    private void verify(UUID credentialId, String credentialType, String algorithm, String publicKey,
                         CredentialEnvelope envelope, byte[] message, boolean consumeReplayProtection) {
        if (envelope == null) throw new ResponseStatusException(BAD_REQUEST, "Missing signature");
        if (!resolveType(credentialType).equals(envelope.credentialType()))
            throw new ResponseStatusException(BAD_REQUEST, "Credential type mismatch");
        try {
            if ("WEBAUTHN".equals(envelope.credentialType())) {
                var material = webauthn.credentialFor(credentialId);
                byte[] coseKeyCbor = WebAuthnCrypto.decodeBase64url(publicKey, "publicKey");
                boolean requireUv = Boolean.TRUE.equals(material.get("user_verification_required"));
                long minSignCount = consumeReplayProtection ? ((Number) material.get("sign_count")).longValue() : 0;
                long newSignCount = WebAuthnCrypto.verifyAssertion(coseKeyCbor, resolveAlgorithm(algorithm), envelope,
                    WebAuthnCrypto.sha256(message), webauthn.rpId, webauthn.allowedOrigins(), requireUv, minSignCount);
                if (consumeReplayProtection) webauthn.recordSignCount(credentialId, newSignCount);
            } else {
                SevenKeysCrypto.requireSignature(publicKey, envelope.signature(), message);
            }
        } catch (IllegalArgumentException e) { throw new ResponseStatusException(BAD_REQUEST, "Invalid constitutional signature"); }
    }
    private void verify(UUID credentialId, String credentialType, String algorithm, String publicKey,
                         CredentialEnvelope envelope, byte[] message) {
        verify(credentialId, credentialType, algorithm, publicKey, envelope, message, true);
    }

    public record SeatInput(int ordinal, UUID controllerId, UUID credentialId, String publicKey, String possessionSignature,
                             String credentialType, String algorithm, CredentialEnvelope possessionEnvelope) {
        public SeatInput(int ordinal, UUID controllerId, UUID credentialId, String publicKey, String possessionSignature) {
            this(ordinal, controllerId, credentialId, publicKey, possessionSignature, null, null, null);
        }
        CredentialEnvelope envelope() { return CredentialEnvelope.of(possessionSignature, possessionEnvelope); }
    }
    public record Bootstrap(UUID authorityId, UUID communityId, List<SeatInput> seats,
                             UUID guardianCredentialId, String guardianPublicKey, String guardianPossessionSignature,
                             String guardianCredentialType, String guardianAlgorithm, CredentialEnvelope guardianPossessionEnvelope) {
        public Bootstrap(UUID authorityId, UUID communityId, List<SeatInput> seats,
                          UUID guardianCredentialId, String guardianPublicKey, String guardianPossessionSignature) {
            this(authorityId, communityId, seats, guardianCredentialId, guardianPublicKey, guardianPossessionSignature, null, null, null);
        }
        CredentialEnvelope guardianEnvelope() { return CredentialEnvelope.of(guardianPossessionSignature, guardianPossessionEnvelope); }
    }
    public record ProposalInput(UUID proposalId,String actionType,Map<String,Object> after,
                                List<String> affectedFields,String reason,List<String> evidenceRefs) {}
    public record SignatureInput(int seatOrdinal, UUID credentialId, String signatureBase64url, CredentialEnvelope credentialEnvelope) {
        public SignatureInput(int seatOrdinal, UUID credentialId, String signatureBase64url) {
            this(seatOrdinal, credentialId, signatureBase64url, null);
        }
        CredentialEnvelope envelope() { return CredentialEnvelope.of(signatureBase64url, credentialEnvelope); }
    }
    public record ExecutionInput(String guardianSignature, String newKeyPossessionSignature,
                                  CredentialEnvelope guardianEnvelope, CredentialEnvelope newKeyPossessionEnvelope) {
        public ExecutionInput(String guardianSignature, String newKeyPossessionSignature) {
            this(guardianSignature, newKeyPossessionSignature, null, null);
        }
        CredentialEnvelope resolvedGuardianEnvelope() { return CredentialEnvelope.of(guardianSignature, guardianEnvelope); }
        CredentialEnvelope resolvedNewKeyEnvelope() { return CredentialEnvelope.of(newKeyPossessionSignature, newKeyPossessionEnvelope); }
    }
    public record SuspensionInput(int seatOrdinal, UUID credentialId, long expectedSequence,
                                  Instant declaredAt, String reasonCode, List<String> evidenceRefs,
                                  String guardianSignature, CredentialEnvelope guardianEnvelope) {
        public SuspensionInput(int seatOrdinal, UUID credentialId, long expectedSequence,
                                Instant declaredAt, String reasonCode, List<String> evidenceRefs, String guardianSignature) {
            this(seatOrdinal, credentialId, expectedSequence, declaredAt, reasonCode, evidenceRefs, guardianSignature, null);
        }
        CredentialEnvelope envelope() { return CredentialEnvelope.of(guardianSignature, guardianEnvelope); }
    }

    static Map<String,Object> initialConstitution() {
        var m=new LinkedHashMap<String,Object>();
        m.put("schema","STIR-MARKET-CONSTITUTION-1");
        m.put("provenanceRequired",true); m.put("historyImmutable",true);
        m.put("independenceChecksRequired",true); m.put("concentrationChecksRequired",true);
        m.put("minimumObservationFloor",5); m.put("minimumParticipantFloor",6);
        m.put("maximumParticipantShareCeiling","0.50");
        m.put("guardianMayGovern",false); m.put("constitutionalThreshold",7);
        return m;
    }
    static void validateConstitution(Map<String,Object> m) {
        if(!m.keySet().equals(initialConstitution().keySet()) ||
           !"STIR-MARKET-CONSTITUTION-1".equals(m.get("schema")) ||
           !Boolean.TRUE.equals(m.get("provenanceRequired")) ||
           !Boolean.TRUE.equals(m.get("historyImmutable")) ||
           !Boolean.FALSE.equals(m.get("guardianMayGovern")) ||
           !Integer.valueOf(7).equals(m.get("constitutionalThreshold")) ||
           !(m.get("independenceChecksRequired") instanceof Boolean) ||
           !(m.get("concentrationChecksRequired") instanceof Boolean) ||
           !(m.get("minimumObservationFloor") instanceof Integer) ||
           (int)m.get("minimumObservationFloor")<5 ||
           !(m.get("minimumParticipantFloor") instanceof Integer) ||
           (int)m.get("minimumParticipantFloor")<6 ||
           !"0.50".equals(m.get("maximumParticipantShareCeiling")))
            throw new ResponseStatusException(BAD_REQUEST,"Invalid or forbidden constitutional field");
    }
    private Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Governance record not found");
        return rows.getFirst();
    }
    private Map<String,Object> authority(UUID community,boolean lock) {
        return one("select * from stir.constitutional_authority where tenant_id=? and community_id=?"+(lock?" for update":""),ReferenceService.tenant(),community);
    }
    private Map<String,Object> authorityById(UUID id,boolean lock) {
        return one("select * from stir.constitutional_authority where tenant_id=? and id=?"+(lock?" for update":""),ReferenceService.tenant(),id);
    }
    private Map<String,Object> seat(UUID authority,int ordinal) {
        return one("select * from stir.constitutional_seat where tenant_id=? and authority_id=? and ordinal=?",
            ReferenceService.tenant(),authority,ordinal);
    }
    private Map<String,Object> constitution(UUID community) {
        return one("select * from stir.market_constitution where tenant_id=? and community_id=? order by version desc limit 1",
            ReferenceService.tenant(),community);
    }
    private List<Map<String,Object>> seats(UUID authority) {
        return db.queryForList("select ordinal,controller_id,credential_id,public_key,status,credential_type,algorithm from stir.constitutional_seat where tenant_id=? and authority_id=? order by ordinal",
            ReferenceService.tenant(),authority);
    }
    private long next(UUID authority) {
        return db.queryForObject("update stir.constitutional_authority set next_sequence=next_sequence+1 where tenant_id=? and id=? returning next_sequence-1",
            Long.class,ReferenceService.tenant(),authority);
    }
    private void event(UUID authority,long sequence,String type,Map<String,Object> payload) {
        var prior=db.queryForList("select digest_sha256 from stir.market_governance_event where tenant_id=? and authority_id=? order by sequence desc limit 1",
            ReferenceService.tenant(),authority);
        String previous=prior.isEmpty()?null:(String)prior.getFirst().get("digest_sha256");
        var envelope=new LinkedHashMap<String,Object>();
        envelope.put("authorityId",authority.toString()); envelope.put("sequence",sequence);
        envelope.put("eventType",type); envelope.put("payload",payload); envelope.put("previousDigest",previous);
        String json=ReferenceService.canonical(envelope);
        db.update("insert into stir.market_governance_event values (?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),
            ReferenceService.tenant(),authority,sequence,type,json,previous,ReferenceService.digest(json),Timestamp.from(Instant.now()));
    }
    public Map<String,Object> bootstrap(CurrentUser user,Bootstrap input) {
        ReferenceService.actor(user);
        if(input==null || input.authorityId()==null || input.communityId()==null || input.seats()==null || input.seats().size()!=7 ||
           input.guardianCredentialId()==null || input.guardianPublicKey()==null)
            throw new ResponseStatusException(BAD_REQUEST,"Exactly seven seats and one separate guardian required");
        var binding=one("select community_id from stir.marketplace_economic_binding where tenant_id=?",ReferenceService.tenant());
        if(!input.communityId().equals(binding.get("community_id"))) throw new ResponseStatusException(BAD_REQUEST,"Community binding mismatch");
        if(!db.queryForList("select id from stir.constitutional_authority where tenant_id=? and community_id=?",ReferenceService.tenant(),input.communityId()).isEmpty())
            throw new ResponseStatusException(CONFLICT,"Authority already exists");
        var ordered=input.seats().stream().sorted(Comparator.comparingInt(SeatInput::ordinal)).toList();
        var controllers=new HashSet<UUID>(); var credentials=new HashSet<UUID>(); var keys=new HashSet<String>();
        var publicSeats=new ArrayList<Map<String,Object>>();
        for(int i=0;i<7;i++) {
            var s=ordered.get(i);
            if(s.ordinal()!=i+1 || s.controllerId()==null || s.credentialId()==null || s.publicKey()==null ||
               !controllers.add(s.controllerId()) || !credentials.add(s.credentialId()) || !keys.add(s.publicKey()))
                throw new ResponseStatusException(BAD_REQUEST,"Seats, controllers and credentials must be distinct");
            publicSeats.add(Map.of("ordinal",s.ordinal(),"controllerId",s.controllerId().toString(),
                "credentialId",s.credentialId().toString(),"publicKey",s.publicKey()));
        }
        if(credentials.contains(input.guardianCredentialId()) || keys.contains(input.guardianPublicKey()))
            throw new ResponseStatusException(BAD_REQUEST,"Guardian must be separate from all seats");
        var constitution=initialConstitution(); String constitutionalJson=ReferenceService.canonical(constitution);
        var bootstrap=new LinkedHashMap<String,Object>();
        bootstrap.put("format","STIR-SEVEN-KEYS-BOOTSTRAP-1"); bootstrap.put("tenantId",ReferenceService.tenant().toString());
        bootstrap.put("communityId",input.communityId().toString()); bootstrap.put("authorityId",input.authorityId().toString());
        bootstrap.put("seats",publicSeats); bootstrap.put("guardianCredentialId",input.guardianCredentialId().toString());
        bootstrap.put("guardianPublicKey",input.guardianPublicKey());
        bootstrap.put("constitutionDigest",ReferenceService.digest(constitutionalJson));
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.BOOTSTRAP_DOMAIN,bootstrap);
        for(var s:ordered) verify(s.credentialId(),s.credentialType(),s.algorithm(),s.publicKey(),s.envelope(),message);
        verify(input.guardianCredentialId(),input.guardianCredentialType(),input.guardianAlgorithm(),input.guardianPublicKey(),input.guardianEnvelope(),message);
        UUID tenant=ReferenceService.tenant(),authority=input.authorityId();
        db.update("insert into stir.constitutional_authority values (?,?,?,?,?,?,'ACTIVE',2,?,?,?)",
            authority,tenant,input.communityId(),7,input.guardianCredentialId(),input.guardianPublicKey(),Timestamp.from(Instant.now()),
            resolveType(input.guardianCredentialType()),resolveAlgorithm(input.guardianAlgorithm()));
        for(var s:ordered) {
            db.update("insert into stir.constitutional_seat values (?,?,?,?,?,?,'ACTIVE',?,?)",
                tenant,authority,s.ordinal(),s.controllerId(),s.credentialId(),s.publicKey(),resolveType(s.credentialType()),resolveAlgorithm(s.algorithm()));
            db.update("insert into stir.constitutional_credential_history values (?,?,?,?,?,?,?,'ACTIVE',1,?,?)",
                UUID.randomUUID(),tenant,authority,s.ordinal(),s.controllerId(),s.credentialId(),s.publicKey(),
                resolveType(s.credentialType()),resolveAlgorithm(s.algorithm()));
        }
        db.update("insert into stir.market_constitution values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant,
            input.communityId(),authority,1,constitutionalJson,ReferenceService.digest(constitutionalJson),Timestamp.from(Instant.now()));
        event(authority,1,"BOOTSTRAPPED",bootstrap);
        return view(input.communityId());
    }
    public Map<String,Object> view(UUID community) {
        var a=authority(community,false); var c=constitution(community);
        return Map.ofEntries(Map.entry("authorityId",a.get("id")),Map.entry("communityId",community),Map.entry("threshold",7),
            Map.entry("guardianStatus",a.get("guardian_status")),Map.entry("guardianCredentialId",a.get("guardian_credential_id")),
            Map.entry("guardianCredentialType",a.get("guardian_credential_type")),
            Map.entry("nextSequence",a.get("next_sequence")),Map.entry("constitutionVersion",c.get("version")),
            Map.entry("constitutionDigest",c.get("digest_sha256")),Map.entry("constitution",ReferenceService.parse((String)c.get("canonical_json"))),
            Map.entry("seats",seats((UUID)a.get("id"))));
    }
    public Map<String,Object> propose(CurrentUser user,UUID community,ProposalInput input) {
        ReferenceService.actor(user);
        var a=authority(community,true); UUID authority=(UUID)a.get("id");
        if(input==null || input.proposalId()==null || input.after()==null || input.reason()==null || input.reason().isBlank() ||
           input.reason().length()>2000 || input.evidenceRefs()==null || input.affectedFields()==null)
            throw new ResponseStatusException(BAD_REQUEST,"Incomplete proposal");
        String action=input.actionType();
        if(!Set.of("AMEND_CONSTITUTION","REMOVE_GUARDIAN","APPOINT_GUARDIAN","ROTATE_CREDENTIAL","REPLACE_CONTROLLER").contains(action))
            throw new ResponseStatusException(BAD_REQUEST,"Unknown protected action");
        if("AMEND_CONSTITUTION".equals(action)) {
            validateConstitution(input.after());
            var before=ReferenceService.parse((String)constitution(community).get("canonical_json"));
            var changed=new TreeSet<String>();
            for(String field:before.keySet()) if(!Objects.equals(before.get(field),input.after().get(field))) changed.add(field);
            if(changed.isEmpty() || !changed.equals(new TreeSet<>(input.affectedFields())))
                throw new ResponseStatusException(BAD_REQUEST,"Semantic diff does not match affected fields");
        } else if("REMOVE_GUARDIAN".equals(action)) {
            if(!input.after().equals(Map.of("guardianCredentialId",a.get("guardian_credential_id").toString(),"status","REMOVED")))
                throw new ResponseStatusException(BAD_REQUEST,"Removal may only revoke the current guardian");
        } else if("APPOINT_GUARDIAN".equals(action)) {
            if(!"REMOVED".equals(a.get("guardian_status")) || !requiredKeysWithOptionalCredentialType(input.after().keySet(),Set.of("guardianCredentialId","publicKey")))
                throw new ResponseStatusException(CONFLICT,"Guardian appointment requires prior removal");
        } else {
            validateRecoveryProposal(authority,action,input.after());
        }
        var c=constitution(community); long sequence=next(authority);
        String afterJson=ReferenceService.canonical(input.after());
        var payload=new LinkedHashMap<String,Object>();
        payload.put("format","STIR-CONSTITUTIONAL-PROPOSAL-1"); payload.put("tenantId",ReferenceService.tenant().toString());
        payload.put("communityId",community.toString()); payload.put("authorityId",authority.toString());
        payload.put("authorityVersion",c.get("version")); payload.put("proposalId",input.proposalId().toString());
        payload.put("actionType",action); payload.put("beforeDigest",c.get("digest_sha256"));
        payload.put("afterDigest",ReferenceService.digest(afterJson)); payload.put("affectedFields",input.affectedFields().stream().sorted().toList());
        payload.put("reason",input.reason()); payload.put("evidenceRefs",input.evidenceRefs().stream().sorted().toList());
        payload.put("sequence",sequence); payload.put("after",input.after());
        String json=ReferenceService.canonical(payload);
        db.update("insert into stir.constitutional_proposal values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            input.proposalId(),ReferenceService.tenant(),authority,community,c.get("version"),action,c.get("digest_sha256"),
            ReferenceService.digest(afterJson),afterJson,ReferenceService.canonical(Map.of("fields",payload.get("affectedFields"))),
            input.reason(),ReferenceService.canonical(Map.of("refs",payload.get("evidenceRefs"))),sequence,json,
            ReferenceService.digest(json),Timestamp.from(Instant.now()));
        event(authority,sequence,"PROPOSED",Map.of("proposalId",input.proposalId().toString(),"payloadDigest",ReferenceService.digest(json)));
        return proposal(input.proposalId());
    }
    /** True if `actual` equals `required` or `required` plus the optional credentialType/algorithm
     * pair a WEBAUTHN-sourced new/guardian key adds - the same additive-null convention as every
     * other new field in this increment, applied to a proposal's `after` map keyset check. */
    private static boolean requiredKeysWithOptionalCredentialType(Set<String> actual,Set<String> required) {
        if(actual.equals(required)) return true;
        var extended=new HashSet<>(required); extended.add("credentialType"); extended.add("algorithm");
        return actual.equals(extended);
    }
    private void validateRecoveryProposal(UUID authority,String action,Map<String,Object> after) {
        var base=Set.of("affectedSeat","oldCredentialId","newCredentialId","newPublicKey","controllerId");
        if(!after.keySet().containsAll(base))
            throw new ResponseStatusException(BAD_REQUEST,"Incomplete recovery payload");
        int ordinal=((Number)after.get("affectedSeat")).intValue(); var s=seat(authority,ordinal);
        if(!s.get("credential_id").toString().equals(after.get("oldCredentialId")))
            throw new ResponseStatusException(CONFLICT,"Old credential mismatch");
        if("ROTATE_CREDENTIAL".equals(action)) {
            var required=new HashSet<>(base); required.addAll(Set.of("continuityEvidenceRefs","reason"));
            if(!s.get("controller_id").toString().equals(after.get("controllerId")) ||
               !requiredKeysWithOptionalCredentialType(after.keySet(),required))
                throw new ResponseStatusException(BAD_REQUEST,"Rotation must preserve controller and carry continuity evidence");
        } else {
            var required=new HashSet<>(base); required.addAll(Set.of("finalResolutionId","finalResolutionDigest","reason"));
            if(!requiredKeysWithOptionalCredentialType(after.keySet(),required) ||
               s.get("controller_id").toString().equals(after.get("controllerId")))
                throw new ResponseStatusException(BAD_REQUEST,"Replacement needs a different controller and final resolution");
        }
        if(!"EMERGENCY_SUSPENDED".equals(s.get("status")))
            throw new ResponseStatusException(CONFLICT,"Seat must first be suspended");
    }
    public Map<String,Object> proposal(UUID id) {
        var p=one("select * from stir.constitutional_proposal where tenant_id=? and id=?",ReferenceService.tenant(),id);
        var sig=db.queryForList("select seat_ordinal,credential_id,signed_at from stir.constitutional_signature where tenant_id=? and proposal_id=? order by seat_ordinal",ReferenceService.tenant(),id);
        var events=db.queryForList("select event_type from stir.market_governance_event where tenant_id=? and authority_id=? and payload_json like ?",
            ReferenceService.tenant(),p.get("authority_id"),"%"+id+"%");
        boolean activated=events.stream().anyMatch(e->"ACTIVATED".equals(e.get("event_type")));
        var result=new LinkedHashMap<String,Object>();
        result.put("id",id); result.put("communityId",p.get("community_id")); result.put("actionType",p.get("action_type"));
        result.put("payloadJson",p.get("payload_json")); result.put("payloadDigest",p.get("payload_digest"));
        result.put("beforeDigest",p.get("before_digest")); result.put("afterDigest",p.get("after_digest"));
        result.put("signatures",sig); result.put("signatureCount",sig.size()); result.put("required",required((String)p.get("action_type")));
        result.put("state",activated?"ACTIVATED":sig.isEmpty()?"PROPOSED":"PARTIALLY_SIGNED");
        return result;
    }
    public Map<String,Object> publicProposal(UUID id) {
        var full=new LinkedHashMap<>(proposal(id));
        full.remove("payloadJson");
        var row=one("select action_type,reason,after_json,affected_fields_json from stir.constitutional_proposal where tenant_id=? and id=?",
            ReferenceService.tenant(),id);
        if(Set.of("AMEND_CONSTITUTION","REMOVE_GUARDIAN","APPOINT_GUARDIAN").contains(row.get("action_type")))
            full.put("reason",row.get("reason"));
        if("AMEND_CONSTITUTION".equals(row.get("action_type"))) {
            full.put("after",ReferenceService.parse((String)row.get("after_json")));
            full.put("affectedFields",ReferenceService.parse((String)row.get("affected_fields_json")).get("fields"));
        }
        return full;
    }
    public List<Map<String,Object>> publicProposals(UUID community) {
        UUID authority=(UUID)authority(community,false).get("id");
        var ids=db.queryForList("select id from stir.constitutional_proposal where tenant_id=? and authority_id=? order by sequence desc limit 100",
            ReferenceService.tenant(),authority);
        return ids.stream().map(row->publicProposal((UUID)row.get("id"))).toList();
    }
    private int required(String action) { return "REMOVE_GUARDIAN".equals(action)?5:
        Set.of("ROTATE_CREDENTIAL","REPLACE_CONTROLLER").contains(action)?6:7; }
    public Map<String,Object> sign(UUID id,SignatureInput input) {
        var p=one("select * from stir.constitutional_proposal where tenant_id=? and id=?",ReferenceService.tenant(),id);
        UUID authority=(UUID)p.get("authority_id"); authorityById(authority,true);
        var s=seat(authority,input.seatOrdinal());
        if(!"ACTIVE".equals(s.get("status")) || !s.get("credential_id").equals(input.credentialId()))
            throw new ResponseStatusException(CONFLICT,"Seat credential is not active");
        if(Set.of("ROTATE_CREDENTIAL","REPLACE_CONTROLLER").contains(p.get("action_type"))) {
            var after=ReferenceService.parse((String)p.get("after_json"));
            if(((Number)after.get("affectedSeat")).intValue()==input.seatOrdinal())
                throw new ResponseStatusException(CONFLICT,"Affected seat cannot sign its own recovery");
        }
        verify((UUID)s.get("credential_id"),(String)s.get("credential_type"),(String)s.get("algorithm"),(String)s.get("public_key"),
            input.envelope(),SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,ReferenceService.parse((String)p.get("payload_json"))));
        if(!db.queryForList("select id from stir.constitutional_signature where tenant_id=? and proposal_id=? and seat_ordinal=?",
            ReferenceService.tenant(),id,input.seatOrdinal()).isEmpty())
            throw new ResponseStatusException(CONFLICT,"Seat already signed");
        var envelope=input.envelope();
        db.update("insert into stir.constitutional_signature values (?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),ReferenceService.tenant(),id,
            input.seatOrdinal(),input.credentialId(),envelope.signature(),Timestamp.from(Instant.now()),
            envelope.clientDataJson(),envelope.authenticatorData());
        event(authority,next(authority),"SIGNED",Map.of("proposalId",id.toString(),"seat",input.seatOrdinal(),"credentialId",input.credentialId().toString()));
        return proposal(id);
    }
    private boolean frozen(UUID authority) {
        return db.queryForObject("select count(*) from stir.constitutional_seat where tenant_id=? and authority_id=? and status<>'ACTIVE'",
            Integer.class,ReferenceService.tenant(),authority)>0;
    }
    public Map<String,Object> activate(UUID id,ExecutionInput execution) {
        var p=one("select * from stir.constitutional_proposal where tenant_id=? and id=?",ReferenceService.tenant(),id);
        UUID authority=(UUID)p.get("authority_id"); var a=authorityById(authority,true);
        if("ACTIVATED".equals(proposal(id).get("state"))) throw new ResponseStatusException(CONFLICT,"Already activated");
        String action=(String)p.get("action_type");
        if(!p.get("before_digest").equals(constitution((UUID)p.get("community_id")).get("digest_sha256")))
            throw new ResponseStatusException(CONFLICT,"Constitution changed; proposal is stale");
        if(frozen(authority) && !Set.of("REMOVE_GUARDIAN","ROTATE_CREDENTIAL","REPLACE_CONTROLLER").contains(action))
            throw new ResponseStatusException(CONFLICT,"CONSTITUTIONAL_FREEZE");
        var signatures=db.queryForList("select seat_ordinal,credential_id,signature_base64url,client_data_json,authenticator_data from stir.constitutional_signature where tenant_id=? and proposal_id=?",ReferenceService.tenant(),id);
        if(signatures.size()<required(action)) throw new ResponseStatusException(CONFLICT,"Insufficient constitutional signatures");
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,ReferenceService.parse((String)p.get("payload_json")));
        for(var sig:signatures) {
            var s=seat(authority,(int)sig.get("seat_ordinal"));
            if(!"ACTIVE".equals(s.get("status")) || !s.get("credential_id").equals(sig.get("credential_id")))
                throw new ResponseStatusException(CONFLICT,"A signing credential was revoked or suspended");
            var envelope=new CredentialEnvelope((String)s.get("credential_type"),(String)s.get("algorithm"),
                (String)sig.get("signature_base64url"),(String)sig.get("client_data_json"),(String)sig.get("authenticator_data"));
            verify((UUID)s.get("credential_id"),(String)s.get("credential_type"),(String)s.get("algorithm"),(String)s.get("public_key"),envelope,message,false);
        }
        var after=ReferenceService.parse((String)p.get("after_json"));
        if("AMEND_CONSTITUTION".equals(action)) {
            if(signatures.size()!=7 || frozen(authority)) throw new ResponseStatusException(CONFLICT,"7-of-7 active seats required");
            validateConstitution(after);
            int version=(int)constitution((UUID)p.get("community_id")).get("version")+1;
            db.update("insert into stir.market_constitution values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),ReferenceService.tenant(),
                p.get("community_id"),authority,version,p.get("after_json"),p.get("after_digest"),Timestamp.from(Instant.now()));
        } else if("REMOVE_GUARDIAN".equals(action)) {
            if(!"ACTIVE".equals(a.get("guardian_status"))) throw new ResponseStatusException(CONFLICT,"Guardian already removed");
            db.update("update stir.constitutional_authority set guardian_status='REMOVED' where tenant_id=? and id=?",ReferenceService.tenant(),authority);
        } else if("APPOINT_GUARDIAN".equals(action)) {
            if(signatures.size()!=7 || frozen(authority) || !"REMOVED".equals(a.get("guardian_status")))
                throw new ResponseStatusException(CONFLICT,"Normal 7-of-7 appointment required");
            if(!db.queryForList("select id from stir.constitutional_credential_history where tenant_id=? and authority_id=? and (credential_id=? or public_key=?)",
                ReferenceService.tenant(),authority,UUID.fromString((String)after.get("guardianCredentialId")),after.get("publicKey")).isEmpty())
                throw new ResponseStatusException(CONFLICT,"Guardian key must not have served a seat");
            String newType=resolveType((String)after.get("credentialType")),newAlgorithm=resolveAlgorithm((String)after.get("algorithm"));
            verify(UUID.fromString((String)after.get("guardianCredentialId")),newType,newAlgorithm,(String)after.get("publicKey"),
                execution.resolvedNewKeyEnvelope(),SevenKeysCrypto.message(SevenKeysCrypto.POSSESSION_DOMAIN,ReferenceService.parse((String)p.get("payload_json"))));
            db.update("update stir.constitutional_authority set guardian_credential_id=?,guardian_public_key=?,guardian_status='ACTIVE',guardian_credential_type=?,guardian_algorithm=? where tenant_id=? and id=?",
                UUID.fromString((String)after.get("guardianCredentialId")),after.get("publicKey"),newType,newAlgorithm,ReferenceService.tenant(),authority);
        } else {
            int ordinal=((Number)after.get("affectedSeat")).intValue(); var old=seat(authority,ordinal);
            if(!"EMERGENCY_SUSPENDED".equals(old.get("status")) || signatures.size()!=6 || !"ACTIVE".equals(a.get("guardian_status")))
                throw new ResponseStatusException(CONFLICT,"Recovery requires Guardian and all other six active seats");
            verify((UUID)a.get("guardian_credential_id"),(String)a.get("guardian_credential_type"),(String)a.get("guardian_algorithm"),(String)a.get("guardian_public_key"),
                execution.resolvedGuardianEnvelope(),SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,ReferenceService.parse((String)p.get("payload_json"))));
            String newType=resolveType((String)after.get("credentialType")),newAlgorithm=resolveAlgorithm((String)after.get("algorithm"));
            UUID newCredential=UUID.fromString((String)after.get("newCredentialId"));
            verify(newCredential,newType,newAlgorithm,(String)after.get("newPublicKey"),
                execution.resolvedNewKeyEnvelope(),SevenKeysCrypto.message(SevenKeysCrypto.POSSESSION_DOMAIN,ReferenceService.parse((String)p.get("payload_json"))));
            if("REPLACE_CONTROLLER".equals(action))
                throw new ResponseStatusException(CONFLICT,"FINAL_RESOLUTION_VERIFICATION_UNAVAILABLE");
            if(!db.queryForList("select id from stir.constitutional_credential_history where tenant_id=? and authority_id=? and credential_id=?",
                ReferenceService.tenant(),authority,newCredential).isEmpty())
                throw new ResponseStatusException(CONFLICT,"Credential was already used");
            if(!db.queryForList("select id from stir.constitutional_credential_history where tenant_id=? and authority_id=? and public_key=?",
                ReferenceService.tenant(),authority,after.get("newPublicKey")).isEmpty() ||
                after.get("newPublicKey").equals(a.get("guardian_public_key")))
                throw new ResponseStatusException(CONFLICT,"Public key already belongs to the authority");
            long sequence=next(authority);
            db.update("update stir.constitutional_seat set credential_id=?,public_key=?,status='ACTIVE',credential_type=?,algorithm=? where tenant_id=? and authority_id=? and ordinal=?",
                newCredential,after.get("newPublicKey"),newType,newAlgorithm,ReferenceService.tenant(),authority,ordinal);
            db.update("insert into stir.constitutional_credential_history values (?,?,?,?,?,?,?,'REVOKED',?,?,?)",UUID.randomUUID(),
                ReferenceService.tenant(),authority,ordinal,old.get("controller_id"),old.get("credential_id"),old.get("public_key"),sequence,
                old.get("credential_type"),old.get("algorithm"));
            db.update("insert into stir.constitutional_credential_history values (?,?,?,?,?,?,?,'ACTIVE',?,?,?)",UUID.randomUUID(),
                ReferenceService.tenant(),authority,ordinal,old.get("controller_id"),newCredential,after.get("newPublicKey"),sequence,newType,newAlgorithm);
            event(authority,sequence,"CREDENTIAL_ROTATED",Map.of("proposalId",id.toString(),"seat",ordinal,
                "oldCredentialId",old.get("credential_id").toString(),"newCredentialId",newCredential.toString()));
        }
        event(authority,next(authority),"ACTIVATED",Map.of("proposalId",id.toString(),"actionType",action));
        return proposal(id);
    }
    public Map<String,Object> suspend(UUID community,SuspensionInput input) {
        var a=authority(community,true); UUID authority=(UUID)a.get("id");
        if(!"ACTIVE".equals(a.get("guardian_status"))) throw new ResponseStatusException(CONFLICT,"Guardian removed");
        if(frozen(authority)) throw new ResponseStatusException(CONFLICT,"One suspension at a time");
        var s=seat(authority,input.seatOrdinal());
        if(!"ACTIVE".equals(s.get("status")) || !s.get("credential_id").equals(input.credentialId()) ||
           input.expectedSequence()!=((Number)a.get("next_sequence")).longValue() || input.reasonCode()==null ||
           input.reasonCode().isBlank() || input.evidenceRefs()==null || input.declaredAt()==null ||
           input.declaredAt().isBefore(Instant.now().minusSeconds(300)) || input.declaredAt().isAfter(Instant.now().plusSeconds(300)))
            throw new ResponseStatusException(CONFLICT,"Invalid or stale suspension");
        var payload=Map.<String,Object>of("format","STIR-KEY-SUSPENSION-1","tenantId",ReferenceService.tenant().toString(),
            "communityId",community.toString(),"authorityId",authority.toString(),"seat",input.seatOrdinal(),
            "credentialId",input.credentialId().toString(),"reasonCode",input.reasonCode(),
            "evidenceRefs",input.evidenceRefs().stream().sorted().toList(),"sequence",input.expectedSequence(),
            "declaredAt",input.declaredAt().toString());
        verify((UUID)a.get("guardian_credential_id"),(String)a.get("guardian_credential_type"),(String)a.get("guardian_algorithm"),(String)a.get("guardian_public_key"),
            input.envelope(),SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,payload));
        long sequence=next(authority);
        db.update("update stir.constitutional_seat set status='EMERGENCY_SUSPENDED' where tenant_id=? and authority_id=? and ordinal=?",
            ReferenceService.tenant(),authority,input.seatOrdinal());
        db.update("insert into stir.constitutional_credential_history values (?,?,?,?,?,?,?,'EMERGENCY_SUSPENDED',?,?,?)",
            UUID.randomUUID(),ReferenceService.tenant(),authority,input.seatOrdinal(),s.get("controller_id"),s.get("credential_id"),s.get("public_key"),sequence,
            s.get("credential_type"),s.get("algorithm"));
        event(authority,sequence,"EMERGENCY_SUSPENDED",payload);
        return view(community);
    }
    public List<Map<String,Object>> events(UUID community) {
        UUID authority=(UUID)authority(community,false).get("id");
        return db.queryForList("select sequence,event_type,previous_digest,digest_sha256,recorded_at from stir.market_governance_event where tenant_id=? and authority_id=? order by sequence",
            ReferenceService.tenant(),authority);
    }
    public Map<String,Object> audit(UUID community) {
        UUID authority=(UUID)authority(community,false).get("id");
        var rows=db.queryForList("select sequence,payload_json,previous_digest,digest_sha256 from stir.market_governance_event where tenant_id=? and authority_id=? order by sequence",
            ReferenceService.tenant(),authority);
        String previous=null;long expected=1;boolean valid=true;
        for(var row:rows) {
            long sequence=((Number)row.get("sequence")).longValue();
            String json=(String)row.get("payload_json");
            if(sequence!=expected++ || !Objects.equals(previous,row.get("previous_digest")) ||
               !Objects.equals(ReferenceService.digest(json),row.get("digest_sha256")) ||
               !json.equals(ReferenceService.canonical(ReferenceService.parse(json)))) valid=false;
            previous=(String)row.get("digest_sha256");
        }
        var result=new LinkedHashMap<String,Object>();result.put("valid",valid);result.put("eventCount",rows.size());
        result.put("latestDigest",previous);return result;
    }
    public List<Map<String,Object>> credentialHistory(UUID community) {
        UUID authority=(UUID)authority(community,false).get("id");
        return db.queryForList("select seat_ordinal,controller_id,credential_id,public_key,status,event_sequence,credential_type,algorithm from stir.constitutional_credential_history where tenant_id=? and authority_id=? order by event_sequence,seat_ordinal",
            ReferenceService.tenant(),authority);
    }
}
