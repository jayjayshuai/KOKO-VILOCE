package cn.kokonexus.identity.tooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** CLI 参数只解析，不连接数据库，不执行 System.exit，也不批准生产授权。 */
class OperationsBootstrapCliTest {

    @Test
    void explicitCommandRequiresAllArgumentsAndPreservesOriginalId() {
        String[] args = {
            "plan",
            "koko_identity",
            "80",
            "isolated_80",
            "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA",
            "2026-10-04T00:00:00.123456",
            "  隔离初始化明确审批原因  ",
        };
        var command = OperationsBootstrapCli.parse(args);
        assertThat(command.requestId()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(command.expiresAt().getNano()).isEqualTo(123456000);
        assertThat(command.reason()).isEqualTo("隔离初始化明确审批原因");
        args[0] = "startup";
        assertThatThrownBy(() -> OperationsBootstrapCli.parse(args)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperationsBootstrapCli.parse(new String[0])).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    @Test
    void invalidSchemaOverflowAndAmbiguousTimezoneRejected() {
        String[] args = {
            "apply",
            "koko_identity",
            "80",
            "isolated_80",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "2026-10-04T00:00:00",
            "隔离初始化明确审批原因",
        };
        args[1] = "koko_identity; DROP DATABASE";
        assertThatThrownBy(() -> OperationsBootstrapCli.parse(args)).isInstanceOf(IllegalArgumentException.class);
        args[1] = "koko_identity";
        args[2] = "9999999999999999999";
        assertThatThrownBy(() -> OperationsBootstrapCli.parse(args)).isInstanceOf(IllegalArgumentException.class);
        args[2] = "80";
        args[5] = "2026-10-04T00:00:00Z";
        assertThatThrownBy(() -> OperationsBootstrapCli.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }
}
