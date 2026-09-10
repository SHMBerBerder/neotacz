package com.tacz.guns.client.resource;

import com.google.gson.Gson;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GltfRenderModelLoaderContractTest {
    @Test
    void publicMeshEntryRejectsInvalidConfigBeforeAccessingMinecraft() {
        Gson gson = new Gson();
        for (String json : new String[]{"{}", "{\"type\":\"gltf\"}", "{\"type\":\"other\"}"}) {
            var config = gson.fromJson(json, GunRenderModelConfig.class);
            assertThrows(IllegalArgumentException.class, () -> GltfRenderModelLoader.load(config, () -> false));
        }
    }
}
