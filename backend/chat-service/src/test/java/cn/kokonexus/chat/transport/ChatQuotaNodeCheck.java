package cn.kokonexus.chat.transport;

import cn.kokonexus.chat.application.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.Mockito;

/** 独立JVM真实Netty与生产Redis租约；登录/SQL明确为夹具，不证明完整Boot/Gateway。 */
public final class ChatQuotaNodeCheck {

    /** 仅本轮夹具的内部键，不用于部署。 */
    static final String FIXTURE_KEY = "test-quota-internal-key-not-for-deployment";

    public static void main(String[] args) throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var fixture = new ChatQuotaRedisFixture()) {
            var service = Mockito.mock(ChatService.class);
            var calls = new AtomicInteger();
            Mockito.when(
                service.send(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString())
            ).thenAnswer(call -> {
                calls.incrementAndGet();
                throw new IllegalStateException("合成业务边界不执行SQL");
            });
            var security = new ChatSecurity(FIXTURE_KEY, "http://127.0.0.1") {
                @Override
                public boolean active(long user, String token) {
                    return (user == 62001 || user == 62002) && "test-quota-session".equals(token);
                }
            };
            var quota = new RedisChatConnectionQuota(fixture.redis, meters);
            var server = new ChatSocketServer(
                service,
                new ObjectMapper().findAndRegisterModules(),
                security,
                quota,
                meters,
                0,
                "127.0.0.1"
            );
            try {
                server.start();
                System.out.println("QUOTA_NODE_READY " + server.boundPort());
                System.out.flush();
                var input = new BufferedReader(new InputStreamReader(System.in));
                String command;
                while ((command = input.readLine()) != null && !"QUIT".equals(command)) {
                    if (!"STATUS".equals(command)) throw new IllegalArgumentException("未知本轮节点命令");
                    System.out.println("QUOTA_BUSINESS_CALLS " + calls.get());
                    System.out.flush();
                }
            } finally {
                server.stop();
            }
        } finally {
            meters.close();
        }
    }
}
