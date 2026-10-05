package cn.kokonexus.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.outbox.persistence.OutboxMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private final OutboxMapper mapper = mock(OutboxMapper.class);
    private final EventSender sender = mock(EventSender.class);
    private final OutboxRelay relay = new OutboxRelay(mapper, sender);

    @Test
    void acknowledgedDeliveryMarksClaimedEventSent() throws Exception {
        OutboxRecord record = record(1);
        when(mapper.claim(anyString(), eq(10))).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(List.of(record));

        relay.tick();

        verify(sender).send(record.toEvent());
        verify(mapper).markSent(eq(record.getId()), anyString());
    }

    @Test
    void brokerFailureSchedulesRetryWithoutDroppingEvent() throws Exception {
        OutboxRecord record = record(3);
        when(mapper.claim(anyString(), eq(10))).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(List.of(record));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker offline")).when(sender).send(record.toEvent());

        relay.tick();

        verify(mapper).markFailed(eq(record.getId()), anyString(), eq(false), eq(8), anyString());
    }

    @Test
    void exhaustedAttemptsGoToDeadLetterState() throws Exception {
        OutboxRecord record = record(10);
        when(mapper.claim(anyString(), eq(10))).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(List.of(record));
        org.mockito.Mockito.doThrow(new IllegalStateException("poison")).when(sender).send(record.toEvent());

        relay.tick();

        verify(mapper).markFailed(eq(record.getId()), anyString(), eq(true), eq(900), anyString());
    }

    @Test
    void writerRejectsSelfNotification() {
        OutboxWriter writer = new OutboxWriter(mapper);
        assertThatThrownBy(() -> writer.enqueue(20L, 20L, "FOLLOW", "20", "关注")).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    @Test
    void sentButSqlAcknowledgementFailureDoesNotBecomeBrokerFailure() throws Exception {
        var record = record(1);
        when(mapper.claim(anyString(), eq(10))).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(List.of(record));
        when(mapper.markSent(eq(record.getId()), anyString())).thenThrow(new IllegalStateException("SQL-secret"));
        relay.tick();
        verify(sender).send(record.toEvent());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).markFailed(
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyInt(),
            anyString()
        );
    }

    @Test
    void persistedDiagnosticKeepsCauseTypeButNeverProviderMessage() throws Exception {
        var record = record(2);
        when(mapper.claim(anyString(), eq(10))).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(List.of(record));
        org.mockito.Mockito.doThrow(
            new IllegalStateException("password=private", new java.io.IOException("token=private"))
        )
            .when(sender)
            .send(record.toEvent());
        relay.tick();
        var detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).markFailed(eq(record.getId()), anyString(), eq(false), eq(4), detail.capture());
        org.assertj.core.api.Assertions.assertThat(detail.getValue())
            .contains("IllegalStateException", "IOException")
            .doesNotContain("private", "password=", "token=");
    }

    @Test
    void expiredUnconfirmedExhaustionRunsBeforeClaimEvenWhenNoWorkIsAvailable() {
        relay.tick();
        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).exhaustExpired();
        order.verify(mapper).claim(anyString(), eq(10));
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    @Test
    void staleFailureAcknowledgementDoesNotAttemptSuccessOrAnotherSend() throws Exception {
        var record = record(2);
        when(mapper.claim(anyString(), eq(10))).thenReturn(1);
        when(mapper.selectClaimed(anyString())).thenReturn(List.of(record));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker-unconfirmed"))
            .when(sender)
            .send(record.toEvent());
        when(mapper.markFailed(eq(record.getId()), anyString(), eq(false), eq(4), anyString())).thenReturn(0);
        relay.tick();
        verify(sender, org.mockito.Mockito.times(1)).send(record.toEvent());
        verify(mapper, org.mockito.Mockito.never()).markSent(anyString(), anyString());
    }

    private OutboxRecord record(int attempts) {
        OutboxRecord record = new OutboxRecord();
        record.setId("30458721-5f3e-4fef-b706-471ab7958bc6");
        record.setRecipientId(20L);
        record.setActorId(10L);
        record.setEventType("FOLLOW");
        record.setResourceId("10");
        record.setSummary("有人关注了你");
        record.setAttempts(attempts);
        return record;
    }
}
