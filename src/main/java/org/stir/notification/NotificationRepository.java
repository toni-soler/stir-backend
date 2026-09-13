package org.stir.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<Notification> findByTenantIdAndRecipientUserIdOrderByCreatedAtDesc(UUID tenantId, UUID recipientUserId, Pageable pageable);
    long countByTenantIdAndRecipientUserIdAndReadAtIsNull(UUID tenantId, UUID recipientUserId);
}
