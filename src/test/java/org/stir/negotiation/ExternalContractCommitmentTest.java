package org.stir.negotiation;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalContractCommitmentTest {
    @Test void acceptedExternalCommitmentIsPartOfTheImmutableAgreementDigest() {
        var agreement = new Agreement(); agreement.id = UUID.randomUUID();
        var negotiation = new Negotiation(); negotiation.id = UUID.randomUUID();
        negotiation.listingId = UUID.randomUUID(); negotiation.initiatorId = UUID.randomUUID();
        negotiation.ownerId = UUID.randomUUID();
        var offer = new Offer(); offer.id = UUID.randomUUID(); offer.message = "Bicycle";
        offer.externalContractNamespace = "example.market";
        offer.externalContractDigest = "a".repeat(64);
        var original = CanonicalJson.sha256Hex(CanonicalJson.canonicalBytes(
            AgreementSnapshotService.fields(agreement, negotiation, offer, "OFFER", "nonce")));
        offer.externalContractDigest = "b".repeat(64);
        var changed = CanonicalJson.sha256Hex(CanonicalJson.canonicalBytes(
            AgreementSnapshotService.fields(agreement, negotiation, offer, "OFFER", "nonce")));
        assertNotEquals(original, changed);
    }
    @Test void additiveSnapshotViewExposesOpaqueFieldsAndAcceptsOlderSnapshots() {
        var snapshot = new AgreementSnapshot();
        snapshot.schemaVersion = 3;
        snapshot.canonicalJson = "{\"externalContractNamespace\":\"example.market\",\"externalContractDigest\":\"" + "a".repeat(64) + "\"}";
        var view = AgreementSnapshotView.of(snapshot);
        assertEquals("example.market", view.externalContractNamespace());
        assertEquals("a".repeat(64), view.externalContractDigest());
        snapshot.schemaVersion = 2;
        snapshot.canonicalJson = "{\"terms\":null}";
        assertNull(AgreementSnapshotView.of(snapshot).externalContractDigest());
    }
}
