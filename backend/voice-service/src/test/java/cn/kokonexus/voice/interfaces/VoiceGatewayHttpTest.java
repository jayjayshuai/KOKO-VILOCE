package cn.kokonexus.voice.interfaces;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.common.api.GlobalExceptionHandler;
import cn.kokonexus.common.api.ResourceNotFoundException;
import cn.kokonexus.voice.application.VoiceApplicationService;
import cn.kokonexus.voice.application.VoiceInteractionDirectory;
import cn.kokonexus.voice.application.VoiceInteractionService;
import cn.kokonexus.voice.domain.VoiceRoom;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 真实环回TCP/Tomcat/VoiceController与过滤器；业务适配为mock，不证明DB/LiveKit。 */
class VoiceGatewayHttpTest {

    /** 固定非生产密钥，测试不调用服务器。 */
    private static final String KEY = "isolated-voice-http-gateway-key-20261005";

    @Test
    void syncAndReceiptHttpBindTrustedIdentityAndKeepPrivateRepliesUncached() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.register(HttpConfiguration.class);
            context.refresh();
            var core = context.getBean(VoiceInteractionService.class);
            String version = "9007199254741001",
                id = "00000000-0000-0000-0000-000000000042";
            when(core.sync(42, 1, version)).thenReturn(
                new VoiceInteractionViews.SyncView(version, java.time.LocalDateTime.of(2026, 10, 6, 3, 0), null)
            );
            when(core.receipt(42, 1, id)).thenReturn(new VoiceInteractionViews.ReceiptView(false, null));
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var denied = client.send(
                    HttpRequest.newBuilder(
                        URI.create(base + "/api/voice/rooms/1/interaction/sync?knownVersion=" + version)
                    )
                        .header("X-Koko-User-Id", "42")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(403, denied.statusCode());
                verifyNoInteractions(core);
                var sync = client.send(
                    HttpRequest.newBuilder(
                        URI.create(base + "/api/voice/rooms/1/interaction/sync?knownVersion=" + version + "&userId=7")
                    )
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, sync.statusCode());
                assertTrue(sync.body().contains("\"version\":\"" + version + "\""));
                assertTrue(sync.body().contains("\"snapshot\":null"));
                assertEquals("no-store", sync.headers().firstValue("Cache-Control").orElseThrow());
                var receipt = client.send(
                    HttpRequest.newBuilder(
                        URI.create(base + "/api/voice/rooms/1/interaction/receipts/" + id + "?userId=7")
                    )
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, receipt.statusCode());
                assertTrue(receipt.body().contains("\"committed\":false"));
                assertEquals("no-store", receipt.headers().firstValue("Cache-Control").orElseThrow());
                verify(core).sync(42, 1, version);
                verify(core).receipt(42, 1, id);
                verifyNoMoreInteractions(core);
            }
        }
    }

    @Test
    void interactionEndpointsKeepDisabledSemanticsAndValidateBeforeDirectory() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.register(HttpConfiguration.class);
            context.refresh();
            var core = context.getBean(VoiceInteractionService.class);
            var directory = context.getBean(VoiceInteractionDirectory.class);
            when(core.features()).thenReturn(new VoiceInteractionViews.Capabilities(false, false, null, false));
            doThrow(new cn.kokonexus.common.api.ExternalDependencyUnavailableException("互动尚未开放", null))
                .when(core)
                .requireEnabled();
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var cap = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/interaction-capabilities"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, cap.statusCode());
                assertTrue(cap.body().contains("\"enabled\":false"));
                assertTrue(cap.body().contains("\"mediaReady\":false"));
                var invalid = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/1/interaction/join"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString("{\"requestId\":\"invalid\",\"expectedVersion\":\"0\"}")
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(400, invalid.statusCode());
                var valid = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/1/interaction/join"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                "{\"requestId\":\"00000000-0000-0000-0000-000000000042\",\"expectedVersion\":\"0\",\"displayName\":\"伪造名称\"}"
                            )
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(503, valid.statusCode());
                assertEquals("no-store", valid.headers().firstValue("Cache-Control").orElseThrow());
                verifyNoInteractions(directory);
            }
        }
    }

    @Test
    void ownerListingIsPrivateAndDoesNotSerializeProviderOrAcceptOwnerOverride() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.register(HttpConfiguration.class);
            context.refresh();
            var service = context.getBean(VoiceApplicationService.class);
            var room = new VoiceRoom();
            room.setId(Long.MAX_VALUE);
            room.setOwnerId(42L);
            room.setOwnerName("本人");
            room.setSlug("mine-room");
            room.setTitle("本人房间");
            room.setStatus("OPEN");
            room.setMaxParticipants(20);
            room.setProviderRoomName("synthetic-private-provider-name");
            when(service.ownedRooms(42, null, 20)).thenReturn(
                new VoiceApplicationService.OwnedRoomPage(List.of(room), "9223372036854775806")
            );
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var anonymous = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/mine"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(403, anonymous.statusCode());
                verifyNoInteractions(service);
                var response = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/mine?ownerId=999"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertTrue(response.body().contains("\"id\":\"9223372036854775807\""));
                assertFalse(response.body().contains("synthetic-private-provider-name"));
                assertFalse(response.body().contains("ownerId"));
                assertFalse(response.body().contains("token"));
                verify(service).ownedRooms(42, null, 20);
                for (String query : List.of("size=0", "size=51", "before=01", "before=0")) {
                    var invalid = client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/mine?" + query))
                            .header("X-Koko-Gateway-Key", KEY)
                            .header("X-Koko-User-Id", "42")
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    );
                    assertEquals(400, invalid.statusCode());
                }
                verifyNoMoreInteractions(service);
            }
        }
    }

    @Test
    void ownerCloseUses204AndKeepsResourceOwnershipError() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.register(HttpConfiguration.class);
            context.refresh();
            var service = context.getBean(VoiceApplicationService.class);
            doThrow(new ResourceNotFoundException("语音房不存在或无权操作")).when(service).close(43, 1);
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                for (String user : List.of("42", "43")) {
                    var response = client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/1"))
                            .header("X-Koko-Gateway-Key", KEY)
                            .header("X-Koko-User-Id", user)
                            .DELETE()
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    );
                    assertEquals(user.equals("42") ? 204 : 404, response.statusCode());
                    if (user.equals("42")) assertEquals("", response.body());
                    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                }
                verify(service).close(42, 1);
                verify(service).close(43, 1);
            }
        }
    }

    @Test
    void actualServletRejectsSpoofAndAllowsTrustedDiscoveryAndPrivateJoinWithoutCaching() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.register(HttpConfiguration.class);
            context.refresh();
            var service = context.getBean(VoiceApplicationService.class);
            when(service.discover(12)).thenReturn(List.of());
            when(service.join(42, 1)).thenReturn(
                new VoiceApplicationService.JoinCredential("ws://127.0.0.1:7880", "synthetic-token", "test")
            );
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                var forged = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/1/join"))
                        .header("X-Koko-User-Id", "42")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(403, forged.statusCode());
                verifyNoInteractions(service);
                var discovery = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/discovery"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, discovery.statusCode());
                assertEquals("[]", discovery.body());
                var joined = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/1/join"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, joined.statusCode());
                assertTrue(joined.body().contains("synthetic-token"));
                assertEquals("no-store", joined.headers().firstValue("Cache-Control").orElseThrow());
                var duplicate = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/voice/rooms/1/join"))
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-Gateway-Key", KEY)
                        .header("X-Koko-User-Id", "42")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(403, duplicate.statusCode());
                verify(service, times(1)).join(42, 1);
            }
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({ VoiceController.class, VoiceInteractionController.class, GlobalExceptionHandler.class })
    static class HttpConfiguration {

        @Bean
        VoiceApplicationService voiceService() {
            return mock(VoiceApplicationService.class);
        }

        @Bean
        VoiceInteractionService interaction() {
            return mock(VoiceInteractionService.class);
        }

        @Bean
        VoiceInteractionDirectory directory() {
            return mock(VoiceInteractionDirectory.class);
        }

        @Bean
        FilterRegistrationBean<VoiceGatewayFilter> trustFilter() {
            return new FilterRegistrationBean<>(new VoiceGatewayFilter(KEY));
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> servlet(WebApplicationContext context) {
            return new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
        }

        @Bean
        TomcatServletWebServerFactory webServer() throws Exception {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getByName("127.0.0.1"));
            factory.setBaseDirectory(Path.of("D:/KOKO/deploy/voice-p0-http-runtime-20261005").toFile());
            return factory;
        }
    }
}
