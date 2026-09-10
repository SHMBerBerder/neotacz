package com.tacz.guns.client.resource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.gltf.render.GunBodyRenderer;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.tacz.guns.client.resource.GunDisplayInstance.LOAD_MODEL;
import static com.tacz.guns.client.resource.QualityReloadTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class GunMeshRoutingTest {
    private static final Identifier LOD_TEXTURE = id("textures/item/m95_lod");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer())
            .create();

    @BeforeEach
    void config() {
        loadConfig();
    }

    @Test
    void meshRenderModelIsAuthoritativeOverLegacyLod() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(id("mesh_lod"), meshDisplay());
        try {
            Pair<BedrockGunModel, Identifier> staleLod = installedLod(instance);

            assertTrue(instance.usesMeshRenderModel());
            assertNull(instance.getLodModel(), "A declared glTF body must not borrow the legacy Bedrock LOD body");
            assertSame(staleLod, field(instance, "lodModel"), "The old field may exist but must stay unreachable");
            assertFalse((boolean) field(instance, "lodLoaded"));
            assertNull(field(instance, "lodWarmUpTask"));
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void bedrockDisplaysKeepLegacyLodWhenLoaded() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(id("bedrock_lod"), bedrockDisplay());
        try {
            Pair<BedrockGunModel, Identifier> lod = installedLod(instance);
            set(instance, "lodLoaded", true);

            assertFalse(instance.usesMeshRenderModel());
            assertSame(lod, instance.getLodModel());
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void declaredMeshWithoutAvailableRendererDoesNotExposeBedrockFallbacks() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(id("mesh_no_renderer"), meshDisplay());
        try {
            installedLod(instance);
            set(instance, "lodLoaded", true);

            assertNull(instance.getGunModel(), "The Bedrock rig is not a visible fallback for declared mesh bodies");
            assertNull(instance.getLodModel(), "The legacy LOD body must also stay suppressed");
            GunDisplayInstance.GuiModelSnapshot snapshot = instance.guiModelSnapshot();
            assertTrue(snapshot.meshRequested());
            assertNull(snapshot.model());
            assertEquals(MissingTextureAtlasSprite.getLocation(), snapshot.slotTexture());
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void declaredMeshLoadFailureRemainsObservableAndDoesNotBorrowBedrock() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(id("mesh_failed"), meshDisplay());
        Exception failure = new IllegalArgumentException("controlled glTF renderer failure");
        try {
            installedLod(instance);
            set(instance, "lodLoaded", true);
            set(instance, "gltfBodyLoadFailure", failure);

            assertNull(instance.getGunModel());
            assertNull(instance.getLodModel());
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> all(instance.warmUpForReload(LOAD_MODEL)).get(5, TimeUnit.SECONDS));
            assertTrue(hasCause(thrown, failure), "Reload observers must see the renderer failure");
        } finally {
            instance.invalidate();
        }
    }

    @Test
    void guiSnapshotForPendingMeshRequestsOnlyMainModel() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(id("mesh_gui_pending"), meshDisplay());
        var release = blockDispatcher();
        try {
            GunDisplayInstance.GuiModelSnapshot first = instance.guiModelSnapshot();
            GunDisplayInstance.GuiModelSnapshot second = instance.guiModelSnapshot();

            assertTrue(first.meshRequested());
            assertNull(first.model());
            assertEquals(MissingTextureAtlasSprite.getLocation(), first.slotTexture());
            assertSame(first.pendingIdentity(), second.pendingIdentity());
            assertEquals(LOAD_MODEL, instance.requestedLoads());
            assertNotNull(field(instance, "modelWarmUpTask"));
            assertNull(field(instance, "lodWarmUpTask"));
            assertNull(field(instance, "animationWarmUpTask"));
        } finally {
            instance.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void pendingGuiIdentityIsStablePerDisplayAndChangesForNewDisplayInstance() throws Exception {
        GunDisplayInstance firstDisplay = new GunDisplayInstance(id("mesh_gui_first"), meshDisplay());
        GunDisplayInstance secondDisplay = new GunDisplayInstance(id("mesh_gui_second"), meshDisplay());
        var release = blockDispatcher();
        try {
            GunDisplayInstance.GuiModelSnapshot firstA = firstDisplay.guiModelSnapshot();
            GunDisplayInstance.GuiModelSnapshot firstB = firstDisplay.guiModelSnapshot();
            GunDisplayInstance.GuiModelSnapshot second = secondDisplay.guiModelSnapshot();

            assertSame(firstA.pendingIdentity(), firstB.pendingIdentity());
            assertNotSame(firstA.pendingIdentity(), second.pendingIdentity());
        } finally {
            firstDisplay.invalidate();
            secondDisplay.invalidate();
            release.countDown();
            drainDispatcher();
        }
    }

    @Test
    void readyMeshSnapshotCanExposeTheMeshRigWithoutRequestingLodOrRuntime() throws Exception {
        GunDisplayInstance instance = new GunDisplayInstance(id("mesh_gui_ready"), meshDisplay());
        try {
            BedrockGunModel model = readyMeshModel(instance);

            GunDisplayInstance.GuiModelSnapshot snapshot = instance.guiModelSnapshot();

            assertTrue(snapshot.meshRequested());
            assertSame(model, snapshot.model());
            assertEquals(LOAD_MODEL, instance.requestedLoads());
            assertNull(field(instance, "lodWarmUpTask"));
            assertNull(field(instance, "animationWarmUpTask"));
        } finally {
            instance.invalidate();
        }
    }

    private static Pair<BedrockGunModel, Identifier> installedLod(GunDisplayInstance instance) throws Exception {
        readyModel(instance);
        BedrockGunModel model = (BedrockGunModel) field(instance, "gunModel");
        Pair<BedrockGunModel, Identifier> lod = Pair.of(model, LOD_TEXTURE);
        set(instance, "lodModel", lod);
        return lod;
    }

    private static BedrockGunModel readyMeshModel(GunDisplayInstance instance) throws Exception {
        readyModel(instance);
        BedrockGunModel model = (BedrockGunModel) field(instance, "gunModel");
        model.setBodyRenderer((GunBodyRenderer) (bedrockRig, poseStack, gunItem, transformType, collector, light, overlay) -> true);
        return model;
    }

    private static boolean hasCause(Throwable root, Throwable expected) {
        for (Throwable cause = root; cause != null; cause = cause.getCause()) {
            if (cause == expected) return true;
        }
        return false;
    }

    private static GunDisplay meshDisplay() {
        return GSON.fromJson("""
                {
                  "model": "test:models/bedrock/m95.json",
                  "texture": "test:textures/item/m95.png",
                  "slot": "test:textures/gui/m95_slot.png",
                  "render_model": {
                    "type": "gltf",
                    "location": "test:models/gltf/bolt_action.glb"
                  },
                  "lod": {
                    "model": "test:models/bedrock/lod/m95.json",
                    "texture": "test:textures/item/m95_lod.png"
                  }
                }
                """, GunDisplay.class);
    }

    private static GunDisplay bedrockDisplay() {
        return GSON.fromJson("""
                {
                  "model": "test:models/bedrock/m95.json",
                  "texture": "test:textures/item/m95.png",
                  "slot": "test:textures/gui/m95_slot.png",
                  "lod": {
                    "model": "test:models/bedrock/lod/m95.json",
                    "texture": "test:textures/item/m95_lod.png"
                  }
                }
                """, GunDisplay.class);
    }
}
