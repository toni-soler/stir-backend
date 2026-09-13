package org.stir.notification;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * create() is called by NegotiationService/TradeService as a side effect INSIDE their own
 * transaction - a notification never outlives (or precedes) the event it describes, and it
 * inherits that event's own idempotency guard (a rejected retry never reaches create()).
 */
@Service @Transactional
public class NotificationService {
    private final NotificationRepository notifications;
    public NotificationService(NotificationRepository notifications) { this.notifications = notifications; }

    public void create(UUID tenant, UUID recipientUserId, String type, String referenceType, UUID referenceId) {
        var notification = new Notification();
        notification.id = UUID.randomUUID(); notification.tenantId = tenant; notification.recipientUserId = recipientUserId;
        notification.type = type; notification.referenceType = referenceType; notification.referenceId = referenceId;
        notification.createdAt = Instant.now();
        notifications.saveAndFlush(notification);
    }

    private UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID actor(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    public Page<NotificationView> mine(CurrentUser user, int page, int size) {
        return notifications.findByTenantIdAndRecipientUserIdOrderByCreatedAtDesc(tenant(), actor(user), PageRequest.of(page, size)).map(NotificationView::of);
    }

    public long unreadCount(CurrentUser user) {
        return notifications.countByTenantIdAndRecipientUserIdAndReadAtIsNull(tenant(), actor(user));
    }

    public void markRead(CurrentUser user, UUID id) {
        UUID tenant = tenant(), actor = actor(user);
        var notification = notifications.findByIdAndTenantId(id, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Notification not found"));
        if (!notification.recipientUserId.equals(actor)) throw new ResponseStatusException(NOT_FOUND, "Notification not found");
        if (notification.readAt == null) { notification.readAt = Instant.now(); notifications.saveAndFlush(notification); }
    }

    public record NotificationView(UUID id, String type, String referenceType, UUID referenceId, boolean read, Instant createdAt) {
        static NotificationView of(Notification n) { return new NotificationView(n.id, n.type, n.referenceType, n.referenceId, n.readAt != null, n.createdAt); }
    }
}
