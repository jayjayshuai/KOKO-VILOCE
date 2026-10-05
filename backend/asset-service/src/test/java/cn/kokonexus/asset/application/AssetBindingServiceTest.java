package cn.kokonexus.asset.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.kokonexus.asset.domain.AssetBindingIntent;
import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.AssetBindingIntentMapper;
import org.junit.jupiter.api.Test;

/** 只验证用例分支；行锁、外键、提交/回滚需要真实 MySQL 集成证据。 */
class AssetBindingServiceTest {

    private static final String ASSET = "0fc78935-9839-4721-92f5-7aaf13f00877";
    private static final String REQUEST = "cfb49d66-46aa-4f6f-9a14-1285a4811425";
    private final AssetBindingIntentMapper mapper = mock(AssetBindingIntentMapper.class);
    private final AssetBindingService service = new AssetBindingService(mapper);

    @Test
    void locksAssetBeforeValidationAndPersistsTheFullFingerprint() {
        when(mapper.lockAsset(ASSET)).thenReturn(asset("READY", 42L, "AVATAR"));
        when(mapper.insert(any(AssetBindingIntent.class))).thenAnswer(invocation -> {
            AssetBindingIntent intent = invocation.getArgument(0);
            assertEquals(REQUEST, intent.getRequestId());
            assertEquals(ASSET, intent.getAssetId());
            assertEquals(42L, intent.getOwnerId());
            assertEquals("AVATAR", intent.getPurpose());
            return 1;
        });
        assertTrue(service.begin(42, ASSET, "AVATAR", REQUEST));
        var order = inOrder(mapper);
        order.verify(mapper).lockAsset(ASSET);
        order.verify(mapper).selectById(REQUEST);
        order.verify(mapper).insert(any(AssetBindingIntent.class));
    }

    @Test
    void missingForeignWrongPurposeAndNonReadyNeverCreateAnIntent() {
        for (MediaAsset asset : new MediaAsset[] {
            null,
            asset("READY", 43L, "AVATAR"),
            asset("READY", 42L, "BANNER"),
            asset("PENDING", 42L, "AVATAR"),
            asset("CLEANING", 42L, "AVATAR"),
            asset("RETIRING", 42L, "AVATAR"),
        }) {
            when(mapper.lockAsset(ASSET)).thenReturn(asset);
            assertFalse(service.begin(42, ASSET, "AVATAR", REQUEST));
        }
        verify(mapper, never()).insert(any(AssetBindingIntent.class));
        verify(mapper, never()).selectById(any());
    }

    @Test
    void identicalRetryIsIdempotentButAChangedFingerprintIsRejected() {
        when(mapper.lockAsset(ASSET)).thenReturn(asset("READY", 42L, "AVATAR"));
        AssetBindingIntent intent = new AssetBindingIntent();
        intent.setRequestId(REQUEST);
        intent.setAssetId(ASSET);
        intent.setOwnerId(42L);
        intent.setPurpose("AVATAR");
        when(mapper.selectById(REQUEST)).thenReturn(intent);
        assertTrue(service.begin(42, ASSET, "AVATAR", REQUEST));
        intent.setPurpose("BANNER");
        assertThrows(IllegalArgumentException.class, () -> service.begin(42, ASSET, "AVATAR", REQUEST));
        verify(mapper, never()).insert(any(AssetBindingIntent.class));
    }

    @Test
    void persistenceFailureIsNotReportedAsProtectionSuccess() {
        when(mapper.lockAsset(ASSET)).thenReturn(asset("READY", 42L, "AVATAR"));
        assertThrows(IllegalStateException.class, () -> service.begin(42, ASSET, "AVATAR", REQUEST));
    }

    @Test
    void completeUsesAssetLockAndFullFingerprintAndIsRepeatable() {
        when(mapper.lockCompletion(REQUEST)).thenReturn(null, completion());
        when(mapper.insertCompletion(42, ASSET, "AVATAR", REQUEST)).thenReturn(1);
        service.complete(42, ASSET, "AVATAR", REQUEST);
        service.complete(42, ASSET, "AVATAR", REQUEST);
        var order = inOrder(mapper);
        order.verify(mapper).lockAsset(ASSET);
        order.verify(mapper).lockCompletion(REQUEST);
        order.verify(mapper).selectById(REQUEST);
        order.verify(mapper).insertCompletion(42, ASSET, "AVATAR", REQUEST);
        order.verify(mapper).deleteMatching(42, ASSET, "AVATAR", REQUEST);
        order.verify(mapper).lockAsset(ASSET);
        order.verify(mapper).lockCompletion(REQUEST);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void completedRequestCannotBeRevivedByLateBegin() {
        when(mapper.lockCompletion(REQUEST)).thenReturn(completion());
        assertFalse(service.begin(42, ASSET, "AVATAR", REQUEST));
        assertThrows(IllegalArgumentException.class, () -> service.begin(43, ASSET, "AVATAR", REQUEST));
        verify(mapper, never()).insert(any(AssetBindingIntent.class));
    }

    @Test
    void completionPersistenceFailureNeverRemovesProtection() {
        assertThrows(IllegalStateException.class, () -> service.complete(42, ASSET, "AVATAR", REQUEST));
        verify(mapper, never()).deleteMatching(42, ASSET, "AVATAR", REQUEST);
    }

    @Test
    void foreignOwnerOrPurposeCannotSealAnActiveAsset() {
        when(mapper.lockAsset(ASSET)).thenReturn(asset("READY", 43L, "AVATAR"));
        assertThrows(IllegalArgumentException.class, () -> service.complete(42, ASSET, "AVATAR", REQUEST));
        verify(mapper, never()).insertCompletion(42, ASSET, "AVATAR", REQUEST);
        verify(mapper, never()).deleteMatching(42, ASSET, "AVATAR", REQUEST);
    }

    private AssetBindingIntent completion() {
        var intent = new AssetBindingIntent();
        intent.setRequestId(REQUEST);
        intent.setAssetId(ASSET);
        intent.setOwnerId(42L);
        intent.setPurpose("AVATAR");
        return intent;
    }

    @Test
    void invalidRequestsFailBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.begin(0, ASSET, "AVATAR", REQUEST));
        assertThrows(IllegalArgumentException.class, () -> service.begin(42, null, "AVATAR", REQUEST));
        assertThrows(IllegalArgumentException.class, () -> service.begin(42, ASSET, null, REQUEST));
        assertThrows(IllegalArgumentException.class, () -> service.begin(42, ASSET, "VIDEO", REQUEST));
        assertThrows(IllegalArgumentException.class, () -> service.begin(42, ASSET, "AVATAR", "1-2-3-4-5"));
        verifyNoInteractions(mapper);
    }

    private MediaAsset asset(String status, long owner, String purpose) {
        var asset = new MediaAsset();
        asset.setId(ASSET);
        asset.setOwnerId(owner);
        asset.setPurpose(purpose);
        asset.setStatus(status);
        return asset;
    }
}
