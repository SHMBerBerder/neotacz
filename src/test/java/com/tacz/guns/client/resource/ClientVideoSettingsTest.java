package com.tacz.guns.client.resource;

import com.tacz.guns.config.client.VideoConfig;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientVideoSettingsTest {
    private final VideoConfig.Settings balanced = VideoConfig.DEFAULT;
    private final VideoConfig.Settings high = VideoConfig.presetSettings(VideoConfig.Preset.HIGH);

    @Test
    void stalePublicationCannotChangeActiveQualityOrInvalidateThePreviousTable() throws Exception {
        QualityReloadTestSupport.loadConfig();
        var original = ClientIndexManager.GUN_DISPLAY;
        var oldQuality = ClientVideoSettings.quality();
        var qualityField = ClientVideoSettings.class.getDeclaredField("activeQuality");
        qualityField.setAccessible(true);
        var old = QualityReloadTestSupport.display("stale");
        try {
            ClientIndexManager.GUN_DISPLAY = new HashMap<>(Map.of(QualityReloadTestSupport.id("stale"), old));
            var staged = ClientIndexManager.stageQualityReload();
            var newerTable = new HashMap<>(ClientIndexManager.GUN_DISPLAY);
            ClientIndexManager.GUN_DISPLAY = newerTable;
            assertThrows(IllegalStateException.class, () -> ClientVideoSettings.publish(high.quality(), staged,
                    new GltfModelManager(), true, () -> fail("Stale publication must not reset the current hand")));
            assertEquals(oldQuality, ClientVideoSettings.quality());
            assertSame(newerTable, ClientIndexManager.GUN_DISPLAY);
            assertFalse((boolean) QualityReloadTestSupport.field(old, "invalidated"));
        } finally {
            ClientIndexManager.GUN_DISPLAY = original;
            qualityField.set(null, oldQuality);
        }
    }

    @Test
    void disposalFailureStillResetsFirstPersonAfterPublishingTheEntireNewTable() throws Exception {
        QualityReloadTestSupport.loadConfig();
        var original = ClientIndexManager.GUN_DISPLAY;
        var oldQuality = ClientVideoSettings.quality();
        var qualityField = ClientVideoSettings.class.getDeclaredField("activeQuality");
        qualityField.setAccessible(true);
        var old = QualityReloadTestSupport.display("cleanup");
        var id = QualityReloadTestSupport.id("cleanup");
        try {
            var before = new HashMap<>(Map.of(id, old));
            ClientIndexManager.GUN_DISPLAY = before;
            var staged = ClientIndexManager.stageQualityReload();
            AtomicInteger resets = new AtomicInteger();
            GltfModelManager manager = new GltfModelManager();
            manager.addDisposalHook(() -> {
                assertEquals(1, resets.get());
                throw new IllegalStateException("controlled GPU cleanup failure");
            });
            assertThrows(IllegalStateException.class, () -> ClientVideoSettings.publish(high.quality(), staged,
                    manager, true, () -> {
                        assertNotSame(before, ClientIndexManager.GUN_DISPLAY);
                        assertEquals(high.quality(), ClientVideoSettings.quality());
                        resets.incrementAndGet();
                    }));
            assertEquals(1, resets.get());
            assertEquals(before.keySet(), ClientIndexManager.GUN_DISPLAY.keySet());
            assertNotSame(old, ClientIndexManager.GUN_DISPLAY.get(id));
            assertTrue((boolean) QualityReloadTestSupport.field(old, "invalidated"));
        } finally {
            ClientIndexManager.GUN_DISPLAY = original;
            qualityField.set(null, oldQuality);
        }
    }

    @Test
    void unchangedAndChangedBackDoNotSaveOrReloadEvenAfterExternalChange() {
        assertEquals(ClientVideoSettings.Change.UNCHANGED,
                ClientVideoSettings.change(balanced, balanced, balanced.quality()));
        VideoConfig.Settings draft = balanced.selectPreset(VideoConfig.Preset.HIGH)
                .selectPreset(VideoConfig.Preset.BALANCED);
        assertEquals(ClientVideoSettings.Change.UNCHANGED,
                ClientVideoSettings.change(balanced, draft, high.quality()));
    }

    @Test
    void equalQualityMetadataOnlySaves() {
        var custom = balanced.selectPreset(VideoConfig.Preset.CUSTOM);
        assertEquals(ClientVideoSettings.Change.SAVE_ONLY,
                ClientVideoSettings.change(balanced, custom, balanced.quality()));
    }

    @Test
    void selectedBackPresetIsUnchangedButSameQualityCustomIsSaveOnly() {
        var editedBack = balanced.selectPreset(VideoConfig.Preset.CUSTOM)
                .customize(4096, 0, 75)
                .selectPreset(VideoConfig.Preset.BALANCED);
        assertEquals(ClientVideoSettings.Change.UNCHANGED,
                ClientVideoSettings.change(balanced, editedBack, high.quality()));

        var sameQualityCustom = balanced.selectPreset(VideoConfig.Preset.CUSTOM);
        assertEquals(ClientVideoSettings.Change.SAVE_ONLY,
                ClientVideoSettings.change(balanced, sameQualityCustom, balanced.quality()));
    }

    @Test
    void changedQualityRequiresReloadButAlreadyExternallyAppliedQualityDoesNot() {
        assertEquals(ClientVideoSettings.Change.RELOAD,
                ClientVideoSettings.change(balanced, high, balanced.quality()));
        assertEquals(ClientVideoSettings.Change.SAVE_ONLY,
                ClientVideoSettings.change(balanced, high, high.quality()));
    }
}
