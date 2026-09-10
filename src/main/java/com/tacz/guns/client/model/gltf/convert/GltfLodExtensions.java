package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import de.javagl.jgltf.impl.v2.GlTFProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Strict LOD parser; supported attribute declarations are validated by their own rules. */
final class GltfLodExtensions {
    private static final String NAME = "MSFT_lod";

    private GltfLodExtensions() {
    }

    static GltfLodMetadata read(NormalizedGltfModel source) throws GltfConversionException {
        LinkedHashSet<String> extensions = new LinkedHashSet<>(source.extensionsUsed());
        extensions.addAll(source.extensionsRequired());
        extensions.remove(NAME);
        extensions.remove(GltfMeshAttributeRules.QUANTIZATION);
        if (!extensions.isEmpty()) {
            throw new GltfConversionException("Unsupported glTF extensions are fail-closed: " + extensions);
        }
        Map<Integer, List<Integer>> nodes = readChains(source.gltf().getNodes(), "node");
        Map<Integer, List<Integer>> materials = readChains(source.gltf().getMaterials(), "material");
        if ((!nodes.isEmpty() || !materials.isEmpty()) && !source.extensionsUsed().contains(NAME)) {
            throw new GltfConversionException("MSFT_lod must be declared in extensionsUsed");
        }
        return nodes.isEmpty() && materials.isEmpty() ? GltfLodMetadata.NONE : new GltfLodMetadata(nodes, materials);
    }

    private static Map<Integer, List<Integer>> readChains(List<? extends GlTFProperty> properties, String role)
            throws GltfConversionException {
        Map<Integer, List<Integer>> chains = new LinkedHashMap<>();
        if (properties == null) return chains;
        for (int index = 0; index < properties.size(); index++) {
            Map<String, Object> extensions = properties.get(index).getExtensions();
            if (extensions == null) continue;
            for (String extension : extensions.keySet()) {
                if (!NAME.equals(extension)) throw new GltfConversionException("Unsupported " + role + " extension: " + extension);
            }
            if (!extensions.containsKey(NAME)) continue;
            Object value = extensions.get(NAME);
            if (!(value instanceof Map<?, ?> object) || !(object.get("ids") instanceof List<?> ids) || ids.isEmpty()) {
                throw new GltfConversionException("MSFT_lod " + role + " " + index + " requires a non-empty ids array");
            }
            List<Integer> converted = new ArrayList<>();
            for (Object id : ids) {
                if (!(id instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() < 0 || number.doubleValue() > Integer.MAX_VALUE
                        || number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw new GltfConversionException("MSFT_lod ids must be non-negative integers");
                }
                converted.add(number.intValue());
            }
            chains.put(index, converted);
        }
        return chains;
    }
}
