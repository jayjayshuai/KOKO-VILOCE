package cn.kokonexus.asset.infrastructure.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.kokonexus.asset.domain.AssetReferenceSnapshot;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class AssetReferencesHealthIndicatorTest {

    @Test
    void readinessRequiresFullReferenceQueriesAndDoesNotExposeFailureDetails() {
        var lookup = mock(AssetPublicationLookup.class);
        var indicator = new AssetReferencesHealthIndicator(lookup);
        when(lookup.referenceSnapshot(anyString())).thenThrow(new IllegalStateException("private database address"));
        var unavailable = indicator.health();
        assertEquals(Status.DOWN, unavailable.getStatus());
        assertEquals(java.util.Map.of("reason", "reference-query-unavailable"), unavailable.getDetails());
        doReturn(new AssetReferenceSnapshot(false, false, OffsetDateTime.parse("2026-10-04T07:00:00Z")))
            .when(lookup)
            .referenceSnapshot(anyString());
        assertEquals(Status.UP, indicator.health().getStatus());
        verify(lookup, never()).isPublished(anyString());
    }

    @Test
    void missingSnapshotCannotPassReadiness() {
        var lookup = mock(AssetPublicationLookup.class);
        assertEquals(Status.DOWN, new AssetReferencesHealthIndicator(lookup).health().getStatus());
    }
}
