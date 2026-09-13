package org.stir.attachment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {
    Optional<Attachment> findByIdAndTenantId(UUID id, UUID tenantId);
    List<Attachment> findByTenantIdAndListingIdAndStatusOrderByPosition(UUID tenantId, UUID listingId, String status);
    long countByTenantIdAndListingIdAndStatus(UUID tenantId, UUID listingId, String status);
    /** Position 0 of each listing's active photos - the marketplace card's main image, fetched in
     * one batch per search page rather than one query per row. */
    List<Attachment> findByTenantIdAndListingIdInAndPositionAndStatus(UUID tenantId, Collection<UUID> listingIds, short position, String status);
}
