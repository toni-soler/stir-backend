package org.stir.listing;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, UUID>, JpaSpecificationExecutor<Listing> {
    Optional<Listing> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Row lock held by an owner edit, so it serializes with negotiations being opened/accepted on the same listing. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id=:id and l.tenantId=:tenantId")
    Optional<Listing> lockForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Shared lock taken when a negotiation is opened or accepted: an owner edit cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select l from Listing l where l.id=:id and l.tenantId=:tenantId")
    Optional<Listing> lockForShare(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
