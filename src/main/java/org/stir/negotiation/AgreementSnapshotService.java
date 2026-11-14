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
 * Freezes the accepted commercial terms into an immutable, canonical, hashable record - format
 * "STIR-AGREEMENT-JCS-1" (real RFC 8785 JCS via CanonicalJson, not schema v1's hand-rolled sorted-
 * key approximation). This is a contractual commitment source, not economic completion: no osTRIS
 * call happens here, but this digest is exactly what later becomes contractualMetadataDigest on
 * the osTRIS EXCHANGE proposal (see org.stir.economic.TradeService / OSTRIS_INTEGRATION.md).
 */
@Service
public class AgreementSnapshotService {
    /** 4 adds the structured terms and openQuestionsAcknowledged (STRUCTURED_TERMS_AND_NEEDS.md);
     * snapshots frozen under 3 keep their bytes and digest. */
    public static final int SCHEMA_VERSION = 4;
    private final AgreementSnapshotRepository snapshots;
    private final SecureRandom random = new SecureRandom();

    public AgreementSnapshotService(AgreementSnapshotRepository snapshots) { this.snapshots=snapshots; }

    public AgreementSnapshot freeze(Agreement agreement, Negotiation negotiation, Offer acceptedOffer, String listingDirection) {
        return freeze(agreement, negotiation, acceptedOffer, listingDirection, false);
    }
    public AgreementSnapshot freeze(Agreement agreement, Negotiation negotiation, Offer acceptedOffer, String listingDirection, boolean openQuestionsAcknowledged) {
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        var fields = fields(agreement, negotiation, acceptedOffer, listingDirection, nonce, openQuestionsAcknowledged);
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
        return fields(agreement, negotiation, offer, listingDirection, nonce, false);
    }
    static Map<String, Object> fields(Agreement agreement, Negotiation negotiation, Offer offer, String listingDirection, String nonce,
                                     boolean openQuestionsAcknowledged) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", CanonicalJson.FORMAT);
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
        fields.put("externalContractNamespace", offer.externalContractNamespace);
        fields.put("externalContractDigest", offer.externalContractDigest);
        // Structured terms (schemaVersion 4): what each side agreed to do, and what was knowingly left open.
        fields.put("scope", offer.scope);
        fields.put("ownerTasks", offer.ownerTasks);
        fields.put("initiatorTasks", offer.initiatorTasks);
        fields.put("fulfilmentMode", offer.fulfilmentMode);
        fields.put("fulfilBy", Objects.toString(offer.fulfilBy, null));
        fields.put("openQuestions", offer.openQuestions);
        fields.put("openQuestionsAcknowledged", openQuestionsAcknowledged);
        fields.put("offerValidUntil", Objects.toString(offer.validUntil, null));
        // Explicit, unambiguous economic direction frozen at accept time (never re-derived at
        // commit time - see NegotiationService.accept()); null on both means no economic execution.
        fields.put("payerUserId", agreement.payerUserId == null ? null : agreement.payerUserId.toString());
        fields.put("payeeUserId", agreement.payeeUserId == null ? null : agreement.payeeUserId.toString());
        fields.put("acceptedAt", Objects.toString(agreement.createdAt, null));
        fields.put("nonce", nonce);
        return fields;
    }

    private byte[] randomBytes(int length) { byte[] bytes = new byte[length]; random.nextBytes(bytes); return bytes; }
}
