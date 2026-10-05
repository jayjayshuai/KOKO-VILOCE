package cn.kokonexus.outbox.binding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.outbox.persistence.BindingReleaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 用例参数和禁止无事务写入；真实代理同事务提交/回滚由隔离 MySQL 核验。 */
class BindingReleaseWriterTest {

    private static final String ASSET = "81000000-0000-4000-8000-000000000001";
    private static final String REQUEST = "81000000-0000-4000-8000-000000000002";
    private final BindingReleaseMapper mapper = mock(BindingReleaseMapper.class);
    private final BindingReleaseWriter writer = new BindingReleaseWriter(mapper);

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void rejectsAbsentOrReadOnlyTransactionBeforeAnySql() {
        assertThrows(IllegalStateException.class, () -> writer.stage(42, ASSET, "AVATAR", REQUEST));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> writer.stage(42, ASSET, "AVATAR", REQUEST));
        verifyNoInteractions(mapper);
    }

    @Test
    void persistsUnchangedFingerprintAndStartsPendingWithoutLease() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.insert(any(BindingRelease.class))).thenReturn(1);
        writer.stage(42, ASSET, "AVATAR", REQUEST);
        var value = ArgumentCaptor.forClass(BindingRelease.class);
        verify(mapper).insert(value.capture());
        assertEquals(42L, value.getValue().getOwnerId());
        assertEquals(ASSET, value.getValue().getAssetId());
        assertEquals(REQUEST, value.getValue().getRequestId());
        assertEquals("AVATAR", value.getValue().getPurpose());
        assertEquals("PENDING", value.getValue().getStatus());
        assertEquals(0, value.getValue().getAttempts());
        assertNull(value.getValue().getLeaseToken());
    }

    @Test
    void zeroAffectedRowsFailsTheBusinessTransactionRatherThanSilentlyLosingRecovery() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> writer.stage(42, ASSET, "AVATAR", REQUEST));
    }
}
