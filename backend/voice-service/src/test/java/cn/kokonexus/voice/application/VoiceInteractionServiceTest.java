package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.voice.domain.VoiceInteraction.CommandType;
import cn.kokonexus.voice.infrastructure.persistence.VoiceInteractionMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 关闭开关与格式保护；事务/竞争/完整命令转换用真实MySQL验证。 */
class VoiceInteractionServiceTest {

    /** I/O桩，不代表真实数据库。 */
    private final VoiceInteractionMapper mapper = mock(VoiceInteractionMapper.class);

    @Test
    void disabledCoreNeverMutatesOrReturnsFakeEnabledCapability() {
        var service = new VoiceInteractionService(mapper, false);
        assertThat(service.capabilities(7, 1).enabled()).isFalse();
        assertThat(service.capabilities(7, 1).mediaReady()).isFalse();
        assertThatThrownBy(() -> service.snapshot(7, 1)).isInstanceOf(
            cn.kokonexus.common.api.ExternalDependencyUnavailableException.class
        );
        assertThatThrownBy(() -> service.join(7, 1, UUID.randomUUID().toString(), "0", "本人")).isInstanceOf(
            cn.kokonexus.common.api.ExternalDependencyUnavailableException.class
        );
        verifyNoInteractions(mapper);
    }

    @Test
    void malformedIdsSessionsVersionsAndCommandFieldsNeverReachSql() {
        var service = new VoiceInteractionService(mapper, true);
        String uuid = UUID.randomUUID().toString();
        for (String version : new String[] { null, "-1", "01", "9223372036854775808" }) {
            assertThatThrownBy(() -> service.join(7, 1, uuid, version, "本人")).isInstanceOf(
                IllegalArgumentException.class
            );
        }
        assertThatThrownBy(() -> service.join(0, 1, uuid, "0", "本人")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.join(7, 1, "1-1-1-1-1", "0", "本人")).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() ->
            service.command(7, 1, uuid, uuid, "0", CommandType.LOCK, 9, null, null, true)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            service.command(7, 1, uuid, uuid, "0", CommandType.LEAVE, 1, null, null, null)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            service.command(7, 1, uuid, uuid, "0", CommandType.MUTE, 1, null, null, null)
        ).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }
}
