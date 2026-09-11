package org.stir.negotiation;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Freezes the accepted commercial terms into an immutable, canonical, hashable record. This is a
 * contractual commitment source, not economic completion: no osTRIS call happens here. See
 * OSTRIS_INTEGRATION.md for the proposed downstream EXCHANGE encoding this snapshot feeds later.
 */
@Service
public class AgreementSnapshotService {
    public static final int SCHEMA_VERSION = 1;
    private final AgreementSnapshotRepository snapshots;
    private final SecureRandom random = new SecureRandom();

    public AgreementSnapshotService(AgreementSnapshotRepository snapshots) { this.snapshots=snapshots; }

    public AgreementSnapshot freeze(Agreement agreement, Negotiation negotiation, Offer acceptedOffer, String listingDirection) {
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        var fields = fields(agreement, negotiation, acceptedOffer, listingDirection, nonce);
        byte[] bytes = CanonicalJson.canonicalBytes(fields);
        var snapshot = new AgreementSnapshot();
        snapshot.id = UUID.randomUUID();
        snapshot.tenantId = agreement.tenantId;
        snapshot.agreementId = agreement.id;
        snapshot.schemaVersion = SCHEMA_VERSION;
        snapshot.canonicalJson = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        snapshot.nonce = nonce;
        snapshot.digestSha256 = CanonicalJson.sha256Hex(bytes);
        snapshot.createdAt = Instant.now();
        return snapshots.saveAndFlush(snapshot);
    }

    /** Package-visible so CanonicalJsonTest can build identical field maps without persistence. */
    static Map<String, Object> fields(Agreement agreement, Negotiation negotiation, Offer offer, String listingDirection, String nonce) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("agreementId", agreement.id.toString());
        fields.put("negotiationId", negotiation.id.toString());
        fields.put("listingId", negotiation.listingId.toString());
        fields.put("listingDirection", listingDirection);
        fields.put("initiatorId", negotiation.initiatorId.toString());
        fields.put("ownerId", negotiation.ownerId.toString());
        fields.put("acceptedOfferId", offer.id.toString());
        fields.put("message", offer.message);
        fields.put("quantity", offer.quantity == null ? null : offer.quantity.toPlainString());
        fields.put("unitLabel", offer.unitLabel);
        fields.put("proposedAmount", offer.proposedAmount == null ? null : offer.proposedAmount.toPlainString());
        fields.put("proposedUnitRef", offer.proposedUnitRef);
        fields.put("terms", offer.terms);
        fields.put("acceptedAt", Objects.toString(agreement.createdAt, null));
        fields.put("nonce", nonce);
        return fields;
    }

    private byte[] randomBytes(int length) { byte[] bytes = new byte[length]; random.nextBytes(bytes); return bytes; }
}
