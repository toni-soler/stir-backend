package org.stir.listing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-path view: identical Listing fields plus the owner's public display name and main photo.
 * counterpartNote is null unless the reader may see it; hasCounterpartNote tells everyone else that
 * the owner shares more detail with the people who negotiate (STRUCTURED_TERMS_AND_NEEDS.md). */
public record ListingView(UUID id, UUID tenantId, UUID ownerId, String ownerDisplayName, String direction,
        String title, String description, String category, String resourceKind, String location,
        String status, boolean hidden, UUID mainPhotoId, long version, Instant createdAt, Instant updatedAt, UUID referenceDefinitionId,
        BigDecimal indicativeAmount, BigDecimal indicativeQuantity, String indicativeUnitLabel, String indicativeUnitRef, boolean shareReferenceObservation,
        String itemCondition, String fulfilmentMode, String includedItems, LocalDate availableUntil, String availability,
        LocalDate neededBy, List<String> flexibleOn, List<String> firmOn, boolean hasCounterpartNote, String counterpartNote) {
    static ListingView of(Listing listing, String ownerDisplayName, UUID mainPhotoId) {
        return of(listing, ownerDisplayName, mainPhotoId, false);
    }
    static ListingView of(Listing listing, String ownerDisplayName, UUID mainPhotoId, boolean withCounterpartNote) {
        return new ListingView(listing.id, listing.tenantId, listing.ownerId, ownerDisplayName, listing.direction,
            listing.title, listing.description, listing.category, listing.resourceKind, listing.location,
            listing.status, listing.hiddenByModerator, mainPhotoId, listing.version, listing.createdAt, listing.updatedAt, listing.referenceDefinitionId,
            listing.indicativeAmount, listing.indicativeQuantity, listing.indicativeUnitLabel, listing.indicativeUnitRef, listing.shareReferenceObservation,
            listing.itemCondition, listing.fulfilmentMode, listing.includedItems, listing.availableUntil, listing.availability,
            listing.neededBy, split(listing.flexibleOn), split(listing.firmOn), listing.counterpartNote!=null,
            withCounterpartNote ? listing.counterpartNote : null);
    }
    private static List<String> split(String csv) { return csv==null ? List.of() : List.of(csv.split(",")); }
}
