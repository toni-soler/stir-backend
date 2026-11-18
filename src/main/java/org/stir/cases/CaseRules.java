package org.stir.cases;

import java.time.Instant;
import java.util.Set;

/** Pure rules of the case path (AGREEMENT_CASES.md), kept apart so they can be tested without a database. */
public final class CaseRules {
    private CaseRules() {}

    public static final Set<String> CLAIMS = Set.of("NON_DELIVERY", "NON_CONFORMITY", "PARTIAL_DELIVERY");
    /** Obligations a case can be about: the ones a person performs. The economic exchange is evidenced by osTRIS. */
    public static final Set<String> CLAIMABLE_KINDS = Set.of("OBJECT", "OWNER_TASKS", "INITIATOR_TASKS");
    /** Nothing to claim about an obligation both parties confirmed, or one an amendment replaced. */
    public static final Set<String> UNCLAIMABLE_STATUSES = Set.of("DONE", "SUPERSEDED");
    public static final Set<String> EFFECTS = Set.of("ESTABLISHED", "PARTIALLY_ESTABLISHED", "NOT_ESTABLISHED");
    public static final Set<String> ECONOMIC_CORRECTIONS = Set.of("NONE", "FULL_REVERSAL");
    public static final Set<String> SUBJECT_ROLES = Set.of("OBLIGOR", "BENEFICIARY", "THIRD_PARTY", "NONE");
    public static final Set<String> ASSESSMENTS = Set.of("NO_FAULT", "GOOD_FAITH_THIRD_PARTY", "GOOD_FAITH_ERROR", "BREACH");
    public static final Set<String> SANCTION_KINDS = Set.of("WARNING");
    public static final Set<String> EVIDENCE_VISIBILITIES = Set.of("PARTIES_AND_REVIEWERS", "REVIEWERS_ONLY");
    public static final Set<String> CLOSED = Set.of("RESOLVED", "WITHDRAWN");
    public static final int MAX_CONTAINMENT_DAYS = 30;

    /** A finding can only order an economic correction for an effect it actually established. */
    public static boolean correctionAllowed(String effect, String economicCorrection) {
        return "NONE".equals(economicCorrection) || !"NOT_ESTABLISHED".equals(effect);
    }

    /** An attribution is only about an effect that happened; a breach names one of the two parties. */
    public static boolean attributionAllowed(String findingEffect, String subjectRole, String assessment) {
        if ("NOT_ESTABLISHED".equals(findingEffect)) return false;
        if ("BREACH".equals(assessment)) return "OBLIGOR".equals(subjectRole) || "BENEFICIARY".equals(subjectRole);
        if ("GOOD_FAITH_THIRD_PARTY".equals(assessment)) return "THIRD_PARTY".equals(subjectRole);
        return true;
    }

    /**
     * A decision becomes final only through a human act: an upheld appeal, both parties waiving
     * their appeal, or a reviewer confirming it once the appeal window closed with no appeal open.
     * Time alone never finalizes anything.
     */
    public static boolean finalizable(String status, Instant appealDeadline, Instant now, boolean bothPartiesWaived) {
        if (!"ISSUED".equals(status)) return false;
        return bothPartiesWaived || !now.isBefore(appealDeadline);
    }

    public static boolean appealable(String status, Instant appealDeadline, Instant now) {
        return "ISSUED".equals(status) && now.isBefore(appealDeadline);
    }
}
