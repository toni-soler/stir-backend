package org.stir.reference;

import java.math.*;
import java.time.*;
import java.util.*;

/** Pure descriptive method. Account diversity is not proof of independent people. */
public final class EvidenceAnalysis {
    private EvidenceAnalysis() {}
    public record Policy(int windowDays, int minimumObservations, int minimumParticipants,
                         BigDecimal maximumParticipantShare, int freshnessDays,
                         int minimumRelationships, BigDecimal maximumPairShare,
                         boolean independenceChecksRequired, boolean concentrationChecksRequired) {
        public Policy(int windowDays,int minimumObservations,int minimumParticipants,
                      BigDecimal maximumParticipantShare,int freshnessDays) {
            this(windowDays,minimumObservations,minimumParticipants,maximumParticipantShare,
                 freshnessDays,3,new BigDecimal("0.50"),true,true);
        }
    }
    public record Observation(UUID id, String source, UUID a, UUID b, BigDecimal amount,
                              BigDecimal quantity, String quantityUnit, String unitRef, boolean consent, Instant at) {}
    public record Result(Map<String,Object> summary, Map<String,String> exclusions, List<String> included) {}
    /** What STIR's projection of osTRIS's identity continuity currently says about one account
     * (ParticipantIndependenceService.Info, narrowed to what this pure function needs). osTRIS never
     * affirmatively certifies independence - RELATED_CONTINUITY is the only positive signal; every
     * other status (or no entry at all) means "not known to be related," never "confirmed independent." */
    public record Independence(String status, String clusterRef) {}

    public static Result analyze(List<Observation> observations, Policy policy, BigDecimal basis,
                                 String quantityUnit, String unitRef, Instant cutoff) {
        return analyze(observations,policy,basis,quantityUnit,unitRef,cutoff,Map.of());
    }
    public static Result analyze(List<Observation> observations, Policy policy, BigDecimal basis,
                                 String quantityUnit, String unitRef, Instant cutoff,
                                 Map<UUID,String> finalExclusions) {
        return analyze(observations,policy,basis,quantityUnit,unitRef,cutoff,finalExclusions,Map.of());
    }
    public static Result analyze(List<Observation> observations, Policy policy, BigDecimal basis,
                                 String quantityUnit, String unitRef, Instant cutoff,
                                 Map<UUID,String> finalExclusions, Map<UUID,Independence> independence) {
        Instant start=cutoff.minus(Duration.ofDays(policy.windowDays()));
        var excluded=new TreeMap<String,String>(); var included=new ArrayList<String>();
        var values=new ArrayList<BigDecimal>(); var participants=new HashMap<UUID,Integer>();
        var pairs=new HashMap<String,Integer>(); var days=new HashSet<java.time.LocalDate>(); Instant newest=null;
        for(var o:observations) {
            String reason=null;
            if(finalExclusions.containsKey(o.id())) reason=finalExclusions.get(o.id());
            else if(!"AGREEMENT".equals(o.source())) reason="SOURCE_NOT_AGREEMENT";
            else if(!o.consent()) reason="NO_BILATERAL_CONSENT";
            else if(o.at().isBefore(start) || !o.at().isBefore(cutoff)) reason="OUTSIDE_WINDOW";
            else if(o.amount()==null || o.quantity()==null || o.quantity().signum()<=0 ||
                    !Objects.equals(quantityUnit,o.quantityUnit()) || !Objects.equals(unitRef,o.unitRef())) reason="NOT_COMPARABLE";
            else if(o.a()==null || o.b()==null || o.a().equals(o.b())) reason="MISSING_COUNTERPARTY";
            // Confirmed same-continuity-cluster counterparties are a self-trade in disguise - the
            // Agreement stays a valid Agreement (never rewritten), but it is not independent market
            // evidence, exactly like MISSING_COUNTERPARTY above for the literal-same-account case.
            else if(clusterKey(independence,o.a()).equals(clusterKey(independence,o.b())) &&
                    isRelated(independence,o.a())) reason="RELATED_PARTICIPANT_CLUSTER";
            if(reason!=null) { excluded.put(o.id().toString(),reason); continue; }
            included.add(o.id().toString());
            values.add(o.amount().multiply(basis).divide(o.quantity(),8,RoundingMode.HALF_UP));
            participants.merge(o.a(),1,Integer::sum); participants.merge(o.b(),1,Integer::sum);
            String pair=o.a().compareTo(o.b())<0?o.a()+":"+o.b():o.b()+":"+o.a();
            pairs.merge(pair,1,Integer::sum);
            days.add(o.at().atZone(ZoneOffset.UTC).toLocalDate());
            if(newest==null || newest.isBefore(o.at())) newest=o.at();
        }
        Collections.sort(values); Collections.sort(included);
        int n=values.size();
        BigDecimal share=n==0?BigDecimal.ZERO:BigDecimal.valueOf(Collections.max(participants.values())).divide(BigDecimal.valueOf(n),4,RoundingMode.UP);
        BigDecimal pairShare=n==0?BigDecimal.ZERO:BigDecimal.valueOf(Collections.max(pairs.values())).divide(BigDecimal.valueOf(n),4,RoundingMode.UP);
        var reasons=new ArrayList<String>();
        if(n<policy.minimumObservations()) reasons.add("SMALL_SAMPLE");
        if(policy.independenceChecksRequired() && participants.size()<policy.minimumParticipants()) reasons.add("LOW_DIVERSITY");
        if(policy.concentrationChecksRequired() && share.compareTo(policy.maximumParticipantShare())>0) reasons.add("CONCENTRATED");
        if(policy.independenceChecksRequired() && pairs.size()<policy.minimumRelationships()) reasons.add("INSUFFICIENT_INDEPENDENT_RELATIONSHIPS");
        if(policy.concentrationChecksRequired() && pairShare.compareTo(policy.maximumPairShare())>0) reasons.add("REPEATED_RELATIONSHIP");
        if(newest==null || newest.isBefore(cutoff.minus(Duration.ofDays(policy.freshnessDays())))) reasons.add("STALE");

        // Account diversity is not participant independence: among the accounts behind INCLUDED
        // observations, collapse any confirmed-related accounts into one identity unit each; accounts
        // with no data, or a non-RELATED_CONTINUITY status, stay their own unit - we correct diversity
        // DOWN when we have positive evidence of relatedness, never UP by inventing independence.
        var clusterTouches=new HashMap<String,Integer>(); var clusterMembers=new HashMap<String,Set<UUID>>();
        int assessedAccounts=0;
        for(var e:participants.entrySet()) {
            String key=clusterKey(independence,e.getKey());
            clusterTouches.merge(key,e.getValue(),Integer::sum);
            clusterMembers.computeIfAbsent(key,k->new HashSet<>()).add(e.getKey());
            if(independence.containsKey(e.getKey())) assessedAccounts++;
        }
        int relatedAccountClusters=(int)clusterMembers.values().stream().filter(m->m.size()>1).count();
        int unknownIndependenceAccountCount=(int)participants.keySet().stream().filter(a->!isRelated(independence,a)).count();
        int adjustedParticipantCount=clusterTouches.size();
        BigDecimal clusterShare=n==0||clusterTouches.isEmpty()?BigDecimal.ZERO:
            BigDecimal.valueOf(Collections.max(clusterTouches.values())).divide(BigDecimal.valueOf(n),4,RoundingMode.UP);
        BigDecimal coverage=participants.isEmpty()?BigDecimal.ZERO:
            BigDecimal.valueOf(assessedAccounts).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(participants.size()),0,RoundingMode.DOWN);
        boolean haveCoverage=assessedAccounts>0;
        if(policy.independenceChecksRequired() && haveCoverage && adjustedParticipantCount<policy.minimumParticipants())
            reasons.add("INSUFFICIENT_INDEPENDENT_PARTICIPANTS");
        if(policy.concentrationChecksRequired() && haveCoverage && clusterShare.compareTo(policy.maximumParticipantShare())>0)
            reasons.add("HIGH_INDEPENDENT_PARTICIPANT_CONCENTRATION");

        boolean sufficient=reasons.isEmpty();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("status",sufficient?"SUFFICIENT_DATA":"INSUFFICIENT_DATA"); out.put("reasons",reasons);
        out.put("windowStart",start.toString()); out.put("windowEnd",cutoff.toString());
        out.put("method","AGREEMENTS_MEDIAN_IQR_V1"); out.put("source","AGREEMENT");
        out.put("identityAssurance",haveCoverage?(unknownIndependenceAccountCount==0?"INDEPENDENCE_ASSURANCE_FULL_COVERAGE":"INDEPENDENCE_ASSURANCE_PARTIAL_COVERAGE"):"ACCOUNTS_ONLY_RELATED_ACCOUNTS_UNKNOWN");
        out.put("independenceChecksRequired",policy.independenceChecksRequired());
        out.put("concentrationChecksRequired",policy.concentrationChecksRequired());
        out.put("independenceAssuranceCoveragePercent",sufficient?coverage.intValue():null);
        out.put("adjustedIndependentParticipantCount",sufficient?adjustedParticipantCount:null);
        out.put("relatedAccountClusters",sufficient?relatedAccountClusters:null);
        out.put("unknownIndependenceAccountCount",sufficient?unknownIndependenceAccountCount:null);
        // Small cohorts expose neither exact counts nor freshness, ranges or concentration ratios.
        out.put("observationCount",sufficient?n:null); out.put("participantCount",sufficient?participants.size():null);
        out.put("newestObservation",sufficient?newest.toString():null);
        out.put("maximumParticipantShare",sufficient?share.toPlainString():null);
        out.put("maximumPairShare",sufficient?pairShare.toPlainString():null);
        out.put("relationshipCount",sufficient?pairs.size():null);
        out.put("distinctUtcDays",sufficient?days.size():null);
        out.put("maximumSingleObservationMedianShift",sufficient?rounded(maxLeaveOneOutShift(values)):null);
        out.put("median",sufficient?rounded(median(values)):null);
        out.put("lowerQuartile",sufficient?rounded(values.get((n-1)/4)):null);
        out.put("upperQuartile",sufficient?rounded(values.get(3*(n-1)/4)):null);
        out.put("filters",List.of("BILATERAL_CONSENT","EXACT_QUANTITY_UNIT","EXACT_ACCOUNT_UNIT","UTC_DAILY_CUTOFF","NO_OUTLIER_TRIMMING"));
        return new Result(Collections.unmodifiableMap(out),excluded,included);
    }
    private static BigDecimal maxLeaveOneOutShift(List<BigDecimal> sorted) {
        if(sorted.size()<2) return BigDecimal.ZERO;
        BigDecimal baseline=median(sorted), maximum=BigDecimal.ZERO;
        for(int i=0;i<sorted.size();i++) {
            var without=new ArrayList<>(sorted); without.remove(i);
            maximum=maximum.max(median(without).subtract(baseline).abs());
        }
        return maximum;
    }
    private static BigDecimal median(List<BigDecimal> v) {
        int n=v.size(); return n%2==1?v.get(n/2):v.get(n/2-1).add(v.get(n/2)).divide(BigDecimal.valueOf(2));
    }
    private static String rounded(BigDecimal v) { return v.setScale(2,RoundingMode.HALF_UP).toPlainString(); }
    private static boolean isRelated(Map<UUID,Independence> independence, UUID account) {
        var info=independence.get(account); return info!=null && "RELATED_CONTINUITY".equals(info.status());
    }
    /** The identity unit an account counts toward: its confirmed cluster if known-related, otherwise
     * itself - so two unrelated (or unassessed) accounts never collide, and confirmed-related
     * accounts always collapse to the same key regardless of which one is looked up. */
    private static String clusterKey(Map<UUID,Independence> independence, UUID account) {
        var info=independence.get(account);
        return (info!=null && "RELATED_CONTINUITY".equals(info.status()) && info.clusterRef()!=null) ? info.clusterRef() : account.toString();
    }
}
