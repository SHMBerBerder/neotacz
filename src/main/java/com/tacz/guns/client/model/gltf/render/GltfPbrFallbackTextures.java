package com.tacz.guns.client.model.gltf.render;

import com.tacz.guns.GunMod;
import net.minecraft.resources.Identifier;

public final class GltfPbrFallbackTextures {
    public static final Identifier BASE_COLOR = id("textures/pbr/fallback/base_color.png");
    public static final Identifier METALLIC_ROUGHNESS = id("textures/pbr/fallback/metallic_roughness.png");
    public static final Identifier NORMAL = id("textures/pbr/fallback/normal.png");
    public static final Identifier OCCLUSION = id("textures/pbr/fallback/occlusion.png");
    public static final Identifier EMISSIVE = id("textures/pbr/fallback/emissive.png");
    public static final Identifier BASE_COLOR_FACTOR = id("textures/pbr/fallback/base_color_factor.png");
    public static final Identifier EMISSIVE_FACTOR = id("textures/pbr/fallback/emissive_factor.png");

    /**
     * One-pixel RGBA: metallicFactor, roughnessFactor, normalScale / 4, occlusionStrength.
     */
    public static final Identifier PBR_PARAMETERS = id("textures/pbr/fallback/pbr_parameters.png");

    private GltfPbrFallbackTextures() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(GunMod.MOD_ID, path);
    }
}
