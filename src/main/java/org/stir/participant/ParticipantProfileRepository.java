package org.stir.participant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantProfileRepository extends JpaRepository<ParticipantProfile, UUID> {
    Optional<ParticipantProfile> findByTenantIdAndUserId(UUID tenantId, UUID userId);
    List<ParticipantProfile> findByTenantIdAndUserIdIn(UUID tenantId, Collection<UUID> userIds);
}
