package org.stir.negotiation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgreementRepository extends JpaRepository<Agreement, UUID> {
    Optional<Agreement> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Agreement> findByNegotiationId(UUID negotiationId);
    @Query("select a from Agreement a where a.tenantId=:tenant and (a.initiatorId=:user or a.ownerId=:user)"
        + " order by a.createdAt desc, a.id asc")
    Page<Agreement> findForParty(@Param("tenant") UUID tenant, @Param("user") UUID user, Pageable pageable);
}
