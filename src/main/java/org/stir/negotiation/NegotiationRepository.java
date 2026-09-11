package org.stir.negotiation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NegotiationRepository extends JpaRepository<Negotiation, UUID> {
    Optional<Negotiation> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Negotiation> findByTenantIdAndListingIdAndInitiatorIdAndStatus(UUID tenantId, UUID listingId, UUID initiatorId, String status);
    @Query("select n from Negotiation n where n.tenantId=:tenant and (n.initiatorId=:user or n.ownerId=:user)"
        + " and (:status is null or n.status=:status) order by n.updatedAt desc, n.id asc")
    Page<Negotiation> findForParty(@Param("tenant") UUID tenant, @Param("user") UUID user, @Param("status") String status, Pageable pageable);
}
