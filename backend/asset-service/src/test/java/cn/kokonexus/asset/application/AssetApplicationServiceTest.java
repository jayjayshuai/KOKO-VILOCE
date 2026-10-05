package cn.kokonexus.asset.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.kokonexus.asset.domain.MediaAsset;
import cn.kokonexus.asset.infrastructure.persistence.MediaAssetMapper;
import cn.kokonexus.asset.infrastructure.rpc.AssetPublicationLookup;
import cn.kokonexus.asset.infrastructure.storage.AssetStorage;
import cn.kokonexus.common.api.ResourceNotFoundException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class AssetApplicationServiceTest {

    private final MediaAssetMapper mapper = mock(MediaAssetMapper.class);
    private final AssetStorage storage = mock(AssetStorage.class);
    private final AssetPublicationLookup publicationLookup = mock(AssetPublicationLookup.class);
    private final AssetRegistrationService registration = mock(AssetRegistrationService.class);
    private final AssetApplicationService service = new AssetApplicationService(
        mapper,
        storage,
        new ImageInspector(),
        publicationLookup,
        registration
    );

    @Test
    void persistsIntentBeforeObjectAndOnlyReturnsReady() throws Exception {
        when(mapper.update(eq(null), any())).thenReturn(1);
        MediaAsset uploaded = service.upload(42, "avatar", png());
        assertEquals("READY", uploaded.getStatus());
        assertEquals(42, uploaded.getOwnerId());
        assertEquals("AVATAR", uploaded.getPurpose());
        var order = inOrder(registration, mapper, storage);
        order.verify(registration).register(any(MediaAsset.class));
        order.verify(storage).put(eq(uploaded.getObjectKey()), any(byte[].class), eq("image/png"));
        order.verify(mapper).update(eq(null), any());
    }

    @Test
    void rejectsInvalidPurposeBeforePersistence() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.upload(42, "SCRIPT", png()));
        verify(registration, never()).register(any(MediaAsset.class));
    }

    @Test
    void hidesOtherOwnersAsset() {
        MediaAsset asset = new MediaAsset();
        asset.setId("6cf7f30c-46d3-4fb3-b185-253760bb143f");
        asset.setOwnerId(42L);
        asset.setStatus("READY");
        when(mapper.selectById(asset.getId())).thenReturn(asset);
        assertThrows(ResourceNotFoundException.class, () -> service.ownedReady(43, asset.getId()));
    }

    @Test
    void unpublishedImageRemainsPrivateButPublishedImageIsReadable() {
        MediaAsset asset = new MediaAsset();
        asset.setId("6cf7f30c-46d3-4fb3-b185-253760bb143f");
        asset.setOwnerId(42L);
        asset.setStatus("READY");
        when(mapper.selectById(asset.getId())).thenReturn(asset);
        assertThrows(ResourceNotFoundException.class, () -> service.readable(asset.getId(), null));
        when(publicationLookup.isPublished(asset.getId())).thenReturn(true);
        assertEquals(asset, service.readable(asset.getId(), null));
    }

    @Test
    void bindingRejectsWrongPurpose() {
        MediaAsset asset = new MediaAsset();
        asset.setId("6cf7f30c-46d3-4fb3-b185-253760bb143f");
        asset.setOwnerId(42L);
        asset.setStatus("READY");
        asset.setPurpose("AVATAR");
        when(mapper.selectById(asset.getId())).thenReturn(asset);
        assertEquals(false, service.isOwnedReady(42, asset.getId(), "POST_COVER"));
    }

    @Test
    void referenceQueryRejectsForeignAndNonReadyAssetBeforeRpc() {
        MediaAsset asset = ready("6cf7f30c-46d3-4fb3-b185-253760bb143f", 43, "POST_COVER");
        when(mapper.selectById(asset.getId())).thenReturn(asset);
        assertThrows(ResourceNotFoundException.class, () -> service.references(42, asset.getId()));
        asset.setOwnerId(42L);
        asset.setStatus("PENDING");
        assertThrows(ResourceNotFoundException.class, () -> service.references(42, asset.getId()));
        assertThrows(IllegalArgumentException.class, () -> service.references(0, asset.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.references(42, "invalid"));
        verifyNoInteractions(publicationLookup, storage, registration);
    }

    @Test
    void referenceQueryDoesNotModifyStorageStatusOrQuota() {
        MediaAsset asset = ready("6cf7f30c-46d3-4fb3-b185-253760bb143f", 42, "POST_COVER");
        when(mapper.selectById(asset.getId())).thenReturn(asset);
        var snapshot = new cn.kokonexus.asset.domain.AssetReferenceSnapshot(
            false,
            true,
            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        );
        when(publicationLookup.referenceSnapshot(asset.getId())).thenReturn(snapshot);
        assertEquals(snapshot, service.references(42, asset.getId()));
        assertEquals("READY", asset.getStatus());
        verify(mapper, never()).update(eq(null), any());
        verify(mapper, never()).delete(any());
        verifyNoInteractions(storage, registration);
    }

    @Test
    void libraryIsBoundedAndScopedWithStableCursorOrdering() {
        MediaAsset cursor = ready("6cf7f30c-46d3-4fb3-b185-253760bb143f", 42, "AVATAR");
        MediaAsset first = ready("1cf7f30c-46d3-4fb3-b185-253760bb143f", 42, "AVATAR");
        when(mapper.selectById(cursor.getId())).thenReturn(cursor);
        when(mapper.selectList(any())).thenReturn(List.of(first, cursor));
        var slice = service.listOwned(42, "avatar", cursor.getId(), 1);
        assertEquals(List.of(first), slice.items());
        assertEquals(first.getId(), slice.nextCursor());
        ArgumentCaptor<QueryWrapper<MediaAsset>> capture = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(capture.capture());
        var query = capture.getValue();
        String sql = query.getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("ORDER BY created_at DESC,id DESC LIMIT 2"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("created_at <") && sql.contains("id <"));
        org.junit.jupiter.api.Assertions.assertTrue(query.getParamNameValuePairs().containsValue(42L));
        org.junit.jupiter.api.Assertions.assertTrue(query.getParamNameValuePairs().containsValue("AVATAR"));
        org.junit.jupiter.api.Assertions.assertTrue(query.getParamNameValuePairs().containsValue("READY"));
    }

    @Test
    void libraryRejectsForeignOrWrongPurposeCursorBeforeListing() {
        MediaAsset cursor = ready("6cf7f30c-46d3-4fb3-b185-253760bb143f", 43, "AVATAR");
        when(mapper.selectById(cursor.getId())).thenReturn(cursor);
        assertThrows(ResourceNotFoundException.class, () -> service.listOwned(42, "AVATAR", cursor.getId(), 12));
        cursor.setOwnerId(42L);
        assertThrows(IllegalArgumentException.class, () -> service.listOwned(42, "BANNER", cursor.getId(), 12));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void libraryRejectsUnboundedQueryAndInvalidPurpose() {
        assertThrows(IllegalArgumentException.class, () -> service.listOwned(42, "AVATAR", null, 25));
        assertThrows(IllegalArgumentException.class, () -> service.listOwned(42, "AVATAR", null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.listOwned(42, "SCRIPT", null, 12));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void boundsConcurrentImageProcessingAndReleasesSlotAfterFailure() throws Exception {
        var slowInspector = mock(ImageInspector.class);
        var guarded = new AssetApplicationService(mapper, storage, slowInspector, publicationLookup, registration);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(slowInspector.inspect(any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release processing");
            throw new IllegalArgumentException("Invalid image");
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() ->
                assertThrows(IllegalArgumentException.class, () -> guarded.upload(42, "AVATAR", null))
            );
            try {
                org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(AssetUploadBusyException.class, () -> guarded.upload(43, "AVATAR", null));
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class, () -> guarded.upload(42, "AVATAR", null));
            verify(registration, never()).register(any());
        }
    }

    private MediaAsset ready(String id, long owner, String purpose) {
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setOwnerId(owner);
        asset.setPurpose(purpose);
        asset.setStatus("READY");
        asset.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        return asset;
    }

    private MockMultipartFile png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("file", "avatar.png", "image/png", output.toByteArray());
    }
}
