package cn.kokonexus.voice.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.voice.*;
import cn.kokonexus.voice.infrastructure.persistence.VoiceMediaPlanMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 有界发现/当前事务代理调用及部分完成语义；真实锁/回滚另由MySQL实验验证。 */
class VoiceWebsiteSessionRetirementTest {

    @Test
    void discoversOutsideRoomTransactionsAndReportsIncompleteRegistration() {
        var media = mock(VoiceMediaPlanMapper.class);
        var core = mock(VoiceInteractionService.class);
        var service = new VoiceWebsiteSessionRetirement(media, core);
        String scope = WebsiteSessionScope.fromToken("synthetic-cookie");
        when(media.websiteRooms(42, scope, 16)).thenReturn(List.of(9L, 10L));
        when(media.websiteRooms(42, scope, 1)).thenReturn(List.of(11L));
        assertThat(service.retire(new MediaWebsiteSessionCommand("42", scope))).isFalse();
        verify(core).retireWebsiteSession(9, 42, scope);
        verify(core).retireWebsiteSession(10, 42, scope);
        when(media.websiteRooms(42, scope, 1)).thenReturn(List.of());
        assertThat(service.retire(new MediaWebsiteSessionCommand("42", scope))).isTrue();
    }

    @Test
    void invalidOrOverflowIdentityNeverReachesSql() {
        var media = mock(VoiceMediaPlanMapper.class);
        var core = mock(VoiceInteractionService.class);
        var service = new VoiceWebsiteSessionRetirement(media, core);
        assertThatThrownBy(() ->
            service.retire(
                new MediaWebsiteSessionCommand("9223372036854775808", WebsiteSessionScope.fromToken("synthetic-cookie"))
            )
        ).isInstanceOf(MediaAdmissionUnavailableException.class);
        assertThatThrownBy(() -> service.retire(new MediaWebsiteSessionCommand("42", "forged"))).isInstanceOf(
            MediaAdmissionUnavailableException.class
        );
        verifyNoInteractions(media, core);
    }
}
