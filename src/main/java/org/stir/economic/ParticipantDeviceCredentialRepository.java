package org.stir.economic;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantDeviceCredentialRepository extends JpaRepository<ParticipantDeviceCredential, UUID> {
    List<ParticipantDeviceCredential> findByTenantIdAndUserId(UUID tenantId, UUID userId);
}
