package cn.kokonexus.chat.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.chat.application.ChatService;
import cn.kokonexus.chat.domain.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 真 TCP WebSocket 协议测试；持久化事务在另一个真实 MySQL 验收中测试。 */
class ChatSocketServerTest {

    private final ChatService service = mock(ChatService.class);
    private final ChatSecurity security = mock(ChatSecurity.class);
    private ChatSocketServer server;

    @BeforeEach
    void start() {
        when(security.trusted("test-key")).thenReturn(true);
        when(security.allowedOrigin("http://localhost:5173")).thenReturn(true);
        when(security.active(42, "test-session")).thenReturn(true);
        server = new ChatSocketServer(service, new ObjectMapper().findAndRegisterModules(), security, 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void validatesHandshakeOriginAndIdentity() {
        assertThrows(Exception.class, () -> connect("https://untrusted.invalid", new Listener()));
        when(security.active(42, "test-session")).thenReturn(false);
        assertThrows(Exception.class, () -> connect("http://localhost:5173", new Listener()));
        verifyNoInteractions(service);
    }

    @Test
    void persistsBeforeAckAndRejectsRevokedConnection() throws Exception {
        var listener = new Listener();
        var socket = connect("http://localhost:5173", listener);
        assertTrue(listener.next().contains("READY"));
        ChatMessage message = new ChatMessage();
        message.setId("6efbcbdf-b588-4a8d-a81c-ce2149945790");
        message.setConversationId("cdfcaa7b-e070-43c3-ad28-26a041d41b8c");
        message.setSeq(1L);
        message.setSenderId(42L);
        message.setSenderName("User");
        message.setClientMessageId("a8b9d016-e9a1-4f5e-8570-b3c96521ef06");
        message.setBody("hello");
        message.setCreatedAt(LocalDateTime.now());
        when(service.send(42, message.getConversationId(), message.getClientMessageId(), "hello")).thenReturn(message);
        socket
            .sendText(
                "{\"type\":\"SEND\",\"conversationId\":\"" +
                    message.getConversationId() +
                    "\",\"clientMessageId\":\"" +
                    message.getClientMessageId() +
                    "\",\"body\":\"hello\",\"senderId\":999}",
                true
            )
            .join();
        assertTrue(listener.next().contains("ACK"));
        assertTrue(listener.next().contains("SYNC"));
        verify(service).send(42, message.getConversationId(), message.getClientMessageId(), "hello");
        Thread.sleep(50);
        when(security.active(42, "test-session")).thenReturn(false);
        socket.sendText("{\"type\":\"PING\"}", true).join();
        assertTrue(listener.next().contains("AUTH_REQUIRED"));
        socket.abort();
    }

    @Test
    void invalidOrFailedSendNeverAcknowledges() throws Exception {
        var listener = new Listener();
        var socket = connect("http://localhost:5173", listener);
        listener.next();
        socket.sendText("[]", true).join();
        assertTrue(listener.next().contains("REJECTED"));
        verifyNoInteractions(service);
        Thread.sleep(50);
        when(service.send(anyLong(), anyString(), anyString(), anyString())).thenThrow(
            new IllegalStateException("消息冲突")
        );
        socket
            .sendText("{\"type\":\"SEND\",\"conversationId\":\"x\",\"clientMessageId\":\"y\",\"body\":\"z\"}", true)
            .join();
        assertTrue(listener.next().contains("REJECTED"));
        assertNull(listener.messages.poll(100, TimeUnit.MILLISECONDS));
        socket.abort();
    }

    @Test
    void maximumThreeConnectionsAndCapacityReleasedOnClose() throws Exception {
        var first = connect("http://localhost:5173", new Listener());
        var second = connect("http://localhost:5173", new Listener());
        var third = connect("http://localhost:5173", new Listener());
        assertThrows(Exception.class, () -> connect("http://localhost:5173", new Listener()));
        first.abort();
        Thread.sleep(100);
        var replacement = connect("http://localhost:5173", new Listener());
        second.abort();
        third.abort();
        replacement.abort();
    }

    @Test
    void blockedPrivateSendReturnsForbiddenAndNeverAck() throws Exception {
        var listener = new Listener();
        var socket = connect("http://localhost:5173", listener);
        listener.next();
        when(service.send(anyLong(), anyString(), anyString(), anyString())).thenThrow(
            new cn.kokonexus.common.api.ForbiddenOperationException("blocked")
        );
        socket
            .sendText("{\"type\":\"SEND\",\"conversationId\":\"x\",\"clientMessageId\":\"y\",\"body\":\"z\"}", true)
            .join();
        String response = listener.next();
        assertTrue(response.contains("FORBIDDEN"));
        assertFalse(response.contains("ACK"));
        assertNull(listener.messages.poll(100, TimeUnit.MILLISECONDS));
        socket.abort();
    }

    private WebSocket connect(String origin, Listener listener) throws Exception {
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .header("Origin", origin)
            .header("X-Koko-Gateway-Key", "test-key")
            .header("X-Koko-User-Id", "42")
            .header("Cookie", "koko-nexus-token=test-session")
            .buildAsync(URI.create("ws://127.0.0.1:" + server.boundPort() + "/api/chat/ws"), listener)
            .get(5, TimeUnit.SECONDS);
    }

    private static class Listener implements WebSocket.Listener {

        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }

        String next() throws Exception {
            String value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "Missing WebSocket response");
            return value;
        }
    }
}
