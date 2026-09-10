package com.tacz.guns.client.resource.pojo.display.attachment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentDisplayRenderModelTest {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer()).create();

    @Test
    void legacyDisplayStillInitializesWithoutMesh() {
        var legacy = display("{}");
        assertDoesNotThrow(legacy::init);
        assertNull(legacy.getRenderModel());
    }

    @Test
    void meshAndOpticalRigKeepIndependentResourceContracts() {
        var configured = display("""
                {"model":"test:scope_semantics", "texture":"test:scope_reticle", "scope":true,
                 "render_model":{"type":"gltf","location":"test:models/gltf/scope.glb","scale":1}}
                """);
        assertDoesNotThrow(configured::init);
        assertEquals(Identifier.fromNamespaceAndPath("test", "models/gltf/scope.glb"),
                configured.getRenderModel().getLocation());
        assertEquals(Identifier.fromNamespaceAndPath("test", "scope_semantics"), configured.getModel());
        assertTrue(configured.isScope());
        assertTrue(configured.getRenderModel().isGltf());
    }

    @Test
    void attachmentMeshRejectsMissingLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> display("{\"render_model\":{\"type\":\"gltf\"}}").init());
    }

    @Test
    void attachmentMeshCannotUseGunNodeBindings() {
        assertThrows(IllegalArgumentException.class, () -> display("""
                {"render_model":{"type":"gltf","location":"test:models/gltf/grip.glb",
                  "node_map":{"bolt":"bolt"}}}
                """).init());
    }

    private static AttachmentDisplay display(String json) {
        return GSON.fromJson(json, AttachmentDisplay.class);
    }
}
