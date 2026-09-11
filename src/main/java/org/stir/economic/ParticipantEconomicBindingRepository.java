package org.stir.economic;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantEconomicBindingRepository extends JpaRepository<ParticipantEconomicBinding, UUID> {
    Optional<ParticipantEconomicBinding> findByTenantIdAndUserId(UUID tenantId, UUID userId);
}
