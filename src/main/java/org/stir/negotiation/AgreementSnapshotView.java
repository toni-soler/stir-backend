package org.stir.negotiation;

import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Party-visible view of a frozen snapshot. canonicalJson intentionally carries the embedded nonce
 * field: the two parties to the Agreement are entitled to the exact canonical bytes so each can
 * independently recompute SHA-256(canonicalJson) and confirm it equals digestSha256 - that
 * reproducibility is the whole point of a hash commitment. What this view does NOT do is expose
 * the nonce (or these bytes) as a REDUNDANT top-level field, or to anyone but the two parties:
 * AgreementService/AgreementController only ever resolve this for negotiation.initiatorId/ownerId
 * (404 otherwise), so a stranger never reaches this DTO at all.
 */
public record AgreementSnapshotView(int schemaVersion, String canonicalJson, String digestSha256, Instant createdAt,
                                    String externalContractNamespace, String externalContractDigest) {
    private static final ObjectMapper JSON = new ObjectMapper();
    static AgreementSnapshotView of(AgreementSnapshot snapshot) {
        try {
            var canonical = JSON.readTree(snapshot.canonicalJson);
            return new AgreementSnapshotView(snapshot.schemaVersion, snapshot.canonicalJson, snapshot.digestSha256,
                snapshot.createdAt, text(canonical, "externalContractNamespace"), text(canonical, "externalContractDigest"));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Stored agreement snapshot is not valid JSON", ex);
        }
    }
    private static String text(com.fasterxml.jackson.databind.JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).asText() : null;
    }
}
