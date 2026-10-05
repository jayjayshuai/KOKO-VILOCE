package cn.kokonexus.gateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.kokonexus.api.operations.BindingReleaseAuditPage;
import cn.kokonexus.api.operations.BindingReleaseAuditView;
import cn.kokonexus.api.operations.BindingReleaseCursor;
import cn.kokonexus.api.operations.BindingReleasePage;
import cn.kokonexus.api.operations.BindingReleaseReplayCommand;
import cn.kokonexus.api.operations.BindingReleaseReplayReceipt;
import cn.kokonexus.api.operations.BindingReleaseSnapshot;
import cn.kokonexus.api.operations.BindingReleaseView;
import cn.kokonexus.api.operations.OperationsAccess;
import cn.kokonexus.api.operations.OperationsRoleChangeCommand;
import cn.kokonexus.api.operations.OperationsRoleChangeReceipt;
import cn.kokonexus.api.operations.OutboxAuditPage;
import cn.kokonexus.api.operations.OutboxAuditView;
import cn.kokonexus.api.operations.OutboxDeadPage;
import cn.kokonexus.api.operations.OutboxEventCursor;
import cn.kokonexus.api.operations.OutboxEventView;
import cn.kokonexus.api.operations.OutboxReplayCommand;
import cn.kokonexus.api.operations.OutboxReplayReceipt;
import cn.kokonexus.api.operations.ReplayConfirmation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.hessian2.Hessian2Serialization;
import org.junit.jupiter.api.Test;

/** 使用当前 Dubbo 的实际编解码器，不用 JSON/mock 掩盖 RPC DTO 不可序列化问题。 */
class OperationsSerializationTest {

    /** 仅测试的未授权载荷，不加入生产白名单，用于证明 STRICT 仍拒绝。 */
    public record UnlistedPayload(String marker) implements Serializable {}

    @Test
    void explicitContractsDoNotDisableStrictClassAllowlist() throws Exception {
        var codec = new Hessian2Serialization();
        var url = URL.valueOf("tri://127.0.0.1:1/isolated-codec-test");
        var bytes = new ByteArrayOutputStream();
        var output = codec.serialize(url, bytes);
        output.writeObject(new UnlistedPayload("isolated-denied-payload"));
        output.flushBuffer();
        var input = codec.deserialize(url, new ByteArrayInputStream(bytes.toByteArray()));
        assertThatThrownBy(() -> input.readObject(UnlistedPayload.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not in allow list");
    }

    @Test
    void allOperationsRpcRecordsRoundTripThroughActualHessian2() throws Exception {
        var time = LocalDateTime.of(2026, 10, 3, 14, 0, 0, 123456000);
        var event = new OutboxEventView(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "FOLLOW",
            "9007199254740993",
            "10",
            "resource",
            "隔离摘要",
            "DEAD",
            10,
            "9007199254740995",
            2,
            "隔离错误",
            time,
            time,
            null
        );
        var audit = new OutboxAuditView(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            event.id(),
            "10",
            1,
            2,
            "隔离测试原因不少于十个字符",
            10,
            "9007199254740995",
            null,
            time
        );
        var cursor = new OutboxEventCursor(time, event.id());
        var release = new BindingReleaseView(
            audit.requestId(),
            event.id(),
            "AVATAR",
            "DEAD",
            10,
            0,
            10,
            "lease-exhausted",
            time,
            time,
            time
        );
        var releaseCursor = new BindingReleaseCursor(time, release.requestId());
        var releaseAudit = new BindingReleaseAuditView(
            audit.requestId(),
            release.requestId(),
            "10",
            0,
            1,
            10,
            10,
            null,
            audit.reason(),
            time
        );
        var values = List.of(
            new BindingReleaseReplayCommand(audit.requestId(), release.requestId(), 0, audit.reason()),
            new BindingReleaseReplayReceipt(audit.requestId(), release.requestId(), 1, time),
            releaseAudit,
            new BindingReleaseAuditPage(List.of(releaseAudit), 1),
            release,
            releaseCursor,
            new BindingReleasePage(List.of(release), releaseCursor),
            new BindingReleaseSnapshot(1001, 0, 1, 1001, 30, 60, time),
            new OperationsAccess(true, List.of("NOTIFICATION_OPERATOR"), List.of("notification:outbox:read")),
            new OperationsRoleChangeCommand(
                audit.requestId(),
                "20",
                "NOTIFICATION_AUDITOR",
                true,
                0,
                null,
                audit.reason()
            ),
            new OperationsRoleChangeReceipt(audit.requestId(), "20", "NOTIFICATION_AUDITOR", "ACTIVE", 1, null, time),
            audit,
            new OutboxAuditPage(List.of(audit), 2L),
            cursor,
            event,
            new OutboxDeadPage(List.of(event), cursor),
            new OutboxReplayCommand(audit.requestId(), event.id(), 1, audit.reason()),
            new OutboxReplayReceipt(audit.requestId(), event.id(), 2, time),
            new ReplayConfirmation("a".repeat(43), time)
        );
        var codec = new Hessian2Serialization();
        var url = URL.valueOf("tri://127.0.0.1:1/isolated-codec-test");
        for (Object value : values) {
            assertThat(value).isInstanceOf(Serializable.class);
            var bytes = new ByteArrayOutputStream();
            var output = codec.serialize(url, bytes);
            output.writeObject(value);
            output.flushBuffer();
            var input = codec.deserialize(url, new ByteArrayInputStream(bytes.toByteArray()));
            assertThat(input.readObject(value.getClass())).isEqualTo(value);
        }
    }
}
