package org.stir.fulfilment;

import org.junit.jupiter.api.Test;
import org.stir.economic.ExecutionOutcome;
import static org.junit.jupiter.api.Assertions.*;

class FulfilmentRulesTest {
    @Test void statusFollowsTheLatestDeclarationOfEachParty() {
        assertEquals("PENDING", FulfilmentRules.status(null, null));
        assertEquals("DECLARED", FulfilmentRules.status("PERFORMED", null));
        assertEquals("DECLARED", FulfilmentRules.status("PARTIALLY_PERFORMED", null));
        assertEquals("DONE", FulfilmentRules.status("PERFORMED", "CONFORMITY_CONFIRMED"));
        assertEquals("DONE", FulfilmentRules.status(null, "CONFORMITY_CONFIRMED"));
        assertEquals("PARTIAL", FulfilmentRules.status("PARTIALLY_PERFORMED", "PARTIAL_RECEIVED"));
        assertEquals("IN_DISAGREEMENT", FulfilmentRules.status("PERFORMED", "NONCONFORMITY_REPORTED"));
        assertEquals("IN_DISAGREEMENT", FulfilmentRules.status("PERFORMED", "NOT_RECEIVED"));
    }

    @Test void oneSidedNonPerformanceIsADeclarationNotAFailure() {
        assertEquals("IN_DISAGREEMENT", FulfilmentRules.status(null, "NOT_RECEIVED"));
        assertEquals("DECLARED", FulfilmentRules.status("NOT_PERFORMED", null));
        assertEquals("FAILED", FulfilmentRules.status("NOT_PERFORMED", "NOT_RECEIVED"));
    }

    @Test void executionOutcomesAreClassifiedWithoutClaimingMoreThanIsKnown() {
        assertEquals(ExecutionOutcome.EXPOSURE_REFUSAL, ExecutionOutcome.classify(422, "CREDIT_FLOOR_EXCEEDED"));
        assertEquals(ExecutionOutcome.AUTHORISATION_INCOMPLETE, ExecutionOutcome.classify(422, "CONTROL_POLICY_NOT_SATISFIED"));
        assertEquals(ExecutionOutcome.INVALID_DESCRIPTION, ExecutionOutcome.classify(422, "ACCOUNT_SCOPE_MISMATCH"));
        assertEquals(ExecutionOutcome.IMPLEMENTATION_FAULT, ExecutionOutcome.classify(422, "PROTOCOL_DIGEST_MISMATCH"));
        assertEquals(ExecutionOutcome.IMPLEMENTATION_FAULT, ExecutionOutcome.classify(404, "UNKNOWN_PROPOSAL"));
        assertEquals(ExecutionOutcome.IMPLEMENTATION_FAULT, ExecutionOutcome.classify(409, "EXECUTION_INTENT_MISMATCH"));
        // Transport failures and codes this table does not name stay UNKNOWN.
        assertEquals(ExecutionOutcome.UNKNOWN, ExecutionOutcome.classify(503, "CREDIT_FLOOR_EXCEEDED"));
        assertEquals(ExecutionOutcome.UNKNOWN, ExecutionOutcome.classify(429, null));
        assertEquals(ExecutionOutcome.UNKNOWN, ExecutionOutcome.classify(422, "SOMETHING_NEW"));
        assertEquals(ExecutionOutcome.UNKNOWN, ExecutionOutcome.classify(403, "CONTROLLER_NOT_OWNED"));
    }
}
