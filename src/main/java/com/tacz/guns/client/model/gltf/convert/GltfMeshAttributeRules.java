package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.ElementType;
import de.javagl.jgltf.model.GltfConstants;

import java.util.Set;

/** The supported subset of Khronos' base/morph attribute type matrices. */
final class GltfMeshAttributeRules {
    static final String QUANTIZATION = "KHR_mesh_quantization";
    private static final Set<Integer> FLOAT = Set.of(GltfConstants.GL_FLOAT);
    private static final Set<Integer> SIGNED = Set.of(GltfConstants.GL_FLOAT, GltfConstants.GL_BYTE, GltfConstants.GL_SHORT);
    private static final Set<Integer> INTEGER = Set.of(GltfConstants.GL_FLOAT, GltfConstants.GL_BYTE,
            GltfConstants.GL_UNSIGNED_BYTE, GltfConstants.GL_SHORT, GltfConstants.GL_UNSIGNED_SHORT);
    private static final Set<Integer> CORE_UV = Set.of(GltfConstants.GL_FLOAT,
            GltfConstants.GL_UNSIGNED_BYTE, GltfConstants.GL_UNSIGNED_SHORT);
    private final boolean quantized;

    private GltfMeshAttributeRules(boolean quantized) {
        this.quantized = quantized;
    }

    static GltfMeshAttributeRules read(NormalizedGltfModel source) throws GltfConversionException {
        boolean used = source.extensionsUsed().contains(QUANTIZATION);
        boolean required = source.extensionsRequired().contains(QUANTIZATION);
        // The extension has no float fallback, so Khronos requires extensionsRequired, not just used.
        if (used != required) throw new GltfConversionException(QUANTIZATION
                + " must be declared in both extensionsUsed and extensionsRequired");
        return new GltfMeshAttributeRules(required);
    }

    float[] read(AccessorModel accessor, String semantic, boolean morph, int count, String role)
            throws GltfConversionException {
        if (accessor == null) return new float[0];
        GltfAccessorReader.requireCount(accessor, count, role);
        boolean uv = semantic.equals("TEXCOORD_0");
        boolean normal = semantic.equals("NORMAL");
        Set<Integer> components = quantized ? normal || morph ? SIGNED : INTEGER : uv ? CORE_UV : FLOAT;
        if (quantized && accessor.getComponentType() != GltfConstants.GL_FLOAT) validateAlignment(accessor, role);
        // Integer positions/UVs keep their authored numeric range. Node/skin/texture transforms,
        // not min/max-derived guesses, own dequantization. Normals must use signed normalized data.
        return GltfAccessorReader.readFloats(accessor, uv ? ElementType.VEC2 : ElementType.VEC3,
                components, quantized ? normal : uv, role);
    }

    private static void validateAlignment(AccessorModel accessor, String role) throws GltfConversionException {
        var view = accessor.getBufferViewModel();
        if (view == null) return; // The shared accessor reader reports missing resolved data.
        int bytes = accessor.getComponentSizeInBytes();
        Integer explicitStride = view.getByteStride();
        int stride = explicitStride == null ? accessor.getElementType().getNumComponents() * bytes : explicitStride;
        if (view.getByteOffset() % bytes != 0) {
            throw new GltfConversionException(role + " buffer view offset must be aligned to its component byte size");
        }
        if ((accessor.getByteOffset() & 3) != 0 || (stride & 3) != 0) {
            throw new GltfConversionException(role + " quantized vertex elements must be aligned to 4-byte boundaries inside the buffer view");
        }
    }
}
