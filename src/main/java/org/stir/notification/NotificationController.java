package org.stir.notification;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.notifications.read')")
    public Page<NotificationService.NotificationView> mine(@AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.mine(user, page, size);
    }

    @GetMapping("/unread-count") @PreAuthorize("@permissionService.hasPermission('stir.notifications.read')")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("count", service.unreadCount(user));
    }

    @PostMapping("/{id}/read") @PreAuthorize("@permissionService.hasPermission('stir.notifications.read')")
    public void markRead(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        service.markRead(user, id);
    }
}
