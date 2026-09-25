package org.stir.reference;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceAnalysisTest {
    final Instant cutoff=Instant.parse("2026-09-25T00:00:00Z");
    final EvidenceAnalysis.Policy policy=new EvidenceAnalysis.Policy(90,5,6,new BigDecimal("0.40"),30);
    EvidenceAnalysis.Observation observation(UUID a,UUID b,String amount,Instant at,String source) {
        return new EvidenceAnalysis.Observation(UUID.randomUUID(),source,a,b,new BigDecimal(amount),BigDecimal.ONE,"hour","unit",true,at);
    }
    EvidenceAnalysis.Result analyze(List<EvidenceAnalysis.Observation> observations) {
        return EvidenceAnalysis.analyze(observations,policy,BigDecimal.ONE,"hour","unit",cutoff);
    }
    List<EvidenceAnalysis.Observation> independent(String...amounts) {
        return Arrays.stream(amounts).map(a->observation(UUID.randomUUID(),UUID.randomUUID(),a,cutoff.minusSeconds(3600),"AGREEMENT")).toList();
    }
    @Test void fiveDealsBetweenSameTwoAccountsAreInsufficient() {
        var a=UUID.randomUUID();var b=UUID.randomUUID();var rows=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<5;i++)rows.add(observation(a,b,"10",cutoff.minusSeconds(100),"AGREEMENT"));
        var result=analyze(rows).summary(); assertEquals("INSUFFICIENT_DATA",result.get("status"));assertNull(result.get("median"));
        assertTrue(((List<?>)result.get("reasons")).containsAll(List.of("LOW_DIVERSITY","CONCENTRATED")));
    }
    @Test void twoObservationsOrOneExtremeDoNotFabricateBands() {
        for(var rows:List.of(independent("10","20"),independent("9999999"))) {
            var result=analyze(rows).summary();assertNull(result.get("median"));assertNull(result.get("lowerQuartile"));assertNull(result.get("observationCount"));
        }
    }
    @Test void extremeListingNeverBecomesAnAcceptedTransaction() {
        var rows=new ArrayList<>(independent("10","11","12","13","14"));
        var listing=observation(UUID.randomUUID(),UUID.randomUUID(),"9999999",cutoff.minusSeconds(100),"LISTING");rows.add(listing);
        var result=analyze(rows);assertEquals("12.00",result.summary().get("median"));assertEquals("SOURCE_NOT_AGREEMENT",result.exclusions().get(listing.id().toString()));
    }
    @Test void extremesAreRetainedAndRealAvailabilityChangesMoveTheDistribution() {
        var result=analyze(independent("10","11","12","13","99999"));assertEquals(5,result.included().size());assertEquals("12.00",result.summary().get("median"));
        assertEquals("32.00",analyze(independent("30","31","32","33","34")).summary().get("median"));
    }
    @Test void enoughAccountsButOneDominantCounterpartyStillFails() {
        UUID hub=UUID.randomUUID();var rows=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<10;i++)rows.add(observation(hub,UUID.randomUUID(),"10",cutoff.minusSeconds(100),"AGREEMENT"));
        assertTrue(((List<?>)analyze(rows).summary().get("reasons")).contains("CONCENTRATED"));
    }
    @Test void staleAndExcludedObservationsHaveExplicitReasons() {
        var old=observation(UUID.randomUUID(),UUID.randomUUID(),"10",cutoff.minus(Duration.ofDays(100)),"AGREEMENT");
        assertEquals("OUTSIDE_WINDOW",analyze(List.of(old)).exclusions().get(old.id().toString()));
        var stale=independent("10","11","12","13","14").stream().map(o->observation(o.a(),o.b(),"10",cutoff.minus(Duration.ofDays(31)),"AGREEMENT")).toList();
        assertTrue(((List<?>)analyze(stale).summary().get("reasons")).contains("STALE"));
    }
    @Test void unrelatedLookingSybilAccountsAreNotSilentlyCalledIndependentPeople() {
        var result=analyze(independent("1","1","1","1","1"));
        assertEquals("ACCOUNTS_ONLY_RELATED_ACCOUNTS_UNKNOWN",result.summary().get("identityAssurance"));
    }
    @Test void missingConsentAndIncompatibleUnitsAreNeverInferred() {
        var o=independent("10").getFirst();
        var no=new EvidenceAnalysis.Observation(o.id(),o.source(),o.a(),o.b(),o.amount(),o.quantity(),"hour","unit",false,o.at());
        assertEquals("NO_BILATERAL_CONSENT",analyze(List.of(no)).exclusions().get(no.id().toString()));
        var bad=new EvidenceAnalysis.Observation(o.id(),o.source(),o.a(),o.b(),o.amount(),o.quantity(),"kg","euro",true,o.at());
        assertEquals("NOT_COMPARABLE",analyze(List.of(bad)).exclusions().get(bad.id().toString()));
    }
    @Test void twentyRepeatedDealsFailWhileTwentyDistinctRelationsCanDescribeValues() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var repeated=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<20;i++) repeated.add(observation(a,b,"50",cutoff.minusSeconds(3600L*(i+1)),"AGREEMENT"));
        var r=analyze(repeated).summary();
        assertEquals("INSUFFICIENT_DATA",r.get("status"));
        assertTrue(((List<?>)r.get("reasons")).contains("REPEATED_RELATIONSHIP"));
        var distinct=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<20;i++) distinct.add(observation(UUID.randomUUID(),UUID.randomUUID(),"50",cutoff.minusSeconds(7200L*(i+1)),"AGREEMENT"));
        var d=analyze(distinct).summary();
        assertEquals("SUFFICIENT_DATA",d.get("status"));
        assertEquals(20,d.get("relationshipCount"));
        assertTrue(((Number)d.get("distinctUtcDays")).intValue()>1);
        assertEquals("0.00",d.get("maximumSingleObservationMedianShift"));
    }
    @Test void circularActivityIsVisibleAsRepeatedRelationsWithoutCallingItFraud() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        var rows=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<18;i++) {
            UUID first=i%3==0?a:i%3==1?b:c,second=i%3==0?b:i%3==1?c:a;
            rows.add(observation(first,second,"10",cutoff.minusSeconds(3600L*(i+1)),"AGREEMENT"));
        }
        var summary=analyze(rows).summary();
        assertEquals("INSUFFICIENT_DATA",summary.get("status"));
        assertTrue(((List<?>)summary.get("reasons")).contains("LOW_DIVERSITY"));
        assertNull(summary.get("median"));
    }
    // --- Participant independence: account diversity is not participant independence ---
    EvidenceAnalysis.Result analyzeWithIndependence(List<EvidenceAnalysis.Observation> observations,Map<UUID,EvidenceAnalysis.Independence> independence) {
        return EvidenceAnalysis.analyze(observations,policy,BigDecimal.ONE,"hour","unit",cutoff,Map.of(),independence);
    }
    @Test void withoutIndependenceDataIdentityAssuranceStaysTheHonestPlaceholder() {
        var result=analyze(independent("10","11","12","13","14","15")).summary();
        assertEquals("ACCOUNTS_ONLY_RELATED_ACCOUNTS_UNKNOWN",result.get("identityAssurance"));
        assertEquals(0,result.get("independenceAssuranceCoveragePercent"),"zero coverage is meaningful information, not suppressed like a small cohort's counts");
    }
    @Test void confirmedSameClusterCounterpartyIsExcludedAsRelatedNotIndependentEvidence() {
        // Scenario D: two different STIR accounts controlled by the same continuity cluster trading
        // with each other is a self-trade in disguise - excluded per-observation, the Agreement's raw
        // row is untouched (never rewritten, only excluded from this snapshot's eligible evidence).
        UUID x1=UUID.randomUUID(),x2=UUID.randomUUID();
        var selfTrade=observation(x1,x2,"9999",cutoff.minusSeconds(100),"AGREEMENT");
        var independence=Map.of(x1,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"),
                                 x2,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"));
        var result=analyzeWithIndependence(List.of(selfTrade),independence);
        assertEquals("RELATED_PARTICIPANT_CLUSTER",result.exclusions().get(selfTrade.id().toString()));
        assertFalse(result.included().contains(selfTrade.id().toString()));
    }
    @Test void differentClustersTradingWithEachOtherAreStillIndependentEvidence() {
        UUID x1=UUID.randomUUID(),x2=UUID.randomUUID();
        var trade=observation(x1,x2,"10",cutoff.minusSeconds(100),"AGREEMENT");
        var independence=Map.of(x1,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"),
                                 x2,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-b"));
        var result=analyzeWithIndependence(List.of(trade),independence);
        assertNull(result.exclusions().get(trade.id().toString()));
        assertTrue(result.included().contains(trade.id().toString()));
    }
    @Test void rawDiversityAtExactlyTheFloorIsCorrectedDownByAConfirmedCluster() {
        // Scenario A/B: a1..a6 form a ring (6 distinct accounts, 6 relationships) - raw counting
        // alone reads this as exactly meeting the diversity floor. a1 and a4 are never directly
        // paired, so raw pair-based checks cannot see they are the same continuity cluster.
        UUID a1=UUID.randomUUID(),a2=UUID.randomUUID(),a3=UUID.randomUUID(),a4=UUID.randomUUID(),a5=UUID.randomUUID(),a6=UUID.randomUUID();
        UUID[] ring={a1,a2,a3,a4,a5,a6,a1};
        var rows=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<6;i++) rows.add(observation(ring[i],ring[i+1],String.valueOf(10+i),cutoff.minusSeconds(3600L*(i+1)),"AGREEMENT"));
        var withoutIndependence=analyze(rows).summary();
        assertEquals("SUFFICIENT_DATA",withoutIndependence.get("status"));
        assertFalse(((List<?>)withoutIndependence.get("reasons")).contains("LOW_DIVERSITY"));
        var independence=Map.of(a1,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"),
                                 a4,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"));
        var withIndependence=analyzeWithIndependence(rows,independence).summary();
        assertEquals("INSUFFICIENT_DATA",withIndependence.get("status"));
        assertTrue(((List<?>)withIndependence.get("reasons")).contains("INSUFFICIENT_INDEPENDENT_PARTICIPANTS"));
        assertFalse(((List<?>)withIndependence.get("reasons")).contains("LOW_DIVERSITY"),"raw diversity never changes retroactively - only the adjusted count catches this");
        // Below the floor, exact counts are suppressed the same way median/participantCount already
        // are for any small cohort - a below-floor identity-assurance breakdown is not exempt from
        // that privacy rule either. identityAssurance itself is not count-shaped, so - like the
        // original ACCOUNTS_ONLY_RELATED_ACCOUNTS_UNKNOWN placeholder it replaces - it is not
        // suppressed: only 2 of the 6 accounts (a1, a4) were ever assessed, so this stays PARTIAL,
        // never FULL, even though those two happen to be the only related ones.
        assertNull(withIndependence.get("adjustedIndependentParticipantCount"));
        assertEquals("INDEPENDENCE_ASSURANCE_PARTIAL_COVERAGE",withIndependence.get("identityAssurance"));
    }
    @Test void splittingConcentrationAcrossTwoClusterMembersStillShowsAsConcentrated() {
        // Scenario F: a single actor spreads its dominance across two accounts of the same cluster,
        // each individually under the raw concentration floor - raw CONCENTRATED misses this, the
        // cluster-aware check does not.
        UUID x1=UUID.randomUUID(),x2=UUID.randomUUID();
        var rows=new ArrayList<EvidenceAnalysis.Observation>();
        for(int i=0;i<3;i++) rows.add(observation(x1,UUID.randomUUID(),"10",cutoff.minusSeconds(3600L*(i+1)),"AGREEMENT"));
        for(int i=0;i<3;i++) rows.add(observation(x2,UUID.randomUUID(),"10",cutoff.minusSeconds(3600L*(i+4)),"AGREEMENT"));
        for(int i=0;i<4;i++) rows.add(observation(UUID.randomUUID(),UUID.randomUUID(),"10",cutoff.minusSeconds(3600L*(i+7)),"AGREEMENT"));
        var withoutIndependence=analyze(rows).summary();
        assertFalse(((List<?>)withoutIndependence.get("reasons")).contains("CONCENTRATED"),"each account alone stays under the raw share floor");
        var independence=Map.of(x1,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"),
                                 x2,new EvidenceAnalysis.Independence("RELATED_CONTINUITY","cluster-a"));
        var withIndependence=analyzeWithIndependence(rows,independence).summary();
        assertTrue(((List<?>)withIndependence.get("reasons")).contains("HIGH_INDEPENDENT_PARTICIPANT_CONCENTRATION"));
    }
    @Test void mixedKnownAndUnknownIndependenceIsNeverRoundedUpToFullIndependence() {
        // Scenario C: 10 accounts with real independence data, several more with none at all -
        // unknown accounts must never be silently counted as confirmed-independent.
        var rows=new ArrayList<EvidenceAnalysis.Observation>();
        UUID known1=UUID.randomUUID(),known2=UUID.randomUUID();
        rows.add(observation(known1,UUID.randomUUID(),"10",cutoff.minusSeconds(3600),"AGREEMENT"));
        rows.add(observation(known2,UUID.randomUUID(),"11",cutoff.minusSeconds(7200),"AGREEMENT"));
        for(int i=0;i<4;i++) rows.add(observation(UUID.randomUUID(),UUID.randomUUID(),String.valueOf(12+i),cutoff.minusSeconds(3600L*(i+3)),"AGREEMENT"));
        var independence=Map.of(known1,new EvidenceAnalysis.Independence("INDEPENDENCE_UNKNOWN",null),
                                 known2,new EvidenceAnalysis.Independence("IDENTITY_CONTINUITY_PENDING",null));
        var result=analyzeWithIndependence(rows,independence).summary();
        assertEquals("SUFFICIENT_DATA",result.get("status"));
        assertEquals(0,result.get("relatedAccountClusters"));
        assertEquals("INDEPENDENCE_ASSURANCE_PARTIAL_COVERAGE",result.get("identityAssurance"));
        assertEquals(result.get("participantCount"),result.get("adjustedIndependentParticipantCount"),
            "no confirmed relation exists - the adjusted count must equal raw diversity, never invent either more or less independence");
    }
    @Test void finalFindingExcludesOnlyFutureEvidenceAndLeavesRawAgreement() {
        var rows=new ArrayList<>(independent("10","11","12","13","1000","14"));
        var before=analyze(rows);
        assertEquals(6,before.included().size());
        var finalOnly=EvidenceAnalysis.analyze(rows,policy,BigDecimal.ONE,"hour","unit",cutoff,
            Map.of(rows.get(4).id(),"FINAL_INTEGRITY_FINDING:RELATED_PARTICIPANT_CLUSTER"));
        assertEquals(6,rows.size());
        assertEquals(5,finalOnly.included().size());
        assertEquals("FINAL_INTEGRITY_FINDING:RELATED_PARTICIPANT_CLUSTER",
            finalOnly.exclusions().get(rows.get(4).id().toString()));
        assertEquals("12.00",finalOnly.summary().get("median"));
    }
}
