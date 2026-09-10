package com.tacz.guns.client.model.gltf.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.runtime.DeformedPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfVertexDeformer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Immutable, triangle-expanded geometry captured before Minecraft executes a retained render callback.
 * Expanding indices is intentional: primitives without NORMAL need one deterministic face normal per
 * triangle, and translucent triangles must be sortable without touching the source model later.
 */
final class GltfPreparedGeometry {
    private static final float EPSILON = 1.0E-8f;

    private final float[] positions;
    private final float[] normals;
    private final float[] texCoords;
    private final float[] colors;
    @Nullable
    private final Matrix4f localToWorld;
    @Nullable
    private final Matrix3f normalTransform;
    private final boolean reverseWinding;
    @Nullable
    private final int[] triangleOrder;

    private GltfPreparedGeometry(float[] positions, float[] normals, float[] texCoords, float[] colors) {
        this(positions, normals, texCoords, colors, null, null, false, null);
    }

    private GltfPreparedGeometry(float[] positions, float[] normals, float[] texCoords, float[] colors,
                                 @Nullable Matrix4f localToWorld, @Nullable Matrix3f normalTransform,
                                 boolean reverseWinding, @Nullable int[] triangleOrder) {
        this.positions = positions;
        this.normals = normals;
        this.texCoords = texCoords;
        this.colors = colors;
        this.localToWorld = localToWorld;
        this.normalTransform = normalTransform;
        this.reverseWinding = reverseWinding;
        this.triangleOrder = triangleOrder;
    }

    static boolean canCacheRigid(GltfMeshPrimitive primitive) {
        if (primitive.hasSkinAttributes() || !primitive.morphTargets().isEmpty()) {
            return false;
        }
        float[] normals = primitive.normals();
        if (normals.length == 0) {
            // Generated face normals depend on world-space degeneracy, including its +Y fallback.
            return false;
        }
        for (int offset = 0; offset < normals.length; offset += 3) {
            float lengthSquared = normals[offset] * normals[offset]
                    + normals[offset + 1] * normals[offset + 1] + normals[offset + 2] * normals[offset + 2];
            if (!Float.isFinite(lengthSquared) || lengthSquared <= EPSILON) {
                // Keep the old path when a later node scale could rescue a tiny authored normal.
                return false;
            }
        }
        return true;
    }

    GltfPreparedGeometry captureTransform(Matrix4fc transform) {
        if (localToWorld != null || triangleOrder != null || reverseWinding) {
            throw new IllegalStateException("only mesh-local geometry can capture a node transform");
        }
        Matrix4f captured = new Matrix4f(transform);
        if (!captured.isFinite()) {
            throw new IllegalArgumentException("node transform must be finite");
        }
        boolean reverse = requiresWindingReversal(captured);
        Matrix3f capturedNormal = new Matrix3f(captured).invert().transpose();
        GltfPreparedGeometry snapshot = new GltfPreparedGeometry(positions, normals, texCoords, colors,
                captured, capturedNormal, reverse, null);
        // Validate before queueing, without rebuilding vertex arrays. Retained callbacks own these
        // copied matrices and immutable local arrays, never a live rig or a mutable pose reference.
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            requireFinite(snapshot.position(vertex, position), "transformed position");
            snapshot.normal(vertex, normal);
        }
        return snapshot;
    }

    static GltfPreparedGeometry create(
            GltfRenderPrimitive primitive,
            DeformedPrimitive deformed,
            @Nullable Matrix4fc localToWorld,
            boolean reverseWinding
    ) {
        return create(primitive, deformed, localToWorld, reverseWinding, true);
    }

    static GltfPreparedGeometry createRigidLocal(GltfRenderPrimitive primitive) {
        if (!canCacheRigid(primitive.runtimePrimitive())) {
            throw new IllegalArgumentException("local rigid cache requires authored normals without skin or morph");
        }
        // The deformer already normalizes authored normals. Preserve those exact values here so
        // the frame applies the node normal matrix before the next normalization, as the CPU path does.
        return create(primitive, GltfVertexDeformer.deform(primitive.runtimePrimitive(), null, null, new float[0]),
                null, false, false);
    }

    private static GltfPreparedGeometry create(
            GltfRenderPrimitive primitive,
            DeformedPrimitive deformed,
            @Nullable Matrix4fc localToWorld,
            boolean reverseWinding,
            boolean normalizeNormals
    ) {
        if (deformed.vertexCount() != primitive.runtimePrimitive().vertexCount()) {
            throw new IllegalArgumentException("deformed vertex count does not match render primitive");
        }

        float[] sourcePositions = deformed.positions();
        float[] sourceNormals = deformed.normals();
        float[] sourceTexCoords = primitive.texCoords0();
        float[] sourceColors = primitive.colors0();
        int[] sourceIndices = primitive.indices();
        boolean hasNormals = sourceNormals.length != 0;
        boolean hasTexCoords = sourceTexCoords.length != 0;
        boolean hasColors = sourceColors.length != 0;

        Matrix3f normalTransform = null;
        if (localToWorld != null) {
            normalTransform = new Matrix3f(localToWorld);
            float determinant = normalTransform.determinant();
            if (!Float.isFinite(determinant) || Math.abs(determinant) <= EPSILON) {
                throw new IllegalArgumentException("node transform is singular or non-finite");
            }
            normalTransform.invert().transpose();
        }

        int emittedVertexCount = sourceIndices.length;
        float[] positions = new float[checkedArrayLength(emittedVertexCount, 3)];
        float[] normals = new float[checkedArrayLength(emittedVertexCount, 3)];
        float[] texCoords = new float[checkedArrayLength(emittedVertexCount, 2)];
        float[] colors = new float[checkedArrayLength(emittedVertexCount, 4)];

        for (int triangle = 0; triangle < sourceIndices.length / 3; triangle++) {
            int sourceOffset = triangle * 3;
            int first = sourceIndices[sourceOffset];
            int second = sourceIndices[sourceOffset + (reverseWinding ? 2 : 1)];
            int third = sourceIndices[sourceOffset + (reverseWinding ? 1 : 2)];
            int[] vertices = {first, second, third};

            for (int corner = 0; corner < 3; corner++) {
                int sourceVertex = vertices[corner];
                int emittedVertex = sourceOffset + corner;
                Vector3f position = vector3(sourcePositions, sourceVertex);
                if (localToWorld != null) {
                    position.mulPosition(localToWorld);
                }
                requireFinite(position, "transformed position");
                writeVector3(positions, emittedVertex, position);

                int uvOffset = emittedVertex * 2;
                if (hasTexCoords) {
                    texCoords[uvOffset] = sourceTexCoords[sourceVertex * 2];
                    texCoords[uvOffset + 1] = sourceTexCoords[sourceVertex * 2 + 1];
                }

                int colorOffset = emittedVertex * 4;
                if (hasColors) {
                    for (int component = 0; component < 4; component++) {
                        float color = sourceColors[sourceVertex * 4 + component];
                        if (!Float.isFinite(color) || color < 0.0f || color > 1.0f) {
                            throw new IllegalArgumentException("COLOR_0 must be finite and in [0, 1]");
                        }
                        colors[colorOffset + component] = color;
                    }
                } else {
                    Arrays.fill(colors, colorOffset, colorOffset + 4, 1.0f);
                }

                if (hasNormals) {
                    Vector3f normal = vector3(sourceNormals, sourceVertex);
                    if (normalTransform != null) {
                        normal.mul(normalTransform);
                    }
                    if (normalizeNormals) {
                        normalizeNormal(normal, "transformed normal");
                    } else {
                        validateNormal(normal, "deformed normal");
                    }
                    writeVector3(normals, emittedVertex, normal);
                }
            }

            if (!hasNormals) {
                Vector3f faceNormal = faceNormal(positions, sourceOffset);
                writeVector3(normals, sourceOffset, faceNormal);
                writeVector3(normals, sourceOffset + 1, faceNormal);
                writeVector3(normals, sourceOffset + 2, faceNormal);
            }
        }
        return new GltfPreparedGeometry(positions, normals, texCoords, colors);
    }

    static boolean requiresWindingReversal(Matrix4fc transform) {
        float determinant = new Matrix3f(transform).determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) <= EPSILON) {
            throw new IllegalArgumentException("node transform is singular or non-finite");
        }
        return determinant < 0.0f;
    }

    GltfPreparedGeometry sortedBackToFront(Matrix4fc modelView) {
        Matrix4fc geometryModelView = sortModelView(modelView);
        int triangleCount = triangleCount();
        if (triangleCount < 2) {
            validateSortTransform(geometryModelView);
            return this;
        }

        TriangleDepth[] order = new TriangleDepth[triangleCount];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            order[triangle] = new TriangleDepth(triangle, triangleViewDepth(geometryModelView, triangle));
        }
        Arrays.sort(order, (left, right) -> {
            int byDepth = Float.compare(right.viewDepth(), left.viewDepth());
            return byDepth != 0 ? byDepth : Integer.compare(left.index(), right.index());
        });

        if (localToWorld != null) {
            int[] sortedTriangles = new int[triangleCount];
            for (int destination = 0; destination < triangleCount; destination++) {
                sortedTriangles[destination] = sourceTriangle(order[destination].index());
            }
            return new GltfPreparedGeometry(positions, normals, texCoords, colors,
                    localToWorld, normalTransform, reverseWinding, sortedTriangles);
        }

        float[] sortedPositions = new float[positions.length];
        float[] sortedNormals = new float[normals.length];
        float[] sortedTexCoords = new float[texCoords.length];
        float[] sortedColors = new float[colors.length];
        for (int destination = 0; destination < order.length; destination++) {
            int source = order[destination].index();
            copyTriangle(positions, 9, source, sortedPositions, destination);
            copyTriangle(normals, 9, source, sortedNormals, destination);
            copyTriangle(texCoords, 6, source, sortedTexCoords, destination);
            copyTriangle(colors, 12, source, sortedColors, destination);
        }
        return new GltfPreparedGeometry(sortedPositions, sortedNormals, sortedTexCoords, sortedColors);
    }

    float farthestTriangleViewDepth(Matrix4fc modelView) {
        Matrix4fc geometryModelView = sortModelView(modelView);
        validateSortTransform(geometryModelView);
        float farthest = Float.NEGATIVE_INFINITY;
        for (int triangle = 0; triangle < triangleCount(); triangle++) {
            farthest = Math.max(farthest, triangleViewDepth(geometryModelView, triangle));
        }
        return farthest;
    }

    void emit(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            position(vertex, position);
            normal(vertex, normal);
            int sourceVertex = sourceVertex(vertex);
            int uvOffset = sourceVertex * 2;
            int colorOffset = sourceVertex * 4;
            buffer.addVertex(
                            pose,
                            position.x,
                            position.y,
                            position.z
                    )
                    .setColor(
                            colors[colorOffset],
                            colors[colorOffset + 1],
                            colors[colorOffset + 2],
                            colors[colorOffset + 3]
                    )
                    .setUv(texCoords[uvOffset], texCoords[uvOffset + 1])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(
                            pose,
                            normal.x,
                            normal.y,
                            normal.z
                    );
        }
    }

    int vertexCount() {
        return positions.length / 3;
    }

    /** The visitor receives one reused scratch vector, never the retained vertex arrays. */
    void visitPositions(Consumer<Vector3fc> visitor) {
        Vector3f point = new Vector3f();
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            visitor.accept(position(vertex, point));
        }
    }

    int triangleCount() {
        return positions.length / 9;
    }

    float[] positions() {
        if (localToWorld == null) {
            return positions.clone();
        }
        float[] snapshot = new float[positions.length];
        Vector3f value = new Vector3f();
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            writeVector3(snapshot, vertex, position(vertex, value));
        }
        return snapshot;
    }

    float[] normals() {
        if (localToWorld == null) {
            return normals.clone();
        }
        float[] snapshot = new float[normals.length];
        Vector3f value = new Vector3f();
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            writeVector3(snapshot, vertex, normal(vertex, value));
        }
        return snapshot;
    }

    float[] texCoords() {
        return copyAttributes(texCoords, 2);
    }

    float[] colors() {
        return copyAttributes(colors, 4);
    }

    private float[] copyAttributes(float[] source, int components) {
        if (!reverseWinding && triangleOrder == null) {
            return source.clone();
        }
        float[] copy = new float[source.length];
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            System.arraycopy(source, sourceVertex(vertex) * components, copy, vertex * components, components);
        }
        return copy;
    }

    private Vector3f position(int vertex, Vector3f destination) {
        int offset = sourceVertex(vertex) * 3;
        destination.set(positions[offset], positions[offset + 1], positions[offset + 2]);
        return localToWorld == null ? destination : destination.mulPosition(localToWorld);
    }

    private Vector3f normal(int vertex, Vector3f destination) {
        int offset = sourceVertex(vertex) * 3;
        destination.set(normals[offset], normals[offset + 1], normals[offset + 2]);
        if (normalTransform != null) {
            destination.mul(normalTransform);
            normalizeNormal(destination, "transformed normal");
        }
        return destination;
    }

    private int sourceVertex(int vertex) {
        int corner = vertex % 3;
        return sourceTriangle(vertex / 3) * 3 + (reverseWinding && corner != 0 ? 3 - corner : corner);
    }

    private int sourceTriangle(int triangle) {
        return triangleOrder == null ? triangle : triangleOrder[triangle];
    }

    private Matrix4fc sortModelView(Matrix4fc modelView) {
        return localToWorld == null ? modelView : new Matrix4f(modelView).mul(localToWorld);
    }

    private float triangleViewDepth(Matrix4fc modelView, int triangle) {
        int offset = sourceTriangle(triangle) * 9;
        Vector3f centroid = new Vector3f(
                (positions[offset] + positions[offset + 3] + positions[offset + 6]) / 3.0f,
                (positions[offset + 1] + positions[offset + 4] + positions[offset + 7]) / 3.0f,
                (positions[offset + 2] + positions[offset + 5] + positions[offset + 8]) / 3.0f
        ).mulPosition(modelView);
        requireFinite(centroid, "translucent triangle centroid");
        // Minecraft view space looks down -Z. Depth avoids treating a lateral offset as farther away.
        return -centroid.z;
    }

    private static void validateSortTransform(Matrix4fc modelView) {
        Vector3f origin = new Vector3f().mulPosition(modelView);
        requireFinite(origin, "model-view transform");
    }

    private static Vector3f faceNormal(float[] positions, int firstVertex) {
        Vector3f first = vector3(positions, firstVertex);
        Vector3f second = vector3(positions, firstVertex + 1);
        Vector3f third = vector3(positions, firstVertex + 2);
        Vector3f normal = second.sub(first, new Vector3f()).cross(third.sub(first, new Vector3f()));
        if (!Float.isFinite(normal.lengthSquared()) || normal.lengthSquared() <= EPSILON) {
            // Degenerate source triangles are legal enough to encounter in authored assets; avoid NaNs.
            return new Vector3f(0.0f, 1.0f, 0.0f);
        }
        return normal.normalize();
    }

    private static void normalizeNormal(Vector3f normal, String role) {
        validateNormal(normal, role);
        normal.normalize();
    }

    private static void validateNormal(Vector3f normal, String role) {
        requireFinite(normal, role);
        float lengthSquared = normal.lengthSquared();
        if (!Float.isFinite(lengthSquared) || lengthSquared <= EPSILON) {
            throw new IllegalArgumentException(role + " must not be zero length");
        }
    }

    private static void requireFinite(Vector3f value, String role) {
        if (!Float.isFinite(value.x) || !Float.isFinite(value.y) || !Float.isFinite(value.z)) {
            throw new IllegalArgumentException(role + " must be finite");
        }
    }

    private static Vector3f vector3(float[] values, int index) {
        int offset = index * 3;
        return new Vector3f(values[offset], values[offset + 1], values[offset + 2]);
    }

    private static void writeVector3(float[] output, int index, Vector3f value) {
        int offset = index * 3;
        output[offset] = value.x;
        output[offset + 1] = value.y;
        output[offset + 2] = value.z;
    }

    private static void copyTriangle(float[] source, int valuesPerTriangle, int sourceTriangle,
                                     float[] destination, int destinationTriangle) {
        System.arraycopy(
                source,
                sourceTriangle * valuesPerTriangle,
                destination,
                destinationTriangle * valuesPerTriangle,
                valuesPerTriangle
        );
    }

    private static int checkedArrayLength(int vertexCount, int components) {
        try {
            return Math.multiplyExact(vertexCount, components);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("glTF prepared geometry exceeds Java array limits", exception);
        }
    }

    private record TriangleDepth(int index, float viewDepth) {
    }
}
