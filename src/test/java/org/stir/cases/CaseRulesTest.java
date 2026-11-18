package org.stir.cases;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaseRulesTest {
    final Instant now = Instant.parse("2026-11-18T12:00:00Z");

    @Test void timeAloneNeverFinalizesButAClosedWindowLetsAReviewerConfirm() {
        Instant deadline = now.plus(1, ChronoUnit.HOURS);
        assertFalse(CaseRules.finalizable("ISSUED", deadline, now, false));
        assertTrue(CaseRules.finalizable("ISSUED", deadline, now, true));
        assertTrue(CaseRules.finalizable("ISSUED", deadline, deadline, false));
        assertFalse(CaseRules.finalizable("APPEALED", deadline, deadline.plusSeconds(1), true), "an open appeal is decided first");
        assertFalse(CaseRules.finalizable("FINAL", deadline, deadline, true));
    }

    @Test void appealsAreOpenOnlyWhileTheWindowIs() {
        Instant deadline = now.plus(1, ChronoUnit.HOURS);
        assertTrue(CaseRules.appealable("ISSUED", deadline, now));
        assertFalse(CaseRules.appealable("ISSUED", deadline, deadline));
        assertFalse(CaseRules.appealable("APPEALED", deadline, now));
    }

    @Test void correctionsAndAttributionsFollowTheFinding() {
        assertTrue(CaseRules.correctionAllowed("ESTABLISHED", "FULL_REVERSAL"));
        assertTrue(CaseRules.correctionAllowed("NOT_ESTABLISHED", "NONE"));
        assertFalse(CaseRules.correctionAllowed("NOT_ESTABLISHED", "FULL_REVERSAL"));
        assertTrue(CaseRules.attributionAllowed("ESTABLISHED", "OBLIGOR", "BREACH"));
        assertFalse(CaseRules.attributionAllowed("ESTABLISHED", "THIRD_PARTY", "BREACH"));
        assertFalse(CaseRules.attributionAllowed("ESTABLISHED", "OBLIGOR", "GOOD_FAITH_THIRD_PARTY"));
        assertTrue(CaseRules.attributionAllowed("PARTIALLY_ESTABLISHED", "THIRD_PARTY", "GOOD_FAITH_THIRD_PARTY"));
        assertTrue(CaseRules.attributionAllowed("ESTABLISHED", "NONE", "NO_FAULT"));
        assertFalse(CaseRules.attributionAllowed("NOT_ESTABLISHED", "NONE", "NO_FAULT"));
    }

    @Test void theOnlySanctionIsAWarningAndOnlyPeoplesObligationsAreClaimable() {
        assertEquals(java.util.Set.of("WARNING"), CaseRules.SANCTION_KINDS);
        assertFalse(CaseRules.CLAIMABLE_KINDS.contains("ECONOMIC_EXCHANGE"));
    }
}
