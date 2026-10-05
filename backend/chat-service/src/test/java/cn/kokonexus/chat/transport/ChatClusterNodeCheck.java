package cn.kokonexus.chat.transport;

import cn.kokonexus.chat.application.ChatClusterFixture;
import cn.kokonexus.chat.domain.ChatSyncRevision;
import cn.kokonexus.chat.persistence.ChatSyncMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立 JVM 的真实 Netty/事务/SQL 节点；身份固定夹具，不证明 Sa-Token/Redis/Gateway 集成。 */
public final class ChatClusterNodeCheck {

    /** 明确合成的内部密钥，不能用于任何部署环境。 */
    public static final String FIXTURE_KEY = "test-cluster-gateway-key-not-for-deployment";

    public static void main(String[] args) throws Exception {
        var fixture = new ChatClusterFixture();
        // 保留真实 Origin/内部密钥握手，仅将共享登录服务替换为精确、有限的夹具身份。
        var security = new ChatSecurity(FIXTURE_KEY, "http://127.0.0.1") {
            @Override
            public boolean active(long userId, String token) {
                return (userId == 9201 || userId == 9202 || userId == 9300) && ("test-device-" + userId).equals(token);
            }
        };
        // 本旧SQL/分发夹具不验证新全局配额；不得因此宣称跨JVM准入验收。
        var quota = org.mockito.Mockito.mock(ChatConnectionQuota.class);
        org.mockito.Mockito.when(
            quota.acquire(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())
        ).thenReturn(true);
        org.mockito.Mockito.when(
            quota.renew(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())
        ).thenReturn(true);
        var sockets = new ChatSocketServer(
            fixture.service,
            new ObjectMapper().findAndRegisterModules(),
            security,
            quota,
            new SimpleMeterRegistry(),
            0,
            "127.0.0.1"
        );
        var queryFault = new AtomicBoolean();
        var productionMapper = fixture.sessions.getMapper(ChatSyncMapper.class);
        var observingMapper = new ChatSyncMapper() {
            @Override
            public int advance(long userId) {
                return productionMapper.advance(userId);
            }

            @Override
            public List<ChatSyncRevision> revisions(List<Long> userIds) {
                if (queryFault.get()) fixture.jdbc.queryForObject(
                    "SELECT revision FROM chat_sync_missing_fixture",
                    Long.class
                );
                return productionMapper.revisions(userIds);
            }
        };
        var meters = new SimpleMeterRegistry();
        var poller = new ChatSyncPoller(observingMapper, sockets, meters, 250);
        try {
            sockets.start();
            poller.start();
            System.out.println("CLUSTER_NODE_READY port=" + sockets.boundPort());
            System.out.flush();
            var input = new BufferedReader(new InputStreamReader(System.in));
            String command;
            while ((command = input.readLine()) != null && !"QUIT".equals(command)) {
                switch (command) {
                    case "FAIL_SCAN" -> queryFault.set(true);
                    case "RECOVER_SCAN" -> queryFault.set(false);
                    case "STATUS" -> System.out.println(
                        "CLUSTER_SCAN_FAILED " + (int) meters.get("koko.chat.sync.failed").gauge().value()
                    );
                    default -> throw new IllegalArgumentException("未知隔离节点命令");
                }
                System.out.println("CLUSTER_COMMAND_DONE " + command);
                System.out.flush();
            }
        } finally {
            poller.stop();
            sockets.stop();
            meters.close();
        }
    }
}
