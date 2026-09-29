package org.stir.audit.verifier;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only mirror of a stir_audit.mutation_event row - the verifier never constructs one of
 * these to write back; it only ever reads what the trigger already persisted. `txid`/`clientAddr`
 * are read back as their exact canonical text form (see V19's compute_event_hash_v2 comment) so
 * recomputing a V2 event's hash never depends on a JDBC driver's own xid8/inet encoding. */
public record MutationEvent(
    UUID auditEventId, UUID tenantId, String domain, long sequence, OffsetDateTime dbTime,
    String dbTimeCanonical, long tableOid, String tableName, String operation,
    String entityKeyCanonical, UUID communityId, byte[] oldRowDigest, byte[] newRowDigest,
    String rowDigestProfile, String sessionUserName, String sessionRoleReported,
    String applicationName, String txidText, String clientAddrText, String requestId,
    String correlationId, String authorizationId, String proposalId, byte[] previousHash,
    byte[] currentHash, String eventFormatVersion
) {}
