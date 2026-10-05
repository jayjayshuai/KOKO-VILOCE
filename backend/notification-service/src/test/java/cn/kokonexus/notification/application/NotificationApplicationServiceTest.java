package cn.kokonexus.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.notification.domain.NotificationEvent;
import cn.kokonexus.notification.domain.NotificationInbox;
import cn.kokonexus.notification.infrastructure.persistence.NotificationInboxMapper;
import cn.kokonexus.notification.infrastructure.persistence.NotificationPreferenceMapper;
import cn.kokonexus.notification.infrastructure.persistence.NotificationReceiptMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationApplicationServiceTest {

    private final NotificationInboxMapper inbox = mock(NotificationInboxMapper.class);
    private final NotificationPreferenceMapper preferences = mock(NotificationPreferenceMapper.class);
    private final NotificationReceiptMapper receipts = mock(NotificationReceiptMapper.class);
    private final NotificationApplicationService service = new NotificationApplicationService(
        inbox,
        preferences,
        receipts
    );

    @Test
    void duplicateEventDoesNotCreateAnotherNotification() {
        when(preferences.enabled(20L, "FOLLOW")).thenReturn(null);
        NotificationEvent event = event();
        when(receipts.claim(event.eventId(), 20L)).thenReturn(1, 0);
        when(receipts.setDisposition(event.eventId(), 20L, "DELIVERED")).thenReturn(1);
        when(inbox.insertIdempotent(any(NotificationInbox.class))).thenReturn(1);

        assertThat(service.deliver(event)).isTrue();
        assertThat(service.deliver(event)).isFalse();
    }

    @Test
    void disabledPreferenceSuppressesDelivery() {
        NotificationEvent event = event();
        when(receipts.claim(event.eventId(), 20L)).thenReturn(1);
        when(receipts.setDisposition(event.eventId(), 20L, "SUPPRESSED")).thenReturn(1);
        when(preferences.enabled(20L, "FOLLOW")).thenReturn(false);
        assertThat(service.deliver(event)).isFalse();
    }

    @Test
    void cannotMarkAnotherUsersNotificationRead() {
        when(inbox.markRead(20L, 99L)).thenReturn(0);
        when(inbox.existsForUser(20L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> service.markRead(20L, 99L)).isInstanceOf(ResourceNotFoundException.class);
        verify(inbox).markRead(20L, 99L);
    }

    @Test
    void readIsIdempotentAndPageIsBounded() {
        when(inbox.existsForUser(20L, 99L)).thenReturn(true);
        when(inbox.selectPageForUser(20L, 50L, 50)).thenReturn(List.of(new NotificationInbox()));
        when(inbox.countForUser(20L)).thenReturn(51L);

        service.markRead(20L, 99L);
        var page = service.page(20L, 2, 1000);

        assertThat(page.size()).isEqualTo(50);
        assertThat(page.total()).isEqualTo(51);
    }

    @Test
    void rejectsUnknownEventTypesAndSelfNotification() {
        assertThatThrownBy(() -> service.savePreference(20L, "INVALID", true)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() ->
            service.deliver(new NotificationEvent(UUID.randomUUID().toString(), 20L, 20L, "FOLLOW", "20", "自我关注"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private NotificationEvent event() {
        return new NotificationEvent(UUID.randomUUID().toString(), 20L, 10L, "FOLLOW", "10", "新的关注者");
    }
}
