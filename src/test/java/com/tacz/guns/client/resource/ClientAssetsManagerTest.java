package com.tacz.guns.client.resource;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAssetsManagerTest {
    @Test
    void registersGltfModelsBeforeIndexReload() {
        List<Identifier> registrations = new ArrayList<>();

        ClientAssetsManager.INSTANCE.reloadAndRegister((id, listener) -> registrations.add(id));

        Identifier gltfModels = Identifier.fromNamespaceAndPath("tacz", "client/gltf_model");
        Identifier indexReload = Identifier.fromNamespaceAndPath("tacz", "client/index_reload");
        assertTrue(registrations.indexOf(gltfModels) >= 0);
        assertTrue(registrations.indexOf(gltfModels) < registrations.indexOf(indexReload));
        assertNotNull(ClientAssetsManager.INSTANCE.getGltfModelManager());
    }
}
