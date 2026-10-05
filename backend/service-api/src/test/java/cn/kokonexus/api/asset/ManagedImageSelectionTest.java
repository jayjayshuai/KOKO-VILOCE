package cn.kokonexus.api.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagedImageSelectionTest {

    private static final String ID = "0fc78935-9839-4721-92f5-7aaf13f00877";
    private static final String BASE = "/api/assets/images";

    @Test
    void bindsVerifiedAssetAndIgnoresSpoofedUrl() {
        AtomicReference<String> verified = new AtomicReference<>();
        ManagedImageSelection selected = ManagedImageSelection.choose(
            ID,
            "https://evil.example/pixel",
            null,
            null,
            BASE,
            verified::set
        );
        assertEquals(ID, selected.assetId());
        assertNull(selected.legacyUrl());
        assertEquals(ID, verified.get());
    }

    @Test
    void preservesOnlyUnchangedLegacyUrl() {
        String legacy = "https://legacy.example/image.jpg";
        assertEquals(legacy, ManagedImageSelection.choose(null, legacy, null, legacy, BASE, ignored -> {}).legacyUrl());
        assertThrows(IllegalArgumentException.class, () ->
            ManagedImageSelection.choose(null, "https://new.example/image.jpg", null, legacy, BASE, ignored -> {})
        );
    }

    @Test
    void oldClientCanPreserveCurrentManagedImageAndClearIt() {
        AtomicReference<String> verified = new AtomicReference<>();
        String url = AssetUrl.publicUrl(BASE, ID);
        assertEquals(ID, ManagedImageSelection.choose(null, url, ID, null, BASE, verified::set).assetId());
        assertEquals(ID, verified.get());
        ManagedImageSelection cleared = ManagedImageSelection.choose(null, "", ID, null, BASE, ignored -> {
            throw new AssertionError("Should not verify a cleared image");
        });
        assertNull(cleared.assetId());
        assertNull(cleared.legacyUrl());
    }

    @Test
    void rejectsNoncanonicalId() {
        assertThrows(IllegalArgumentException.class, () ->
            ManagedImageSelection.choose("0FC78935-9839-4721-92F5-7AAF13F00877", "", null, null, BASE, ignored -> {})
        );
    }
}
