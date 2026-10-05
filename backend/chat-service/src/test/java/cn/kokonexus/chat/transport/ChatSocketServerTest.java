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
    /** 旧协议用例明确只替换租约；Redis真值另有独立验收。 */
    private final ChatConnectionQuota quota = mock(ChatConnectionQuota.class);
    private ChatSocketServer server;

    @BeforeEach
    void start() {
        when(security.trusted("test-key")).thenReturn(true);
        when(security.allowedOrigin("http://localhost:5173")).thenReturn(true);
        when(security.active(42, "test-session")).thenReturn(true);
        when(quota.acquire(anyLong(), anyString())).thenReturn(true);
        when(quota.renew(anyLong(), anyString())).thenReturn(true);
        server = new ChatSocketServer(
            service,
            new ObjectMapper().findAndRegisterModules(),
            security,
            quota,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            0,
            "127.0.0.1"
        );
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void stoppingDuringAuthenticationCannotAllocateLateGlobalLease() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var released = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            released.await(5, TimeUnit.SECONDS);
            return true;
        })
            .when(security)
            .active(42, "test-session");
        var opening = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("Origin", "http://localhost:5173")
            .header("X-Koko-Gateway-Key", "test-key")
            .header("X-Koko-User-Id", "42")
            .header("Cookie", "koko-nexus-token=test-session")
            .buildAsync(URI.create("ws://127.0.0.1:" + server.boundPort() + "/api/chat/ws"), new Listener());
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var stopping = Thread.ofPlatform().start(server::stop);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(server.isRunning());
            released.countDown();
            stopping.join(15000);
            assertFalse(stopping.isAlive());
            assertThrows(Exception.class, () -> opening.get(3, TimeUnit.SECONDS));
            verify(quota, never()).acquire(anyLong(), anyString());
            verifyNoInteractions(service);
        } finally {
            released.countDown();
            stopping.join(15000);
        }
    }

    @Test
    void globalQuotaDeniedOrUnavailableNeverEntersBusiness() {
        when(quota.acquire(anyLong(), anyString())).thenReturn(false);
        assertThrows(Exception.class, () -> connect("http://localhost:5173", new Listener()));
        verifyNoInteractions(service);
        doThrow(new ChatQuotaUnavailableException(new IllegalStateException("synthetic-sensitive-message")))
            .when(quota)
            .acquire(anyLong(), anyString());
        assertThrows(Exception.class, () -> connect("http://localhost:5173", new Listener()));
        verifyNoInteractions(service);
    }

    @Test
    void expiredLeaseCannotSendOrReceivePongAndReleasesOnlyItsUuidOffEventLoop() throws Exception {
        var listener = new Listener();
        var socket = connect("http://localhost:5173", listener);
        listener.next();
        doReturn(false).when(quota).renew(anyLong(), anyString());
        var released = new java.util.concurrent.CompletableFuture<String>();
        doAnswer(call -> {
            assertFalse(Thread.currentThread().getName().contains("EventLoop"));
            released.complete(call.getArgument(1));
            return null;
        })
            .when(quota)
            .release(anyLong(), anyString());
        socket.sendText("{\"type\":\"PING\"}", true).join();
        assertTrue(listener.next().contains("CONNECTION_EXPIRED"));
        String connection = released.get(5, TimeUnit.SECONDS);
        verify(quota).acquire(42, connection);
        verify(quota).renew(42, connection);
        verifyNoInteractions(service);
    }

    @Test
    void redisRenewFailureDoesNotCommitOrAcknowledge() throws Exception {
        var listener = new Listener();
        var socket = connect("http://localhost:5173", listener);
        listener.next();
        doThrow(new ChatQuotaUnavailableException(new java.net.ConnectException("synthetic-secret")))
            .when(quota)
            .renew(anyLong(), anyString());
        socket
            .sendText("{\"type\":\"SEND\",\"conversationId\":\"x\",\"clientMessageId\":\"y\",\"body\":\"z\"}", true)
            .join();
        assertTrue(listener.next().contains("UNAVAILABLE"));
        verifyNoInteractions(service);
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

    @Test
    void ambiguousOrNoncanonicalIdentityNeverReachesAuthenticationOrBusiness() {
        for (String identity : java.util.List.of("0", "-42", "+42", "042", "9223372036854775808", "42,43")) {
            assertThrows(Exception.class, () -> customHandshake(identity, "koko-nexus-token=test-session", false));
        }
        assertThrows(Exception.class, () ->
            customHandshake("42", "koko-nexus-token=test-session; koko-nexus-token=other", false)
        );
        assertThrows(Exception.class, () -> customHandshake("42", "unrelated=value", false));
        assertThrows(Exception.class, () -> customHandshake("42", "koko-nexus-token=test-session", true));
        verify(security, never()).active(anyLong(), anyString());
        verifyNoInteractions(service);
    }

    @Test
    void targetedSyncDoesNotBroadcastToOtherLocalUsers() throws Exception {
        when(security.active(43, "test-session")).thenReturn(true);
        var firstListener = new Listener();
        var first = connect("http://localhost:5173", firstListener);
        firstListener.next();
        var secondListener = new Listener();
        var second = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("Origin", "http://localhost:5173")
            .header("X-Koko-Gateway-Key", "test-key")
            .header("X-Koko-User-Id", "43")
            .header("Cookie", "koko-nexus-token=test-session")
            .buildAsync(URI.create("ws://127.0.0.1:" + server.boundPort() + "/api/chat/ws"), secondListener)
            .get(5, TimeUnit.SECONDS);
        try {
            secondListener.next();
            assertEquals(java.util.Set.of(42L, 43L), server.connectedUsers());
            server.syncUsers(java.util.Set.of(42L));
            assertEquals("{\"type\":\"SYNC\"}", firstListener.next());
            assertNull(secondListener.messages.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            first.abort();
            second.abort();
        }
    }

    private WebSocket customHandshake(String identity, String cookies, boolean duplicate) throws Exception {
        var builder = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .header("Origin", "http://localhost:5173")
            .header("X-Koko-Gateway-Key", "test-key")
            .header("X-Koko-User-Id", identity)
            .header("Cookie", cookies);
        if (duplicate) builder.header("X-Koko-User-Id", "43");
        return builder
            .buildAsync(URI.create("ws://127.0.0.1:" + server.boundPort() + "/api/chat/ws"), new Listener())
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
