package org.stir.listing;

import java.time.Instant;
import java.util.UUID;

/** Read-path view: identical Listing fields plus the owner's public display name and main photo. */
public record ListingView(UUID id, UUID tenantId, UUID ownerId, String ownerDisplayName, String direction,
        String title, String description, String category, String resourceKind, String location,
        String status, boolean hidden, UUID mainPhotoId, long version, Instant createdAt, Instant updatedAt) {
    static ListingView of(Listing listing, String ownerDisplayName, UUID mainPhotoId) {
        return new ListingView(listing.id, listing.tenantId, listing.ownerId, ownerDisplayName, listing.direction,
            listing.title, listing.description, listing.category, listing.resourceKind, listing.location,
            listing.status, listing.hiddenByModerator, mainPhotoId, listing.version, listing.createdAt, listing.updatedAt);
    }
}
