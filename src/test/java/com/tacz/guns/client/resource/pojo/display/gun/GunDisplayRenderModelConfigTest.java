package com.tacz.guns.client.resource.pojo.display.gun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GunDisplayRenderModelConfigTest {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer())
            .create();

    @Test
    void omittedRenderModelKeepsBedrockDefaultPath() {
        GunDisplay display = GSON.fromJson("{}", GunDisplay.class);

        assertNull(display.getRenderModel());
    }

    @Test
    void parsesOptInRenderModel() {
        GunDisplay display = GSON.fromJson("""
                {
                  "render_model": {
                    "type": "gltf",
                    "location": "tacz:models/gltf/rifle.gltf",
                    "scale": 0.0625,
                    "node_map": {
                      "bolt": "BoltVisual",
                      "magazine": "MagazineVisual"
                    },
                    "animation": "idle",
                    "loop_animation": false,
                    "animation_speed": 1.5
                  }
                }
                """, GunDisplay.class);

        GunRenderModelConfig config = display.getRenderModel();
        assertEquals("gltf", config.getType());
        assertEquals(Identifier.fromNamespaceAndPath("tacz", "models/gltf/rifle.gltf"), config.getLocation());
        assertEquals(0.0625F, config.getScale());
        assertEquals(Map.of("bolt", "BoltVisual", "magazine", "MagazineVisual"), config.getNodeMap());
        assertEquals("idle", config.getAnimation());
        assertFalse(config.isLoopAnimation());
        assertEquals(1.5F, config.getAnimationSpeed());
        assertTrue(config.isGltf());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void emptyRenderModelUsesNonOptInDefaults() {
        GunDisplay display = GSON.fromJson("{\"render_model\":{}}", GunDisplay.class);

        GunRenderModelConfig config = display.getRenderModel();
        assertEquals("bedrock", config.getType());
        assertNull(config.getLocation());
        assertEquals(1.0F, config.getScale());
        assertEquals(Map.of(), config.getNodeMap());
        assertNull(config.getAnimation());
        assertTrue(config.isLoopAnimation());
        assertEquals(1.0F, config.getAnimationSpeed());
        assertFalse(config.isGltf());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void rejectsInvalidOptInValues() {
        assertInvalid("{\"type\":\"unknown\"}");
        assertInvalid("{\"type\":\"gltf\"}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/rifle.json\"}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"scale\":0}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"scale\":1025}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"scale\":1e1000}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"animation\":\" \"}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"animation_speed\":0}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"animation_speed\":1e1000}");
    }

    @Test
    void validatesNarrowGltfNodeMapProtocol() {
        assertInvalid("{\"type\":\"bedrock\",\"node_map\":{\"bolt\":\"BoltVisual\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\"\":\"BoltVisual\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\" \":\"BoltVisual\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\" bolt\":\"BoltVisual\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\"bolt\":\"\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\"bolt\":\" BoltVisual\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\"bolt\":null}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\"root\":\"RootVisual\"}}");
        assertInvalid("{\"type\":\"gltf\",\"location\":\"tacz:models/gltf/rifle.gltf\",\"node_map\":{\"bolt\":\"SharedVisual\",\"magazine\":\"SharedVisual\"}}");
        assertInvalid("""
                {
                  "type": "gltf",
                  "location": "tacz:models/gltf/rifle.gltf",
                  "node_map": {
                    "a": "A",
                    "b": "B",
                    "c": "C",
                    "d": "D",
                    "e": "E",
                    "f": "F",
                    "g": "G",
                    "h": "H",
                    "i": "I"
                  }
                }
                """);
    }

    private static void assertInvalid(String renderModelJson) {
        GunRenderModelConfig config = GSON.fromJson(renderModelJson, GunRenderModelConfig.class);
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
