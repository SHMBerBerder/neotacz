package com.tacz.guns.client.model.gltf.quality;

import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import com.tacz.guns.client.model.gltf.convert.GltfPbrMaterialData;
import com.tacz.guns.client.model.gltf.convert.GltfTextureBinding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Builds selected, role-correct image data once per quality generation; source assets stay intact. */
public final class GltfTextureVariants {
    private static final int MAX_DIAGNOSTIC_DIMENSIONS = 32;
    private static volatile Snapshot lastSnapshot = new Snapshot(0, 0, 0, 0, List.of());

    private GltfTextureVariants() { }

    public static ConvertedGltfAsset apply(ConvertedGltfAsset asset, GltfRenderQuality quality, Path cacheDirectory) {
        return apply(asset, quality, cacheDirectory, () -> false);
    }

    public static ConvertedGltfAsset apply(ConvertedGltfAsset asset, GltfRenderQuality quality, Path cacheDirectory,
                                           BooleanSupplier cancelled) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(cancelled, "cancelled");
        TextureImageFilter.checkCancelled(cancelled);
        Set<Integer> selectedMaterials = asset.selectedMaterialIndices();
        for (int index : selectedMaterials) {
            TextureImageFilter.checkCancelled(cancelled);
            GltfPbrMaterialData material = asset.materials().get(index);
            // Reject the whole selected asset before deriving any earlier material's textures.
            validateSampler(material.baseColorTexture());
            validateSampler(material.metallicRoughnessTexture());
            validateSampler(material.normalTexture());
            validateSampler(material.occlusionTexture());
            validateSampler(material.emissiveTexture());
        }
        Derivation derivation = new Derivation(asset, quality.textureMaxSize(), new TextureVariantCache(cacheDirectory), cancelled);
        List<GltfPbrMaterialData> materials = new ArrayList<>(asset.materials().size());
        for (int index = 0; index < asset.materials().size(); index++) {
            TextureImageFilter.checkCancelled(cancelled);
            // Keep material indices stable for preserved author-LOD metadata, but do not retain
            // bindings to any unselected images. The next quality generation starts from source.
            materials.add(selectedMaterials.contains(index)
                    ? derivation.material(asset.materials().get(index)) : GltfPbrMaterialData.defaultMaterial());
        }
        ConvertedGltfAsset result = asset.withRenderData(asset.renderMeshes(), materials, derivation.images);
        TextureImageFilter.checkCancelled(cancelled);
        lastSnapshot = new Snapshot(derivation.hits, derivation.misses, derivation.derived, derivation.original,
                derivation.dimensions);
        return result;
    }

    /** Last completed derivation only, not a global residency counter; no pixels/assets are retained. */
    public static Snapshot snapshot() { return lastSnapshot; }

    private static void validateSampler(GltfTextureBinding binding) {
        if (binding != null) TextureVariantPolicy.validateSampler(binding.sampler());
    }

    public record Dimensions(int width, int height) { }
    public record Snapshot(long cacheHits, long cacheMisses, long derivedImages, long retainedOriginalImages,
                           List<Dimensions> dimensions) {
        public Snapshot { dimensions = List.copyOf(dimensions); }
    }

    private static final class Derivation {
        private final ConvertedGltfAsset asset;
        private final int maxSize;
        private final TextureVariantCache cache;
        private final BooleanSupplier cancelled;
        private final Map<Integer, Source> sources = new HashMap<>();
        private final Map<String, Integer> imageIndices = new HashMap<>();
        private final List<GltfImageData> images = new ArrayList<>();
        private final List<Dimensions> dimensions = new ArrayList<>();
        private long hits, misses, derived, original;

        private Derivation(ConvertedGltfAsset asset, int maxSize, TextureVariantCache cache, BooleanSupplier cancelled) {
            this.asset = asset;
            this.maxSize = maxSize;
            this.cache = cache;
            this.cancelled = cancelled;
        }

        private GltfPbrMaterialData material(GltfPbrMaterialData material) {
            return new GltfPbrMaterialData(material.name(), material.baseColorFactor(), material.metallicFactor(),
                    material.roughnessFactor(), material.emissiveFactor(), material.normalScale(),
                    material.occlusionStrength(), material.alphaMode(), material.alphaCutoff(), material.doubleSided(),
                    binding(material.baseColorTexture(), TextureImageFilter.Role.BASE_COLOR, material),
                    binding(material.metallicRoughnessTexture(), TextureImageFilter.Role.ORM, material),
                    binding(material.normalTexture(), TextureImageFilter.Role.NORMAL, material),
                    binding(material.occlusionTexture(), TextureImageFilter.Role.OCCLUSION, material),
                    binding(material.emissiveTexture(), TextureImageFilter.Role.EMISSIVE, material));
        }

        private GltfTextureBinding binding(GltfTextureBinding binding, TextureImageFilter.Role role,
                                           GltfPbrMaterialData material) {
            TextureImageFilter.checkCancelled(cancelled);
            if (binding == null) return null;
            if (binding.imageIndex() >= asset.images().size()) throw new IllegalArgumentException("image index out of range");
            Source source = sources.computeIfAbsent(binding.imageIndex(), this::source);
            if (source.ktx() != null) source.ktx().validateUsage(role);
            TextureImageFilter.Dimensions target = TextureImageFilter.target(source.dimensions(), maxSize);
            // Keep original-size Basis compressed. A lossless PNG can exceed the source byte
            // budget even when the original KTX2 is admitted; the GPU cache decodes it on demand.
            boolean unchanged = target.equals(source.dimensions());
            boolean mask = role == TextureImageFilter.Role.BASE_COLOR && material.alphaMode() == GltfAlphaMode.MASK;
            TextureImageFilter.Spec spec = new TextureImageFilter.Spec(role, maxSize,
                    role == TextureImageFilter.Role.BASE_COLOR ? material.alphaMode() : GltfAlphaMode.OPAQUE,
                    mask ? material.alphaCutoff() : 0.5f,
                    mask ? material.baseColorFactor()[3] : 1.0f, binding.sampler());
            String key = unchanged ? "original:" + source.hash() : TextureVariantCache.key(source.hash(), spec);
            Integer imageIndex = imageIndices.get(key);
            if (imageIndex == null) {
                GltfImageData image;
                if (unchanged) {
                    image = source.image();
                    original++;
                } else {
                    byte[] encoded = cache.read(key, target.width(), target.height());
                    TextureImageFilter.checkCancelled(cancelled);
                    if (encoded != null) {
                        hits++;
                    } else {
                        misses++;
                        TextureImageFilter.Result filtered = TextureImageFilter.resize(source.image().encodedBytes(), spec, cancelled);
                        TextureImageFilter.checkCancelled(cancelled);
                        cache.write(key, filtered);
                        encoded = filtered.encoded();
                        derived++;
                    }
                    image = new GltfImageData(source.image().name(), "derived/" + key + ".png", "image/png", encoded);
                }
                imageIndex = images.size();
                TextureImageFilter.checkCancelled(cancelled);
                images.add(image);
                imageIndices.put(key, imageIndex);
                if (dimensions.size() < MAX_DIAGNOSTIC_DIMENSIONS) dimensions.add(new Dimensions(target.width(), target.height()));
            }
            return new GltfTextureBinding(imageIndex, binding.texCoord(), binding.sampler());
        }

        private Source source(int imageIndex) {
            GltfImageData image = asset.images().get(imageIndex);
            byte[] encoded = image.encodedBytes();
            // Header/hash inspection is not decoding. Warm variants avoid the original pixel
            // allocation, although the importer still reads encoded glTF image resources.
            Ktx2ImageHeader ktx = Ktx2ImageHeader.matches(encoded) ? Ktx2ImageHeader.read(encoded) : null;
            var dimensions = ktx == null ? TextureImageFilter.inspect(encoded)
                    : new TextureImageFilter.Dimensions(ktx.width(), ktx.height());
            return new Source(image, dimensions, TextureVariantCache.contentHash(encoded), ktx);
        }
    }

    private record Source(GltfImageData image, TextureImageFilter.Dimensions dimensions, String hash, Ktx2ImageHeader ktx) { }
}
