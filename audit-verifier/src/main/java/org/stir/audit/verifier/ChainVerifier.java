package org.stir.audit.verifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Structural chain integrity - identical logic for every domain, deliberately domain-agnostic.
 * This is the core "tamper-evident" property this MVP demonstrates: a gap, a reordered/rewritten
 * event, or a hash that does not match its own stored inputs is detectable without knowing
 * anything about Seven Keys, Market Integrity or any other business rule. */
public final class ChainVerifier {
    private final AuditSql sql;
    ChainVerifier(AuditSql sql) { this.sql = sql; }

    sealed interface Result permits Ok, Broken {}
    record Ok() implements Result {}
    record Broken(String reasonCode, String detail) implements Result {}

    /** Verifies one event against its immediate predecessor in the same (tenant,domain) stream.
     * `previous` is null only for sequence=1, where continuity instead means previousHash must
     * equal the deterministic genesis for that stream. */
    Result verifyLink(MutationEvent event, MutationEvent previous) throws java.sql.SQLException {
        if (event.sequence() == 1) {
            byte[] genesis = sql.genesisHash(event.tenantId(), event.domain());
            if (!Arrays.equals(genesis, event.previousHash())) {
                return new Broken("GENESIS_MISMATCH", "sequence=1 previous_hash does not equal the deterministic genesis for this stream");
            }
        } else {
            if (previous == null) {
                return new Broken("MISSING_PREDECESSOR", "sequence=" + event.sequence() + " has no predecessor event fetched for continuity check (possible gap)");
            }
            if (previous.sequence() != event.sequence() - 1) {
                return new Broken("SEQUENCE_GAP", "expected predecessor sequence=" + (event.sequence() - 1) + " but found sequence=" + previous.sequence());
            }
            if (!Arrays.equals(previous.currentHash(), event.previousHash())) {
                return new Broken("HASH_DISCONTINUITY", "event.previous_hash does not equal predecessor.current_hash");
            }
        }
        byte[] recomputed = sql.recomputeEventHash(event);
        if (!Arrays.equals(recomputed, event.currentHash())) {
            return new Broken("STORED_HASH_MISMATCH", "recomputing compute_event_hash from this event's own stored fields does not reproduce its stored current_hash - the row's fields were altered by a table/function owner without recomputing the hash");
        }
        return new Ok();
    }

    /** Walks an entire stream from sequence 1 (or from a given resume point) verifying every link
     * in order; stops and reports the first break, since later events in a broken stream are not
     * meaningfully verifiable against a chain that has already diverged. */
    Result verifyStreamFrom(UUID tenant, String domain, long fromSequenceInclusive) throws java.sql.SQLException {
        List<MutationEvent> events = sql.fetchStreamFrom(tenant, domain, Math.max(1, fromSequenceInclusive - 1));
        MutationEvent previous = events.isEmpty() ? null : (fromSequenceInclusive <= 1 ? null : events.get(0));
        int startIdx = (fromSequenceInclusive <= 1) ? 0 : 1;
        for (int i = startIdx; i < events.size(); i++) {
            MutationEvent current = events.get(i);
            Result r = verifyLink(current, previous);
            if (r instanceof Broken) return r;
            previous = current;
        }
        return new Ok();
    }

    /** P1-RA-007: the periodic full-reconciliation result. `verifiedThroughSequence`/`verifiedHead`
     * are the last link this call actually confirmed - the caller (VerifierLoop's periodic pass)
     * persists them as the new stir_audit.chain_reconciliation_checkpoint only when the walk
     * reached the live stream_head cleanly, so a broken stream never advances its own checkpoint
     * past the break (the same break would otherwise go undetected on every later cycle). */
    record ReconciliationOutcome(Result result, long verifiedThroughSequence, byte[] verifiedHead) {}

    /** Walks from `fromSequenceInclusive` through the CURRENT live stream_head (not just however
     * many event rows exist), and additionally confirms the last walked event's current_hash still
     * equals the live stream_head.head_hash - catching a head tampered independently of any single
     * event row, or an event silently inserted/re-pointed without going through the trigger.
     *
     * P1-R2 race fix: the head read and the events read now share ONE
     * `REPEATABLE READ, READ ONLY` snapshot (`AuditSql.beginRepeatableReadSnapshot`) instead of two
     * separate autocommit calls - PostgreSQL's MVCC snapshot, fixed at the transaction's first
     * statement, guarantees both reflect the exact same instant. A writer that commits a new
     * event AFTER this snapshot is taken is simply invisible to this pass entirely (correctly
     * deferred to the next reconciliation cycle), never partially visible to one read and not the
     * other - no application-level lock of any kind. */
    ReconciliationOutcome reconcileStreamFrom(UUID tenant, String domain, long fromSequenceInclusive) throws java.sql.SQLException {
        long liveSequence; byte[] liveHash; List<MutationEvent> events;
        sql.beginRepeatableReadSnapshot();
        try {
            var liveHead = sql.currentStreamHead(tenant, domain);
            liveSequence = liveHead.getKey();
            liveHash = liveHead.getValue();
            events = sql.fetchStreamFrom(tenant, domain, Math.max(1, fromSequenceInclusive - 1));
        } finally {
            sql.endRepeatableReadSnapshot();
        }
        MutationEvent previous = events.isEmpty() ? null : (fromSequenceInclusive <= 1 ? null : events.get(0));
        int startIdx = (fromSequenceInclusive <= 1) ? 0 : 1;
        long verifiedThrough = fromSequenceInclusive - 1;
        byte[] verifiedHead = (fromSequenceInclusive <= 1) ? null : (previous != null ? previous.currentHash() : null);

        for (int i = startIdx; i < events.size(); i++) {
            MutationEvent current = events.get(i);
            Result r = verifyLink(current, previous);
            if (r instanceof Broken) return new ReconciliationOutcome(r, verifiedThrough, verifiedHead);
            verifiedThrough = current.sequence();
            verifiedHead = current.currentHash();
            previous = current;
        }

        if (verifiedThrough != liveSequence) {
            return new ReconciliationOutcome(
                new Broken("EXPECTED_EVENT_MISSING", "walked through sequence " + verifiedThrough +
                    " but stream_head reports sequence " + liveSequence + " - an event this stream's head claims to have is not observable (deleted, or head advanced without a corresponding event row)"),
                verifiedThrough, verifiedHead);
        }
        if (verifiedHead != null && !Arrays.equals(verifiedHead, liveHash)) {
            return new ReconciliationOutcome(
                new Broken("STREAM_HEAD_MISMATCH", "last walked event's current_hash does not equal stream_head.head_hash for this stream - the head was altered independently of its events"),
                verifiedThrough, verifiedHead);
        }
        return new ReconciliationOutcome(new Ok(), verifiedThrough, verifiedHead);
    }
}
