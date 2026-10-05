package cn.kokonexus.asset.infrastructure.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.api.asset.CommunityAssetRpcService;
import cn.kokonexus.api.identity.IdentityRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AssetPublicationLookupTest {

    private final IdentityRpcService identity = mock(IdentityRpcService.class);
    private final CommunityAssetRpcService community = mock(CommunityAssetRpcService.class);
    private final AssetPublicationLookup lookup = new AssetPublicationLookup();

    AssetPublicationLookupTest() {
        ReflectionTestUtils.setField(lookup, "identityRpcService", identity);
        ReflectionTestUtils.setField(lookup, "communityAssetRpcService", community);
    }

    @Test
    void unpublishedReferenceIsPrivate() {
        assertFalse(lookup.isPublished("asset-id"));
    }

    @Test
    void eitherPublishedDomainMayAuthorizeRead() {
        when(community.isPublishedCover("asset-id")).thenReturn(true);
        assertTrue(lookup.isPublished("asset-id"));
    }

    @Test
    void failsClosedWhenReferenceTruthIsUnavailable() {
        when(identity.isPublishedProfileAsset("asset-id")).thenThrow(new IllegalStateException("offline"));
        assertThrows(AssetReferenceUnavailableException.class, () -> lookup.isPublished("asset-id"));
    }

    @Test
    void referenceSnapshotQueriesBothDomainsEvenWhenProfileIsReferenced() {
        when(identity.hasProfileAssetReference("asset-id")).thenReturn(true);
        var snapshot = lookup.referenceSnapshot("asset-id");
        assertTrue(snapshot.profileReferenced());
        assertFalse(snapshot.postReferenced());
        assertEquals(java.time.ZoneOffset.UTC, snapshot.checkedAt().getOffset());
        verify(community).hasCoverReference("asset-id");
        verify(identity, never()).isPublishedProfileAsset("asset-id");
        verify(community, never()).isPublishedCover("asset-id");
    }

    @Test
    void referenceSnapshotDoesNotAuthorizePublicRead() {
        when(community.hasCoverReference("asset-id")).thenReturn(true);
        assertTrue(lookup.referenceSnapshot("asset-id").postReferenced());
        assertFalse(lookup.isPublished("asset-id"));
    }

    @Test
    void emptySnapshotRequiresBothSuccessfulDomainQueries() {
        var snapshot = lookup.referenceSnapshot("asset-id");
        assertFalse(snapshot.profileReferenced());
        assertFalse(snapshot.postReferenced());
        verify(identity).hasProfileAssetReference("asset-id");
        verify(community).hasCoverReference("asset-id");
    }

    @Test
    void oneSuccessfulReferenceCannotMaskAnotherUnavailableDomain() {
        when(identity.hasProfileAssetReference("asset-id")).thenReturn(true);
        when(community.hasCoverReference("asset-id")).thenThrow(new IllegalStateException("offline"));
        assertThrows(AssetReferenceUnavailableException.class, () -> lookup.referenceSnapshot("asset-id"));
    }

    @Test
    void twoFailuresKeepDiagnosticCausesWithoutSelfSuppression() {
        var first = new IllegalStateException("first private address");
        var second = new IllegalStateException("second private address");
        when(identity.hasProfileAssetReference("asset-id")).thenThrow(first);
        when(community.hasCoverReference("asset-id")).thenThrow(second);
        var failure = assertThrows(AssetReferenceUnavailableException.class, () ->
            lookup.referenceSnapshot("asset-id")
        );
        assertEquals(first, failure.getCause());
        assertEquals(second, first.getSuppressed()[0]);
        org.mockito.Mockito.doThrow(first).when(community).hasCoverReference("asset-id");
        assertThrows(AssetReferenceUnavailableException.class, () -> lookup.referenceSnapshot("asset-id"));
    }
}
