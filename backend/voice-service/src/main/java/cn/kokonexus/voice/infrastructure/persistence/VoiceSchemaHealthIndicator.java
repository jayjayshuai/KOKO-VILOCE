package cn.kokonexus.voice.infrastructure.persistence;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 基础房间投影所需Schema就绪检查；数据库可连接不代表当前代码能读房间。 */
@Component("voiceSchema")
public class VoiceSchemaHealthIndicator implements HealthIndicator {

    /** MP房间实体查询涉及的实际列；缺V3也会破坏普通发现接口。 */
    private static final List<String> REQUIRED_COLUMNS = List.of(
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
        "closed_at",
        "control_mode",
        "interaction_version"
    );
    /** 只读information_schema，不读取成员数据或执行DDL；等待最多两秒SQL执行时间。 */
    private final JdbcTemplate jdbc;

    @Autowired
    public VoiceSchemaHealthIndicator(DataSource source) {
        this(new JdbcTemplate(source));
    }

    VoiceSchemaHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.setQueryTimeout(2);
    }

    /** 仅检查基础SQL兼容；不能据此声明LiveKit、麦位或媒体撤权已经就绪。 */
    @Override
    public Health health() {
        try {
            var columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='voice_room'",
                String.class
            );
            var missing = REQUIRED_COLUMNS.stream()
                .filter(column -> !columns.contains(column))
                .toList();
            return missing.isEmpty()
                ? Health.up().withDetail("scope", "BASIC_ROOM_SCHEMA").build()
                : Health.down()
                      .withDetail("reason", "VOICE_SCHEMA_INCOMPATIBLE")
                      .withDetail("missingColumns", missing)
                      .build();
        } catch (org.springframework.dao.DataAccessException unavailable) {
            // JDBC异常可能包含SQL/地址，不向健康详情复制原异常。
            return Health.down().withDetail("reason", "VOICE_SCHEMA_UNAVAILABLE").build();
        }
    }
}
