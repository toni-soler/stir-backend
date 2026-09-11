package org.stir.economic;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketplaceEconomicBindingRepository extends JpaRepository<MarketplaceEconomicBinding, UUID> {
    Optional<MarketplaceEconomicBinding> findByTenantId(UUID tenantId);
}
