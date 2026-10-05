package cn.kokonexus.asset.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.AssetUsageMapper;
import cn.kokonexus.asset.infrastructure.persistence.MediaAssetMapper;
import org.junit.jupiter.api.Test;

class AssetRegistrationServiceTest {

    private final AssetUsageMapper usage = mock(AssetUsageMapper.class);
    private final MediaAssetMapper assets = mock(MediaAssetMapper.class);
    private final AssetRegistrationService service = new AssetRegistrationService(usage, assets, 1000, 5, 5000, 25);

    @Test
    void quotaMustBeReservedBeforeAnIntentIsInserted() {
        var image = pending();
        when(usage.reserve(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(assets.insert(image)).thenReturn(1);
        service.register(image);
        var order = inOrder(usage, assets);
        order.verify(usage).ensureOwner(0);
        order.verify(usage).reserve(0, 200, 5000, 25);
        order.verify(usage).ensureOwner(42);
        order.verify(usage).reserve(42, 200, 1000, 5);
        order.verify(assets).insert(image);
    }

    @Test
    void refusesBucketAndOwnerOverflowBeforePersistence() {
        assertTrue(assertThrows(AssetQuotaExceededException.class, () -> service.register(pending())).sharedCapacity());
        when(usage.reserve(0, 200, 5000, 25)).thenReturn(1);
        assertFalse(
            assertThrows(AssetQuotaExceededException.class, () -> service.register(pending())).sharedCapacity()
        );
        verify(assets, never()).insert(any(MediaAsset.class));
    }

    @Test
    void repeatedCleanupDoesNotReleaseQuotaTwice() {
        service.deleteCleaning(pending());
        verifyNoInteractions(usage);
    }

    @Test
    void quotaCorruptionRollsBackTheCleaningTransaction() {
        when(assets.delete(any())).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.deleteCleaning(pending()));
    }

    @Test
    void missingOwnerHasAnEmptyBudgetAndInvalidConfigurationFailsStartup() {
        assertEquals(new AssetRegistrationService.QuotaView(0, 1000, 0, 5), service.quota(42));
        assertThrows(IllegalStateException.class, () -> new AssetRegistrationService(usage, assets, 1000, 5, 999, 25));
    }

    private MediaAsset pending() {
        var asset = new MediaAsset();
        asset.setId("ee432e0b-4a71-4f88-b55a-3df6fe5b0a99");
        asset.setOwnerId(42L);
        asset.setByteSize(200L);
        asset.setStatus("PENDING");
        return asset;
    }
}
