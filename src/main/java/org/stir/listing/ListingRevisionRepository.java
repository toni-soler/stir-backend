package org.stir.listing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRevisionRepository extends JpaRepository<ListingRevision, UUID> {
    @Query("select coalesce(max(r.revisionNumber),0)+1 from ListingRevision r where r.tenantId=:tenant and r.listingId=:listingId")
    int nextRevisionNumber(@Param("tenant") UUID tenant, @Param("listingId") UUID listingId);

    /** The revision in force at a given instant (e.g. when a negotiation was opened), if one was recorded. */
    Optional<ListingRevision> findFirstByTenantIdAndListingIdAndCreatedAtLessThanEqualOrderByRevisionNumberDesc(UUID tenantId, UUID listingId, Instant at);
}
