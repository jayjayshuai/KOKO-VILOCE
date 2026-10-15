package cn.kokonexus.gateway.configuration;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** 媒体候选启用时要求显式配置双上游，禁止容器误连自身localhost仍宣称就绪。 */
@Configuration
public class MediaSignalUpstreamConfiguration {

    @org.springframework.beans.factory.annotation.Autowired
    public MediaSignalUpstreamConfiguration(
        @Value("${koko.gateway.media-admission.enabled:false}") boolean enabled,
        @Value("${LIVEKIT_SIGNAL_WS_URI:}") String signal,
        @Value("${LIVEKIT_SIGNAL_HTTP_URI:}") String validation,
        @Value("${koko.gateway.native-calls-enabled:false}") boolean nativeEnabled
    ) {
        if (!enabled && !nativeEnabled) return;
        URI websocket = upstream(signal, "ws", "wss"),
            http = upstream(validation, "http", "https");
        if (
            !websocket.getHost().equalsIgnoreCase(http.getHost()) ||
            effectivePort(websocket) != effectivePort(http) ||
            "wss".equals(websocket.getScheme()) != "https".equals(http.getScheme())
        ) throw new IllegalStateException("媒体信令及验证必须指向同一个显式上游");
    }

    /** 保留公开语音配置的原构造，私人候选缺省关闭。 */
    public MediaSignalUpstreamConfiguration(boolean enabled, String signal, String validation) {
        this(enabled, signal, validation, false);
    }

    /** 上游为服务基址，路由负责追加精确rtc路径；拒绝嵌入凭据、查询串和已有路径。 */
    private static URI upstream(String value, String plain, String secure) {
        try {
            URI uri = URI.create(value);
            if (
                uri.getHost() == null ||
                !(plain.equals(uri.getScheme()) || secure.equals(uri.getScheme())) ||
                uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null ||
                !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())) ||
                uri.getPort() == 0 ||
                uri.getPort() > 65535
            ) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            // URI解析消息可能含凭据，只给固定描述，不保留原异常作为cause。
            throw new IllegalStateException("媒体候选需显式配置有效的信令及验证上游基址");
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0
            ? "wss".equals(uri.getScheme()) || "https".equals(uri.getScheme())
                ? 443
                : 80
            : uri.getPort();
    }
}
