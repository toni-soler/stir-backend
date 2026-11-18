package org.stir.fulfilment;

import java.util.Set;

/**
 * The status of one obligation is a pure function of the latest declaration of each party
 * (AGREEMENT_FULFILMENT.md, "Estados"). STIR never decides who is right: one party saying "not
 * received" is a declaration, not a finding, so FAILED needs both parties to agree.
 */
public final class FulfilmentRules {
    public static final Set<String> OBLIGOR_TYPES = Set.of("PERFORMED", "PARTIALLY_PERFORMED", "NOT_PERFORMED");
    public static final Set<String> BENEFICIARY_TYPES = Set.of("CONFORMITY_CONFIRMED", "PARTIAL_RECEIVED", "NONCONFORMITY_REPORTED", "NOT_RECEIVED");
    public static final Set<String> NOTE_REQUIRED = Set.of("PARTIALLY_PERFORMED", "PARTIAL_RECEIVED");
    /** Kinds a participant may declare on; ECONOMIC_EXCHANGE follows osTRIS. */
    public static final Set<String> DECLARABLE_KINDS = Set.of("OBJECT", "OWNER_TASKS", "INITIATOR_TASKS");
    /** Statuses after which an obligation can no longer be replaced by an amendment. */
    public static final Set<String> SETTLED = Set.of("DONE", "FAILED", "SUPERSEDED");
    public static final Set<String> FULFILMENT_MODES = Set.of("PICKUP", "DELIVERY", "PICKUP_OR_DELIVERY", "ON_SITE", "REMOTE");

    private FulfilmentRules() {}

    /** @param obligor the obligor's latest declaration, or null; @param beneficiary the beneficiary's, or null. */
    public static String status(String obligor, String beneficiary) {
        if (beneficiary == null) return obligor == null ? "PENDING" : "DECLARED";
        return switch (beneficiary) {
            case "CONFORMITY_CONFIRMED" -> "DONE";
            case "PARTIAL_RECEIVED" -> "PARTIAL";
            case "NOT_RECEIVED" -> "NOT_PERFORMED".equals(obligor) ? "FAILED" : "IN_DISAGREEMENT";
            case "NONCONFORMITY_REPORTED" -> "IN_DISAGREEMENT";
            default -> throw new IllegalArgumentException("Unknown beneficiary declaration " + beneficiary);
        };
    }
}
