package org.stir.reference;

import org.springframework.stereotype.Component;
import org.stir.listing.ListingRevision;

/** Transactional integration boundary for the LISTING/WANTED evidence sources
 * (MULTI_SOURCE_VALUE_EVIDENCE.md) - the listing bounded context's own equivalent of
 * ReferenceAcceptanceAdapter. A listing has exactly one party behind it, so its consent is
 * unilateral, never bilateral, and uses its own purpose (LISTING_EVIDENCE_CONTRIBUTION), never
 * REFERENCE_EVIDENCE_CONTRIBUTION (Agreement's). An observation is recorded whenever the revision
 * actually carries an indicative amount/quantity, regardless of the owner's consent choice -
 * having the datum and having permission to use it stay separate questions, exactly as for
 * Agreements. */
@Component
public class ListingEvidenceAdapter {
    private final ReferenceService references;
    private final ConsentService consent;
    public ListingEvidenceAdapter(ReferenceService references,ConsentService consent) { this.references=references; this.consent=consent; }
    public void recorded(ListingRevision revision) {
        if(revision.referenceDefinitionId==null || revision.indicativeAmount==null || revision.indicativeQuantity==null) return;
        String source="OFFER".equals(revision.direction)?"LISTING":"WANTED";
        var observationId=references.record(revision.referenceDefinitionId,source,revision.id,revision.ownerId,null,
            revision.indicativeAmount,revision.indicativeQuantity,revision.indicativeUnitLabel,revision.indicativeUnitRef,
            revision.shareReferenceObservation,revision.createdAt,revision.listingId);
        consent.capture(observationId,revision.referenceDefinitionId,revision.ownerId,revision.shareReferenceObservation,ConsentService.LISTING_PURPOSE);
    }
}
