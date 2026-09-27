package org.stir.listing;

import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.stir.negotiation.CanonicalJson;

/** Freezes the listing's current fields into an immutable, canonical, hashable revision - the
 * same freeze-at-creation convention as Offer/AgreementSnapshot, applied to Listing for the first
 * time (MULTI_SOURCE_VALUE_EVIDENCE.md). */
@Service
public class ListingRevisionService {
    public static final String FORMAT = "STIR-LISTING-REVISION-JCS-1";
    private final ListingRevisionRepository revisions;
    public ListingRevisionService(ListingRevisionRepository revisions) { this.revisions=revisions; }

    public ListingRevision freeze(Listing listing) {
        var fields = new LinkedHashMap<String,Object>();
        fields.put("format", FORMAT);
        fields.put("listingId", listing.id.toString());
        fields.put("ownerId", listing.ownerId.toString());
        fields.put("direction", listing.direction);
        fields.put("title", listing.title);
        fields.put("indicativeAmount", listing.indicativeAmount==null?null:listing.indicativeAmount.toPlainString());
        fields.put("indicativeQuantity", listing.indicativeQuantity==null?null:listing.indicativeQuantity.toPlainString());
        fields.put("indicativeUnitLabel", listing.indicativeUnitLabel);
        fields.put("indicativeUnitRef", listing.indicativeUnitRef);
        fields.put("referenceDefinitionId", listing.referenceDefinitionId==null?null:listing.referenceDefinitionId.toString());
        fields.put("shareReferenceObservation", listing.shareReferenceObservation);
        byte[] bytes = CanonicalJson.canonicalBytes(fields);
        var revision = new ListingRevision();
        revision.id = java.util.UUID.randomUUID();
        revision.tenantId = listing.tenantId;
        revision.listingId = listing.id;
        revision.revisionNumber = revisions.nextRevisionNumber(listing.tenantId, listing.id);
        revision.ownerId = listing.ownerId;
        revision.direction = listing.direction;
        revision.title = listing.title;
        revision.indicativeAmount = listing.indicativeAmount;
        revision.indicativeQuantity = listing.indicativeQuantity;
        revision.indicativeUnitLabel = listing.indicativeUnitLabel;
        revision.indicativeUnitRef = listing.indicativeUnitRef;
        revision.referenceDefinitionId = listing.referenceDefinitionId;
        revision.shareReferenceObservation = listing.shareReferenceObservation;
        revision.canonicalJson = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        revision.digestSha256 = CanonicalJson.sha256Hex(bytes);
        revision.createdAt = Instant.now();
        return revisions.saveAndFlush(revision);
    }
}
