package org.stir.reference;

import java.math.*;
import java.time.*;
import java.util.*;

/** Pure descriptive method. Account diversity is not proof of independent people. */
public final class EvidenceAnalysis {
    private EvidenceAnalysis() {}
    public record Policy(int windowDays, int minimumObservations, int minimumParticipants,
                         BigDecimal maximumParticipantShare, int freshnessDays) {}
    public record Observation(UUID id, String source, UUID a, UUID b, BigDecimal amount,
                              BigDecimal quantity, String quantityUnit, String unitRef, boolean consent, Instant at) {}
    public record Result(Map<String,Object> summary, Map<String,String> exclusions, List<String> included) {}

    public static Result analyze(List<Observation> observations, Policy policy, BigDecimal basis,
                                 String quantityUnit, String unitRef, Instant cutoff) {
        Instant start=cutoff.minus(Duration.ofDays(policy.windowDays()));
        var excluded=new TreeMap<String,String>(); var included=new ArrayList<String>();
        var values=new ArrayList<BigDecimal>(); var participants=new HashMap<UUID,Integer>();
        var pairs=new HashMap<String,Integer>(); Instant newest=null;
        for(var o:observations) {
            String reason=null;
            if(!"AGREEMENT".equals(o.source())) reason="SOURCE_NOT_AGREEMENT";
            else if(!o.consent()) reason="NO_BILATERAL_CONSENT";
            else if(o.at().isBefore(start) || !o.at().isBefore(cutoff)) reason="OUTSIDE_WINDOW";
            else if(o.amount()==null || o.quantity()==null || o.quantity().signum()<=0 ||
                    !Objects.equals(quantityUnit,o.quantityUnit()) || !Objects.equals(unitRef,o.unitRef())) reason="NOT_COMPARABLE";
            else if(o.a()==null || o.b()==null || o.a().equals(o.b())) reason="MISSING_COUNTERPARTY";
            if(reason!=null) { excluded.put(o.id().toString(),reason); continue; }
            included.add(o.id().toString());
            values.add(o.amount().multiply(basis).divide(o.quantity(),8,RoundingMode.HALF_UP));
            participants.merge(o.a(),1,Integer::sum); participants.merge(o.b(),1,Integer::sum);
            String pair=o.a().compareTo(o.b())<0?o.a()+":"+o.b():o.b()+":"+o.a();
            pairs.merge(pair,1,Integer::sum);
            if(newest==null || newest.isBefore(o.at())) newest=o.at();
        }
        Collections.sort(values); Collections.sort(included);
        int n=values.size();
        BigDecimal share=n==0?BigDecimal.ZERO:BigDecimal.valueOf(Collections.max(participants.values())).divide(BigDecimal.valueOf(n),4,RoundingMode.UP);
        BigDecimal pairShare=n==0?BigDecimal.ZERO:BigDecimal.valueOf(Collections.max(pairs.values())).divide(BigDecimal.valueOf(n),4,RoundingMode.UP);
        var reasons=new ArrayList<String>();
        if(n<policy.minimumObservations()) reasons.add("SMALL_SAMPLE");
        if(participants.size()<policy.minimumParticipants()) reasons.add("LOW_DIVERSITY");
        if(share.compareTo(policy.maximumParticipantShare())>0) reasons.add("CONCENTRATED");
        if(newest==null || newest.isBefore(cutoff.minus(Duration.ofDays(policy.freshnessDays())))) reasons.add("STALE");
        boolean sufficient=reasons.isEmpty();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("status",sufficient?"SUFFICIENT_DATA":"INSUFFICIENT_DATA"); out.put("reasons",reasons);
        out.put("windowStart",start.toString()); out.put("windowEnd",cutoff.toString());
        out.put("method","AGREEMENTS_MEDIAN_IQR_V1"); out.put("source","AGREEMENT");
        out.put("identityAssurance","ACCOUNTS_ONLY_RELATED_ACCOUNTS_UNKNOWN");
        // Small cohorts expose neither exact counts nor freshness, ranges or concentration ratios.
        out.put("observationCount",sufficient?n:null); out.put("participantCount",sufficient?participants.size():null);
        out.put("newestObservation",sufficient?newest.toString():null);
        out.put("maximumParticipantShare",sufficient?share.toPlainString():null);
        out.put("maximumPairShare",sufficient?pairShare.toPlainString():null);
        out.put("median",sufficient?rounded(median(values)):null);
        out.put("lowerQuartile",sufficient?rounded(values.get((n-1)/4)):null);
        out.put("upperQuartile",sufficient?rounded(values.get(3*(n-1)/4)):null);
        out.put("filters",List.of("BILATERAL_CONSENT","EXACT_QUANTITY_UNIT","EXACT_ACCOUNT_UNIT","UTC_DAILY_CUTOFF","NO_OUTLIER_TRIMMING"));
        return new Result(Collections.unmodifiableMap(out),excluded,included);
    }
    private static BigDecimal median(List<BigDecimal> v) {
        int n=v.size(); return n%2==1?v.get(n/2):v.get(n/2-1).add(v.get(n/2)).divide(BigDecimal.valueOf(2));
    }
    private static String rounded(BigDecimal v) { return v.setScale(2,RoundingMode.HALF_UP).toPlainString(); }
}
