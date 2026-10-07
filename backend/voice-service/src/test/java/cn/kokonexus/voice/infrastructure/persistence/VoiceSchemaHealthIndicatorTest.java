package cn.kokonexus.voice.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

/** 协议化就绪状态不复制数据库错误，也不将基础Schema冒充媒体就绪。 */
class VoiceSchemaHealthIndicatorTest {

    /** V1可连接但缺少新实体查询列，用于覆盖原先健康UP/业务500的具体触发条件。 */
    private static final List<String> V1_COLUMNS = List.of(
        "id",
        "owner_id",
        "owner_name",
        "slug",
        "title",
        "topic",
        "status",
        "provider_room_name",
        "max_participants",
        "created_at",
        "closed_at"
    );

    @Test
    void oldConnectedSchemaIsDownInsteadOfHealthyButDiscovery500() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(V1_COLUMNS);
        var result = new VoiceSchemaHealthIndicator(jdbc).health();
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails().get("missingColumns")).isEqualTo(List.of("control_mode", "interaction_version"));
        verify(jdbc).setQueryTimeout(2);
    }

    @Test
    void compatibleSchemaIsBasicReadinessWithoutRtcClaim() {
        var jdbc = mock(JdbcTemplate.class);
        var columns = new java.util.ArrayList<>(V1_COLUMNS);
        columns.addAll(List.of("control_mode", "interaction_version"));
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(columns);
        var result = new VoiceSchemaHealthIndicator(jdbc).health();
        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsOnlyKeys("scope").containsEntry("scope", "BASIC_ROOM_SCHEMA");
    }

    @Test
    void failedDependencyDoesNotExposeSqlOrCredentialInDetails() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("private-connection-diagnostic")
        );
        var result = new VoiceSchemaHealthIndicator(jdbc).health();
        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsOnlyKeys("reason").containsEntry("reason", "VOICE_SCHEMA_UNAVAILABLE");
        assertThat(result.getDetails().toString()).doesNotContain("private-connection-diagnostic");
    }
}
