package com.tacz.guns.client.model.gltf.convert;

import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorDatas;
import de.javagl.jgltf.model.AccessorDoubleData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.BufferViewModel;
import de.javagl.jgltf.model.ElementType;
import de.javagl.jgltf.model.GltfConstants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

final class GltfAccessorReader {
    private GltfAccessorReader() {
    }

    static float[] readFloats(
            AccessorModel accessor,
            ElementType elementType,
            Set<Integer> allowedComponentTypes,
            boolean requireNormalizedIntegers,
            String role
    ) throws GltfConversionException {
        requireAccessor(accessor, elementType, allowedComponentTypes, role);
        return readFloats(accessor, elementType, requireNormalizedIntegers, accessor.getCount(), role);
    }

    static float[] readFloatPrefix(
            AccessorModel accessor,
            ElementType elementType,
            Set<Integer> allowedComponentTypes,
            boolean requireNormalizedIntegers,
            int elementCount,
            String role
    ) throws GltfConversionException {
        requireAccessor(accessor, elementType, allowedComponentTypes, role);
        if (elementCount < 0 || accessor.getCount() < elementCount) {
            throw error(role, "count is " + accessor.getCount() + " but expected at least " + elementCount);
        }
        return readFloats(accessor, elementType, requireNormalizedIntegers, elementCount, role);
    }

    private static float[] readFloats(
            AccessorModel accessor,
            ElementType elementType,
            boolean requireNormalizedIntegers,
            int elementCount,
            String role
    ) throws GltfConversionException {
        int componentType = accessor.getComponentType();
        if (componentType == GltfConstants.GL_FLOAT && accessor.isNormalized()) {
            throw error(role, "FLOAT accessors must not set normalized");
        }
        if (componentType != GltfConstants.GL_FLOAT
                && requireNormalizedIntegers
                && !accessor.isNormalized()) {
            throw error(role, "integer accessor must set normalized=true");
        }

        AccessorData data = requireData(accessor, role);
        int components = elementType.getNumComponents();
        float[] result = new float[Math.multiplyExact(elementCount, components)];
        for (int element = 0; element < elementCount; element++) {
            for (int component = 0; component < components; component++) {
                double value = numericValue(data, element, component, role);
                if (accessor.isNormalized()) {
                    value = normalize(value, componentType);
                }
                if (!Double.isFinite(value) || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
                    throw error(role, "contains a non-finite or out-of-range value");
                }
                result[element * components + component] = (float) value;
            }
        }
        return result;
    }

    static int[] readUnsignedInts(
            AccessorModel accessor,
            ElementType elementType,
            Set<Integer> allowedComponentTypes,
            String role
    ) throws GltfConversionException {
        requireAccessor(accessor, elementType, allowedComponentTypes, role);
        if (accessor.isNormalized()) {
            throw error(role, "integer accessor must not be normalized");
        }
        AccessorData data = requireData(accessor, role);
        int components = elementType.getNumComponents();
        int[] result = new int[Math.multiplyExact(accessor.getCount(), components)];
        for (int element = 0; element < accessor.getCount(); element++) {
            for (int component = 0; component < components; component++) {
                long value = integerValue(data, element, component, role);
                if (value < 0 || value > Integer.MAX_VALUE) {
                    throw error(role, "contains an index outside Java's supported range: " + value);
                }
                result[element * components + component] = (int) value;
            }
        }
        return result;
    }

    static void requireCount(AccessorModel accessor, int expectedCount, String role) throws GltfConversionException {
        if (accessor.getCount() != expectedCount) {
            throw error(role, "count is " + accessor.getCount() + " but expected " + expectedCount);
        }
    }

    private static void requireAccessor(
            AccessorModel accessor,
            ElementType elementType,
            Set<Integer> allowedComponentTypes,
            String role
    ) throws GltfConversionException {
        if (accessor == null) {
            throw error(role, "accessor is missing");
        }
        if (accessor.getElementType() != elementType) {
            throw error(role, "must have type " + elementType + " but has " + accessor.getElementType());
        }
        if (!allowedComponentTypes.contains(accessor.getComponentType())) {
            throw error(role, "has unsupported component type "
                    + GltfConstants.stringFor(accessor.getComponentType()));
        }
        if (accessor.getCount() < 0) {
            throw error(role, "has a negative count");
        }
    }

    private static AccessorData requireData(AccessorModel accessor, String role) throws GltfConversionException {
        BufferViewModel bufferView = accessor.getBufferViewModel();
        if (bufferView == null || bufferView.getBufferViewData() == null) {
            throw error(role, "has no resolved bufferView data");
        }
        ByteBuffer littleEndian = bufferView.getBufferViewData().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        AccessorData data = AccessorDatas.create(accessor, littleEndian);
        if (data.getNumElements() != accessor.getCount()
                || data.getNumComponentsPerElement() != accessor.getElementType().getNumComponents()) {
            throw error(role, "resolved accessor data shape does not match its declaration");
        }
        return data;
    }

    private static double numericValue(
            AccessorData data,
            int element,
            int component,
            String role
    ) throws GltfConversionException {
        if (data instanceof AccessorFloatData floats) {
            return floats.get(element, component);
        }
        if (data instanceof AccessorByteData bytes) {
            return bytes.getInt(element, component);
        }
        if (data instanceof AccessorShortData shorts) {
            return shorts.getInt(element, component);
        }
        if (data instanceof AccessorIntData ints) {
            return ints.getLong(element, component);
        }
        if (data instanceof AccessorDoubleData doubles) {
            return doubles.get(element, component);
        }
        throw error(role, "uses an unknown accessor data implementation " + data.getClass().getName());
    }

    private static long integerValue(
            AccessorData data,
            int element,
            int component,
            String role
    ) throws GltfConversionException {
        if (data instanceof AccessorByteData bytes) {
            return bytes.getInt(element, component);
        }
        if (data instanceof AccessorShortData shorts) {
            return shorts.getInt(element, component);
        }
        if (data instanceof AccessorIntData ints) {
            return ints.getLong(element, component);
        }
        throw error(role, "is not backed by integer accessor data");
    }

    private static double normalize(double value, int componentType) throws GltfConversionException {
        return switch (componentType) {
            case GltfConstants.GL_BYTE -> Math.max(value / 127.0, -1.0);
            case GltfConstants.GL_UNSIGNED_BYTE -> value / 255.0;
            case GltfConstants.GL_SHORT -> Math.max(value / 32767.0, -1.0);
            case GltfConstants.GL_UNSIGNED_SHORT -> value / 65535.0;
            case GltfConstants.GL_UNSIGNED_INT -> value / 4294967295.0;
            default -> throw new GltfConversionException(
                    "Cannot normalize glTF component type " + GltfConstants.stringFor(componentType)
            );
        };
    }

    private static GltfConversionException error(String role, String message) {
        return new GltfConversionException(role + " " + message);
    }
}
