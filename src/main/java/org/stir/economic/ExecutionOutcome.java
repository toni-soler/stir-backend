package org.stir.economic;

import java.util.Set;

/**
 * What did not happen, and why, as a typed outcome (AGREEMENT_FULFILMENT.md, "Resultados de
 * ejecución"). A restriction that worked as designed, a description error, a system fault and an
 * unknown cause are different outcomes. A code this table does not name is UNKNOWN: a
 * classification never claims more than is known.
 */
public enum ExecutionOutcome {
    COMMITTED, EXPOSURE_REFUSAL, AUTHORISATION_INCOMPLETE, INVALID_DESCRIPTION, IMPLEMENTATION_FAULT, UNKNOWN;

    private static final Set<String> INVALID_DESCRIPTION_CODES = Set.of(
        "ACCOUNT_SCOPE_MISMATCH", "INVALID_AMOUNT", "AMOUNT_TOO_LARGE", "NON_ZERO_SUM", "PROPOSAL_INTENT_MISMATCH");
    private static final Set<String> IMPLEMENTATION_FAULT_CODES = Set.of(
        "PROTOCOL_DIGEST_MISMATCH", "PROJECTION_DRIFT", TradeService.INTENT_MISMATCH_CODE);

    /** Classifies one failed osTRIS call by its own code and HTTP status. */
    public static ExecutionOutcome classify(int httpStatus, String code) {
        if (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500) return UNKNOWN;
        if ("CREDIT_FLOOR_EXCEEDED".equals(code)) return EXPOSURE_REFUSAL;
        if ("CONTROL_POLICY_NOT_SATISFIED".equals(code)) return AUTHORISATION_INCOMPLETE;
        if (code != null && INVALID_DESCRIPTION_CODES.contains(code)) return INVALID_DESCRIPTION;
        if (code != null && IMPLEMENTATION_FAULT_CODES.contains(code)) return IMPLEMENTATION_FAULT;
        // A 400/404 about a transaction STIR itself created is a contract/consistency anomaly.
        if (httpStatus == 400 || httpStatus == 404) return IMPLEMENTATION_FAULT;
        return UNKNOWN;
    }
}
