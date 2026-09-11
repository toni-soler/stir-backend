package org.stir.economic;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, UUID> {
    Optional<Trade> findByTenantIdAndAgreementId(UUID tenantId, UUID agreementId);
}
