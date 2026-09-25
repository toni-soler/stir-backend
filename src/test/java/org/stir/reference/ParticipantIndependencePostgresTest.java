package org.stir.reference;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import es.idynamicsax.idax.tenant.TenantContext;
import es.idynamicsax.idax.security.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.stir.economic.OstrisClient;
import org.stir.economic.StirOstrisException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** STIR does not confuse account diversity with independent-participant diversity
 * (PARTICIPANT_INDEPENDENCE.md). osTRIS stays the sole identity authority - these tests use a
 * mocked OstrisClient to simulate its real, already-shipped private continuity contract
 * (CONFIRMED/CONTESTED/REJECTED/CONTINUITY_NOT_FOUND), and prove STIR only ever narrows a
 * PUBLISHER-triggered refresh into a minimal, idempotent, non-live-reused projection - never
 * inventing independence, never blocking a valid Agreement, never letting a stale or downgraded
 * response overwrite a fresher one. */
@Testcontainers
class ParticipantIndependencePostgresTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    Connection connection; JdbcTemplate jdbc; ReferenceService service; ParticipantIndependenceService independence; OstrisClient ostris;
    UUID tenant=UUID.randomUUID(), userId=UUID.randomUUID(), community; CurrentUser user;
    @BeforeAll static void migrate() throws Exception {
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()) { s.execute("create role idax_app; create role idax_admin"); }
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("stir").locations("classpath:db/migration-stir").load().migrate();
    }
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("set local role idax_app"); jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        TenantContext.set(new TenantContext(tenant,null,userId,"test",TenantContext.DbRole.IDAX_APP));
        ostris=mock(OstrisClient.class); independence=new ParticipantIndependenceService(jdbc,ostris); service=new ReferenceService(jdbc,independence);
        user=mock(CurrentUser.class); when(user.getUserId()).thenReturn(userId);
        community=UUID.randomUUID();
        jdbc.update("insert into stir.marketplace_economic_binding values (?,?,?,now())",tenant,community,UUID.randomUUID());
    }
    @AfterEach void close() throws Exception {connection.rollback();connection.close();TenantContext.clear();}
    UUID definition() {return (UUID)service.create(user,new ReferenceController.DefinitionRequest("Firewood","1 stere",Map.of(),BigDecimal.ONE,"stere")).get("id");}
    void bind(UUID stirUser,UUID participantId) {
        jdbc.update("insert into stir.participant_economic_binding values (?,?,?,?,?,?,?,?,?,?,now())",
            UUID.randomUUID(),tenant,stirUser,community,UUID.randomUUID(),participantId,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"pk");
    }
    OstrisClient.IdentityContinuityView confirmed(UUID participant,UUID riskSubject,long sequence) {
        return new OstrisClient.IdentityContinuityView(UUID.randomUUID(),participant,"CONFIRMED",sequence,riskSubject,"NONE");
    }
    OstrisClient.IdentityContinuityView contested(UUID participant,long sequence) {
        return new OstrisClient.IdentityContinuityView(UUID.randomUUID(),participant,"CONTESTED",sequence,null,"REQUIRE_REVIEW");
    }
    OstrisClient.IdentityContinuityView rejected(UUID participant,long sequence) {
        return new OstrisClient.IdentityContinuityView(UUID.randomUUID(),participant,"REJECTED",sequence,null,"NONE");
    }

    @Test void refreshWithNoOstrisBindingRecordsThatExplicitlyWithoutCallingOstris() {
        var result=independence.refresh(user,UUID.randomUUID());
        assertEquals("NO_OSTRIS_BINDING",result.get("status"));
        verifyNoInteractions(ostris);
    }
    @Test void refreshOnContinuityNotFoundRecordsUnknownNeverInventsIndependence() {
        UUID target=UUID.randomUUID(), participant=UUID.randomUUID();
        bind(target,participant);
        when(ostris.privateContinuity(community,participant)).thenThrow(new StirOstrisException(422,"CONTINUITY_NOT_FOUND","No effective continuity decision"));
        var result=independence.refresh(user,target);
        assertEquals("INDEPENDENCE_UNKNOWN",result.get("status"));
    }
    @Test void refreshOnConfirmedRecordsClusterRefNotRawRiskSubjectId() {
        UUID target=UUID.randomUUID(), participant=UUID.randomUUID(), riskSubject=UUID.randomUUID();
        bind(target,participant);
        when(ostris.privateContinuity(community,participant)).thenReturn(confirmed(participant,riskSubject,5L));
        independence.refresh(user,target);
        var stored=jdbc.queryForList("select status,cluster_ref,community_sequence from stir.participant_independence_projection where tenant_id=? and user_id=?",tenant,target).getFirst();
        assertEquals("RELATED_CONTINUITY",stored.get("status"));
        assertEquals(5L,((Number)stored.get("community_sequence")).longValue());
        assertNotEquals(riskSubject.toString(),stored.get("cluster_ref"));
        assertEquals(ParticipantIndependenceService.clusterRef(tenant,community,riskSubject),stored.get("cluster_ref"));
    }
    @Test void refreshOnContestedIsPendingNeverTreatedAsRelatedOrIndependent() {
        UUID target=UUID.randomUUID(), participant=UUID.randomUUID();
        bind(target,participant);
        when(ostris.privateContinuity(community,participant)).thenReturn(contested(participant,3L));
        assertEquals("IDENTITY_CONTINUITY_PENDING",independence.refresh(user,target).get("status"));
    }
    @Test void refreshOnRejectedIsUnknownNotAnIndependenceCertificate() {
        // osTRIS never affirmatively certifies independence - REJECTED means only "this specific
        // relatedness claim was rejected," never "confirmed independent."
        UUID target=UUID.randomUUID(), participant=UUID.randomUUID();
        bind(target,participant);
        when(ostris.privateContinuity(community,participant)).thenReturn(rejected(participant,2L));
        assertEquals("INDEPENDENCE_UNKNOWN",independence.refresh(user,target).get("status"));
    }
    @Test void refreshRejectsPlatformSuperAdminSameGuardAsEveryOtherMutation() {
        var superadmin=mock(CurrentUser.class); when(superadmin.getUserId()).thenReturn(UUID.randomUUID()); when(superadmin.isSuperuser()).thenReturn(true);
        assertThrows(AccessDeniedException.class,()->independence.refresh(superadmin,UUID.randomUUID()));
        verifyNoInteractions(ostris);
    }
    @Test void staleOrOutOfOrderOstrisResponseNeverDowngradesAFresherProjection() {
        UUID target=UUID.randomUUID(), participant=UUID.randomUUID(), riskSubject=UUID.randomUUID();
        bind(target,participant);
        when(ostris.privateContinuity(community,participant)).thenReturn(confirmed(participant,riskSubject,10L));
        independence.refresh(user,target);
        when(ostris.privateContinuity(community,participant)).thenReturn(rejected(participant,4L)); // an older, stale answer
        independence.refresh(user,target);
        var stored=jdbc.queryForList("select status,community_sequence from stir.participant_independence_projection where tenant_id=? and user_id=?",tenant,target).getFirst();
        assertEquals("RELATED_CONTINUITY",stored.get("status"));
        assertEquals(10L,((Number)stored.get("community_sequence")).longValue());
    }
    @Test void refreshIsIdempotentSameResponseTwiceLeavesOneRow() {
        UUID target=UUID.randomUUID(), participant=UUID.randomUUID(), riskSubject=UUID.randomUUID();
        bind(target,participant);
        when(ostris.privateContinuity(community,participant)).thenReturn(confirmed(participant,riskSubject,7L));
        independence.refresh(user,target); independence.refresh(user,target);
        assertEquals(1,jdbc.queryForObject("select count(*) from stir.participant_independence_projection where tenant_id=? and user_id=?",Integer.class,tenant,target));
    }
    @Test void staleProjectionPastFreshnessPolicyIsTreatedAsAbsentBySnapshot() {
        UUID target=UUID.randomUUID();
        jdbc.update("insert into stir.participant_independence_projection values (?,?,?,?,?,?,?,?)",
            tenant,target,community,"RELATED_CONTINUITY",ParticipantIndependenceService.clusterRef(tenant,community,UUID.randomUUID()),1L,
            Timestamp.from(Instant.now().minus(Duration.ofDays(400))),userId);
        var forUsers=independence.forUsers(tenant,List.of(target),30,Instant.now());
        assertTrue(forUsers.isEmpty(),"a projection older than the policy's freshnessDays must be treated as absent, never silently trusted");
    }

    // --- Adversarial scenario D: same person via multiple accounts (per-observation exclusion) ---
    @Test void confirmedSameClusterCounterpartyIsExcludedAsRelatedNotIndependentEvidence() {
        UUID id=definition(); var d=service.definition(id);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), pa=UUID.randomUUID(), pb=UUID.randomUUID(), riskSubject=UUID.randomUUID();
        bind(a,pa); bind(b,pb);
        when(ostris.privateContinuity(community,pa)).thenReturn(confirmed(pa,riskSubject,1L));
        when(ostris.privateContinuity(community,pb)).thenReturn(confirmed(pb,riskSubject,1L));
        independence.refresh(user,a); independence.refresh(user,b);
        UUID source=UUID.randomUUID();
        service.record(id,"AGREEMENT",source,a,b,BigDecimal.TEN,BigDecimal.ONE,"stere",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        var observation=jdbc.queryForObject("select id from stir.reference_observation where tenant_id=? and source_id=?",UUID.class,tenant,source);
        var manifest=service.evidenceManifest(id);
        @SuppressWarnings("unchecked") var exclusions=(Map<String,String>)manifest.get("exclusions");
        assertEquals("RELATED_PARTICIPANT_CLUSTER",exclusions.get(observation.toString()));
        // The Agreement itself is never rewritten - it stays a real, unmodified raw observation.
        assertEquals(1,jdbc.queryForObject("select count(*) from stir.reference_observation where tenant_id=? and id=?",Integer.class,tenant,observation));
    }

    // --- Adversarial scenario A/B: raw diversity looks sufficient but a confirmed cluster corrects it down ---
    @Test void rawDiversityLooksSufficientButConfirmedClusterRevealsInsufficientIndependentParticipants() {
        UUID id=definition(); var d=service.definition(id);
        UUID a1=UUID.randomUUID(),a2=UUID.randomUUID(),a3=UUID.randomUUID(),a4=UUID.randomUUID(),a5=UUID.randomUUID(),a6=UUID.randomUUID();
        UUID[] ring={a1,a2,a3,a4,a5,a6,a1};
        // Without independence data, this shape (6 distinct accounts, 6 relationships) alone would
        // already read as sufficient raw diversity - confirmed with the pure EvidenceAnalysis function
        // directly, bypassing the once-per-day snapshot cache entirely, since a second same-day
        // service.snapshot() call would just replay today's already-frozen result either way
        // (dailyCutPreventsLiveDifferencing in ReferencePostgresTest already proves that caching).
        var observations=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<6;i++) observations.add(new EvidenceAnalysis.Observation(UUID.randomUUID(),"AGREEMENT",ring[i],ring[i+1],
            BigDecimal.valueOf(10+i),BigDecimal.ONE,"stere",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1))));
        var rawPolicy=new EvidenceAnalysis.Policy(90,5,6,new BigDecimal("0.40"),30);
        var withoutIndependence=EvidenceAnalysis.analyze(observations,rawPolicy,BigDecimal.ONE,(String)d.get("quantity_unit"),(String)d.get("unit_ref"),Instant.now());
        assertEquals("SUFFICIENT_DATA",withoutIndependence.summary().get("status"));
        assertFalse(((List<?>)withoutIndependence.summary().get("reasons")).contains("LOW_DIVERSITY"));
        // a1 and a4 (never directly paired with each other in this ring) turn out to be the same
        // continuity cluster - a real relationship raw account-counting could never see. Record the
        // observations for real and refresh independence BEFORE the only real snapshot() call this
        // test makes, so it is never masked by the daily-freeze cache.
        for(int i=0;i<6;i++) service.record(id,"AGREEMENT",UUID.randomUUID(),ring[i],ring[i+1],BigDecimal.valueOf(10+i),BigDecimal.ONE,"stere",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        UUID pa1=UUID.randomUUID(), pa4=UUID.randomUUID(), riskSubject=UUID.randomUUID();
        bind(a1,pa1); bind(a4,pa4);
        when(ostris.privateContinuity(community,pa1)).thenReturn(confirmed(pa1,riskSubject,1L));
        when(ostris.privateContinuity(community,pa4)).thenReturn(confirmed(pa4,riskSubject,1L));
        independence.refresh(user,a1); independence.refresh(user,a4);
        var after=service.snapshot(id);
        assertEquals("INSUFFICIENT_DATA",after.get("status"));
        assertTrue(((List<?>)after.get("reasons")).contains("INSUFFICIENT_INDEPENDENT_PARTICIPANTS"),after.get("reasons").toString());
        assertFalse(((List<?>)after.get("reasons")).contains("LOW_DIVERSITY"),"raw diversity is unaffected - only the adjusted count catches this");
    }

    // --- Adversarial scenario C: mixed coverage never gets rounded up to "all independent" ---
    @Test void mixedKnownAndUnknownIndependenceNeverInflatesToFullIndependence() {
        UUID id=definition(); var d=service.definition(id);
        UUID a1=UUID.randomUUID(),a2=UUID.randomUUID(),a3=UUID.randomUUID(),a4=UUID.randomUUID(),a5=UUID.randomUUID(),a6=UUID.randomUUID();
        UUID[] ring={a1,a2,a3,a4,a5,a6,a1};
        for(int i=0;i<6;i++) service.record(id,"AGREEMENT",UUID.randomUUID(),ring[i],ring[i+1],BigDecimal.valueOf(10+i),BigDecimal.ONE,"stere",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        // Only a1 and a2 ever get assessed; a3..a6 remain completely unknown to STIR.
        UUID pa1=UUID.randomUUID(), pa2=UUID.randomUUID();
        bind(a1,pa1); bind(a2,pa2);
        when(ostris.privateContinuity(community,pa1)).thenThrow(new StirOstrisException(422,"CONTINUITY_NOT_FOUND","none"));
        when(ostris.privateContinuity(community,pa2)).thenThrow(new StirOstrisException(422,"CONTINUITY_NOT_FOUND","none"));
        independence.refresh(user,a1); independence.refresh(user,a2);
        var snapshot=service.snapshot(id);
        assertEquals(33,snapshot.get("independenceAssuranceCoveragePercent"),"only 2 of 6 accounts were ever assessed - a1/a2 resolve UNKNOWN, not independent");
        assertEquals(6,snapshot.get("adjustedIndependentParticipantCount"),"no confirmed cluster exists, so the adjusted count still matches raw diversity - never assume relatedness either");
        assertEquals(0,snapshot.get("relatedAccountClusters"));
        assertEquals("INDEPENDENCE_ASSURANCE_PARTIAL_COVERAGE",snapshot.get("identityAssurance"));
    }

    // --- Historicity: an old snapshot's independence context is not silently rewritten later ---
    @Test void historicalSnapshotRecordsWhichCommunitySequenceItUsed() {
        UUID id=definition(); var d=service.definition(id);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), pa=UUID.randomUUID(), pb=UUID.randomUUID(), riskSubject=UUID.randomUUID();
        bind(a,pa); bind(b,pb);
        when(ostris.privateContinuity(community,pa)).thenReturn(confirmed(pa,riskSubject,1L));
        when(ostris.privateContinuity(community,pb)).thenReturn(confirmed(pb,riskSubject,1L));
        independence.refresh(user,a); independence.refresh(user,b);
        service.record(id,"AGREEMENT",UUID.randomUUID(),a,b,BigDecimal.TEN,BigDecimal.ONE,"stere",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        for(int i=0;i<4;i++) service.record(id,"AGREEMENT",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BigDecimal.valueOf(10+i),BigDecimal.ONE,"stere",(String)d.get("unit_ref"),true,Instant.now().minus(Duration.ofDays(1)));
        var manifest=service.evidenceManifest(id);
        @SuppressWarnings("unchecked") var projections=(Map<String,Object>)manifest.get("independenceProjections");
        assertTrue(projections.containsKey(a.toString()));
        @SuppressWarnings("unchecked") var recorded=(Map<String,Object>)projections.get(a.toString());
        assertEquals("RELATED_CONTINUITY",recorded.get("status"));
        assertEquals(1,((Number)recorded.get("communitySequence")).intValue());
    }
}
