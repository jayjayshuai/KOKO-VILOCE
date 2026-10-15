package cn.kokonexus.chat.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.chat.infrastructure.media.*;
import cn.kokonexus.common.api.ForbiddenOperationException;
import org.junit.jupiter.api.Test;

/** 用例编排的明确网络桩；SQL同意/租约由真实MySQL检查另行验证。 */
class NativeCallMediaServiceTest {

    @Test
    void provisioningNeverHoldsTransactionAndRevocationDuringNetworkCannotProduceCredential() {
        var calls = mock(NativeCallService.class);
        var identity = mock(NativeCallIdentity.class);
        var media = mock(NativeCallMediaGateway.class);
        var tokens = mock(NativeCallTokens.class);
        when(identity.active(1)).thenReturn(true);
        when(calls.mediaGrant(1, "scope", "call"))
            .thenReturn(new NativeCallService.MediaGrant("call", "identity", "name"))
            .thenThrow(new ForbiddenOperationException("revoked"));
        doAnswer(invocation -> {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            );
            return null;
        })
            .when(media)
            .provision("call");
        var service = new NativeCallMediaService(calls, identity, tokens, media);
        assertThrows(ForbiddenOperationException.class, () -> service.issue(1, "scope", "call"));
        verify(media).provision("call");
        verifyNoInteractions(tokens);
    }

    @Test
    void inactiveIdentityCannotTouchSqlOrMedia() {
        var calls = mock(NativeCallService.class);
        var identity = mock(NativeCallIdentity.class);
        var media = mock(NativeCallMediaGateway.class);
        var tokens = mock(NativeCallTokens.class);
        assertThrows(ForbiddenOperationException.class, () ->
            new NativeCallMediaService(calls, identity, tokens, media).issue(1, "scope", "call")
        );
        verifyNoInteractions(calls, media, tokens);
    }
}
