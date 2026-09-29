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
}
