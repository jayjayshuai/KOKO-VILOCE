package cn.kokonexus.community.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.api.asset.AssetRpcService;
import cn.kokonexus.api.asset.AssetUrl;
import cn.kokonexus.common.api.ExternalDependencyUnavailableException;
import cn.kokonexus.outbox.binding.BindingAttempt;
import cn.kokonexus.outbox.binding.BindingAttemptCoordinator;
import cn.kokonexus.outbox.binding.BindingAttemptReservation;
import cn.kokonexus.outbox.binding.BindingAttemptResolution;
import cn.kokonexus.outbox.binding.BindingReleaseWriter;
import cn.kokonexus.outbox.persistence.BindingAttemptMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 校验 RPC 参数与失败关闭；真实代理/提交回调另由公共事务测试核验。 */
class AssetBindingValidatorTest {

    private static final String ASSET = "0fc78935-9839-4721-92f5-7aaf13f00877";
    private final AssetRpcService rpc = mock(AssetRpcService.class);
    private final BindingReleaseWriter writer = mock(BindingReleaseWriter.class);
    private final BindingAttemptReservation reservation = mock(BindingAttemptReservation.class);
    private final BindingAttemptResolution resolution = mock(BindingAttemptResolution.class);
    private final BindingAttemptMapper attempts = mock(BindingAttemptMapper.class);
    private final AssetBindingValidator validator = new AssetBindingValidator(
        new BindingAttemptCoordinator(reservation, attempts, writer, resolution)
    );

    AssetBindingValidatorTest() {
        ReflectionTestUtils.setField(validator, "assetRpcService", rpc);
        when(attempts.lock(anyString())).thenAnswer(call -> {
            BindingAttempt row = new BindingAttempt();
            row.setRequestId(call.getArgument(0));
            row.setOwnerId(42L);
            row.setAssetId(ASSET);
            row.setPurpose("POST_COVER");
            row.setStatus("OPEN");
            return row;
        });
        when(attempts.finish(any(), eq("COMMITTED"))).thenReturn(1);
        when(resolution.abort(anyString())).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        if (
            TransactionSynchronizationManager.isSynchronizationActive()
        ) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void noTransactionFailsBeforeAnyRpc() {
        assertThrows(IllegalStateException.class, () -> validator.assertOwnedReady(42, ASSET, "POST_COVER"));
        verifyNoInteractions(rpc);
    }

    @Test
    void falseAcquisitionDoesNotFallBackToReadOnlyOrRegisterRelease() {
        context();
        assertThrows(IllegalArgumentException.class, () -> validator.assertOwnedReady(42, ASSET, "POST_COVER"));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(writer);
        verify(rpc, never()).isOwnedReady(anyString(), anyString(), anyString());
        verify(rpc, never()).completeBinding(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownOrOldProviderFailureFailsClosedAndRetainsPossibleIntent() {
        context();
        when(rpc.beginBinding(anyString(), anyString(), anyString(), anyString())).thenThrow(
            new IllegalStateException("simulated missing method or lost response")
        );
        assertThrows(ExternalDependencyUnavailableException.class, () ->
            validator.assertOwnedReady(42, ASSET, "POST_COVER")
        );
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verifyNoInteractions(writer);
        verify(rpc, never()).isOwnedReady(anyString(), anyString(), anyString());
        verify(rpc, never()).completeBinding(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void successfulAcquireReleasesTheExactRequestOnlyAfterKnownCompletion() {
        context();
        when(rpc.beginBinding(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        validator.assertOwnedReady(42, ASSET, "POST_COVER");
        var request = ArgumentCaptor.forClass(String.class);
        verify(rpc).beginBinding(eq("42"), eq(ASSET), eq("POST_COVER"), request.capture());
        assertEquals(request.getValue(), AssetUrl.canonicalId(request.getValue()));
        verify(rpc, never()).completeBinding(anyString(), anyString(), anyString(), anyString());
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(resolution).abort(request.getValue());
        verify(writer).stage(42, ASSET, "POST_COVER", request.getValue());
        verify(rpc).sealBinding("42", ASSET, "POST_COVER", request.getValue());
        verifyNoMoreInteractions(rpc);
    }

    private void context() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
