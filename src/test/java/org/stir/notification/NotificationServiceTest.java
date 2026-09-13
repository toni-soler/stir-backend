package org.stir.notification;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    // The 8 event types the 0.4 brief requires (section 10): every one must persist through the
    // same create() path with no special-casing.
    static final List<String> EVENT_TYPES = List.of(
        "OFFER_RECEIVED", "COUNTEROFFER_RECEIVED", "OFFER_ACCEPTED", "OFFER_DECLINED",
        "SIGNATURE_NEEDED", "COUNTERPARTY_SIGNED", "TRADE_COMMITTED", "TRADE_REJECTED");

    final UUID tenant = UUID.randomUUID(), recipient = UUID.randomUUID(), stranger = UUID.randomUUID();
    NotificationRepository notifications;
    NotificationService service;
    CurrentUser recipientUser, strangerUser;

    @BeforeEach void setup() {
        notifications = mock(NotificationRepository.class);
        service = new NotificationService(notifications);
        when(notifications.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        recipientUser = mock(CurrentUser.class); when(recipientUser.getUserId()).thenReturn(recipient);
        strangerUser = mock(CurrentUser.class); when(strangerUser.getUserId()).thenReturn(stranger);
        TenantContext.set(new TenantContext(tenant, null, recipient, "test", TenantContext.DbRole.IDAX_APP));
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void createPersistsEveryEventTypeWithTheGivenReference() {
        var referenceId = UUID.randomUUID();
        for (String type : EVENT_TYPES) {
            reset(notifications);
            when(notifications.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            service.create(tenant, recipient, type, "AGREEMENT", referenceId);
            var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
            verify(notifications).saveAndFlush(captor.capture());
            var saved = captor.getValue();
            assertEquals(tenant, saved.tenantId);
            assertEquals(recipient, saved.recipientUserId);
            assertEquals(type, saved.type);
            assertEquals("AGREEMENT", saved.referenceType);
            assertEquals(referenceId, saved.referenceId);
            assertNull(saved.readAt, "a freshly created notification must start unread");
        }
    }

    @Test void createNeverDeduplicatesItself_repeatedRealEventsEachProduceTheirOwnRow() {
        // create() has no dedup logic of its own - see the class docstring: idempotency comes from
        // the CALLER (NegotiationService/TradeService) never calling create() twice for the same
        // real event, not from any guard here. Confirms that contract stays true: two distinct
        // calls really do produce two distinct rows, so a caller-side idempotency bug would show up
        // as visible duplicate notifications rather than being silently absorbed here.
        var referenceId = UUID.randomUUID();
        service.create(tenant, recipient, "TRADE_COMMITTED", "AGREEMENT", referenceId);
        service.create(tenant, recipient, "TRADE_COMMITTED", "AGREEMENT", referenceId);
        verify(notifications, times(2)).saveAndFlush(any());
    }

    @Test void markReadRejectsANotificationThatBelongsToSomeoneElse() {
        var notification = new Notification();
        notification.id = UUID.randomUUID(); notification.tenantId = tenant; notification.recipientUserId = stranger;
        when(notifications.findByIdAndTenantId(notification.id, tenant)).thenReturn(Optional.of(notification));
        var error = assertThrows(ResponseStatusException.class, () -> service.markRead(recipientUser, notification.id));
        assertEquals(404, error.getStatusCode().value());
        assertNull(notification.readAt);
    }

    @Test void markReadIsIdempotent() {
        var notification = new Notification();
        notification.id = UUID.randomUUID(); notification.tenantId = tenant; notification.recipientUserId = recipient;
        when(notifications.findByIdAndTenantId(notification.id, tenant)).thenReturn(Optional.of(notification));
        service.markRead(recipientUser, notification.id);
        var firstReadAt = notification.readAt;
        assertNotNull(firstReadAt);
        service.markRead(recipientUser, notification.id);
        assertEquals(firstReadAt, notification.readAt, "marking an already-read notification read again must not touch its timestamp");
        verify(notifications, times(1)).saveAndFlush(notification);
    }

    @Test void mineAndUnreadCountAreScopedToTheCallersOwnTenantAndUser() {
        when(notifications.findByTenantIdAndRecipientUserIdOrderByCreatedAtDesc(tenant, recipient, PageRequest.of(0, 20)))
            .thenReturn(new PageImpl<>(List.of()));
        when(notifications.countByTenantIdAndRecipientUserIdAndReadAtIsNull(tenant, recipient)).thenReturn(3L);

        service.mine(recipientUser, 0, 20);
        assertEquals(3L, service.unreadCount(recipientUser));
        verify(notifications).findByTenantIdAndRecipientUserIdOrderByCreatedAtDesc(tenant, recipient, PageRequest.of(0, 20));
    }
}
