package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.quality.TextureVariantPolicy;
import de.javagl.jgltf.model.GltfConstants;

import java.util.Objects;

/** Immutable sampler keys are part of a material/RenderType, not mutable state on a shared image. */
public record GltfPbrSamplers(GltfSamplerData baseColor, GltfSamplerData metallicRoughness,
                             GltfSamplerData normal, GltfSamplerData occlusion, GltfSamplerData emissive) {
    public static final GltfPbrSamplers DEFAULT = new GltfPbrSamplers(null, null, null, null, null);

    public GltfPbrSamplers {
        baseColor = admitted(baseColor);
        metallicRoughness = admitted(metallicRoughness);
        normal = admitted(normal);
        occlusion = admitted(occlusion);
        emissive = admitted(emissive);
    }

    static GltfPbrSamplers from(GltfPbrTextureSlots slots) {
        return new GltfPbrSamplers(sampler(slots.baseColor()), sampler(slots.metallicRoughness()),
                sampler(slots.normal()), sampler(slots.occlusion()), sampler(slots.emissive()));
    }

    static GpuSampler resolve(GltfSamplerData data) {
        TextureVariantPolicy.validateSampler(data);
        return RenderSystem.getSamplerCache().getSampler(address(data.wrapS()), address(data.wrapT()),
                minFilter(data.minFilter()), data.magFilter() == GltfConstants.GL_NEAREST
                        ? FilterMode.NEAREST : FilterMode.LINEAR, TextureVariantPolicy.mipmapped(data));
    }

    private static GltfSamplerData admitted(GltfSamplerData data) {
        data = Objects.requireNonNullElse(data, GltfSamplerData.DEFAULT);
        TextureVariantPolicy.validateSampler(data);
        return data;
    }

    private static GltfSamplerData sampler(GltfPbrTextureSource source) {
        return source == null ? GltfSamplerData.DEFAULT : source.settings().sampler();
    }

    private static AddressMode address(int wrap) {
        return wrap == GltfConstants.GL_REPEAT ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
    }

    private static FilterMode minFilter(int min) {
        return min == GltfConstants.GL_NEAREST || min == GltfConstants.GL_NEAREST_MIPMAP_LINEAR
                ? FilterMode.NEAREST : FilterMode.LINEAR;
    }
}
