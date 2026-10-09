package cn.kokonexus.gateway.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

/** 发布前解析真实配置，不能仅由模拟控制器测试证明服务可启动。 */
class StartupConfigurationTest {

    @Test
    void httpDiscoveryGroupIsSeparatedFromDubboForEveryHttpService() throws Exception {
        for (String module : java.util.List.of(
            "gateway",
            "community",
            "live",
            "voice",
            "notification",
            "asset",
            "chat"
        )) {
            Path config = Path.of("..", module + "-service", "src/main/resources/application.yml");
            var properties = new YamlPropertySourceLoader().load(module, new FileSystemResource(config)).getFirst();
            // 不读取机器环境；分别验证默认分组、隔离分组继承及明确覆盖的解析结果。
            var environment = new org.springframework.core.env.StandardEnvironment();
            environment.getPropertySources().remove("systemEnvironment");
            environment.getPropertySources().remove("systemProperties");
            environment.getPropertySources().addLast(properties);
            String httpKey = "spring.cloud.nacos.discovery.group";
            String rpcKey = "dubbo.registry.group";
            assertEquals("KOKO_NEXUS_PROD_HTTP", environment.getProperty(httpKey), module);
            assertEquals("KOKO_NEXUS_PROD", environment.getProperty(rpcKey), module);
            environment
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "isolated",
                        java.util.Map.of("NACOS_GROUP", "KOKO_ISOLATED")
                    )
                );
            assertEquals("KOKO_ISOLATED_HTTP", environment.getProperty(httpKey), module);
            assertEquals("KOKO_ISOLATED", environment.getProperty(rpcKey), module);
            environment
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "http-override",
                        java.util.Map.of("NACOS_HTTP_GROUP", "KOKO_HTTP_ONLY")
                    )
                );
            assertEquals("KOKO_HTTP_ONLY", environment.getProperty(httpKey), module);
            assertEquals("KOKO_ISOLATED", environment.getProperty(rpcKey), module);
        }
    }

    @Test
    void discoveryRoutesInstallRealReactiveLoadBalancer() throws Exception {
        assertTrue(hasDependency(Path.of("pom.xml"), "org.springframework.cloud", "spring-cloud-starter-loadbalancer"));
        assertNotNull(Class.forName("org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer"));
        assertNotNull(Class.forName("org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter"));
    }

    @Test
    void everyDocumentedHttpServiceInstallsSpringdocMvcEndpoint() throws Exception {
        for (String module : java.util.List.of("community", "live", "voice", "notification", "asset", "chat")) {
            assertTrue(
                hasDependency(
                    Path.of("..", module + "-service", "pom.xml"),
                    "org.springdoc",
                    "springdoc-openapi-starter-webmvc-api"
                ),
                module + " must expose its protected OpenAPI contract, not only annotation classes"
            );
        }
    }

    @Test
    void gatewayCookieSettingsBindToActualSaTokenConfig() throws Exception {
        var properties = new YamlPropertySourceLoader()
            .load("gateway", new ClassPathResource("application.yml"))
            .getFirst();
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().addLast(properties);
        var config = org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind("sa-token", cn.dev33.satoken.config.SaTokenConfig.class)
            .get();
        assertTrue(config.getCookie().getHttpOnly());
        assertEquals("Lax", config.getCookie().getSameSite());
        assertFalse(config.getCookie().getSecure());
        environment
            .getPropertySources()
            .addFirst(
                new org.springframework.core.env.MapPropertySource(
                    "formal-tls",
                    java.util.Map.of("KOKO_COOKIE_SECURE", "true")
                )
            );
        assertTrue(
            org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("sa-token", cn.dev33.satoken.config.SaTokenConfig.class)
                .get()
                .getCookie()
                .getSecure()
        );
    }

    @Test
    void chatWebsocketMetadataUsesEachNodesConfiguredPort() throws Exception {
        var properties = new YamlPropertySourceLoader()
            .load("chat", new FileSystemResource(Path.of("..", "chat-service", "src/main/resources/application.yml")))
            .getFirst();
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().addLast(properties);
        String key = "spring.cloud.nacos.discovery.metadata.koko-chat-websocket-port";
        assertEquals("8097", environment.getProperty(key));
        environment
            .getPropertySources()
            .addFirst(
                new org.springframework.core.env.MapPropertySource(
                    "node-two",
                    java.util.Map.of("CHAT_WS_PORT", "42998")
                )
            );
        assertEquals("42998", environment.getProperty(key));
        assertEquals("42998", environment.getProperty("koko.chat.websocket-port"));
    }

    @Test
    void websocketRoutePrecedesRestAndDocumentationIsGetOnly() throws Exception {
        var properties = new YamlPropertySourceLoader()
            .load("gateway", new ClassPathResource("application.yml"))
            .getFirst();
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        assertEquals("chat-websocket-service", properties.getProperty(prefix + "[0].id"));
        assertEquals("Path=/api/chat/ws", properties.getProperty(prefix + "[0].predicates[0]"));
        assertEquals("chat-service", properties.getProperty(prefix + "[1].id"));
        assertEquals("Method=GET", properties.getProperty(prefix + "[2].predicates[1]"));
        assertEquals("asset-service", properties.getProperty(prefix + "[11].id"));
        assertEquals("livekit-signal", properties.getProperty(prefix + "[12].id"));
        assertEquals(
            "Path=/api/media/livekit/rtc,/api/media/livekit/rtc/v1",
            properties.getProperty(prefix + "[12].predicates[0]")
        );
        assertEquals("Method=GET", properties.getProperty(prefix + "[12].predicates[1]"));
        assertEquals("StripPrefix=3", properties.getProperty(prefix + "[12].filters[0]"));
        assertEquals("livekit-validate", properties.getProperty(prefix + "[13].id"));
        assertEquals(
            "Path=/api/media/livekit/rtc/validate,/api/media/livekit/rtc/v1/validate",
            properties.getProperty(prefix + "[13].predicates[0]")
        );
        assertEquals(
            "${MEDIA_ADMISSION_ENABLED:false}",
            properties.getProperty("koko.gateway.media-admission.enabled")
        );
    }

    @Test
    void everyServiceApplicationYamlParsesWithBootLoader() throws Exception {
        try (var modules = Files.list(Path.of("..").toAbsolutePath().normalize())) {
            for (Path module : modules.toList()) {
                Path config = module.resolve("src/main/resources/application.yml");
                if (Files.exists(config)) assertFalse(
                    new YamlPropertySourceLoader()
                        .load(module.getFileName().toString(), new FileSystemResource(config))
                        .isEmpty()
                );
            }
        }
    }

    /** 解析真实 Maven 依赖节点，而不是匹配易受换行影响的标签字符串；禁用外部实体。 */
    private boolean hasDependency(Path pom, String group, String artifact) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = Files.newInputStream(pom)) {
            var document = factory.newDocumentBuilder().parse(input);
            var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            return (Boolean) xpath.evaluate(
                "boolean(/project/dependencies/dependency[normalize-space(groupId)='" +
                    group +
                    "' and normalize-space(artifactId)='" +
                    artifact +
                    "'])",
                document,
                javax.xml.xpath.XPathConstants.BOOLEAN
            );
        }
    }
}
