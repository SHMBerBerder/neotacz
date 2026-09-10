package com.tacz.guns.client.model.gltf.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GltfRuntimeMathTest {
    private static final float EPSILON = 1.0E-5f;

    @Test
    void computesNodeWorldTransformsThroughSceneRoots() {
        GltfScene scene = new GltfScene(
                List.of(
                        new GltfNode("root", GltfNodeTransform.translation(1.0f, 0.0f, 0.0f), new int[]{1}, -1, -1),
                        new GltfNode("child", GltfNodeTransform.translation(0.0f, 2.0f, 0.0f), new int[0], -1, -1)
                ),
                List.of(),
                List.of(),
                new int[]{0}
        );

        Matrix4f[] transforms = GltfSceneTransforms.computeWorldTransforms(scene);

        assertVectorEquals(new Vector3f(1.0f, 2.0f, 0.0f), transforms[1].getTranslation(new Vector3f()));
    }

    @Test
    void preservesDisconnectedHierarchyWhenChildPrecedesParent() {
        GltfScene scene = new GltfScene(
                List.of(
                        new GltfNode("externalChild", GltfNodeTransform.translation(0.0f, 2.0f, 0.0f),
                                new int[0], -1, -1),
                        new GltfNode("externalParent", GltfNodeTransform.translation(1.0f, 0.0f, 0.0f),
                                new int[]{0}, -1, -1),
                        new GltfNode("activeRoot", GltfNodeTransform.identity(), new int[0], -1, -1)
                ),
                List.of(),
                List.of(),
                new int[]{2}
        );

        Matrix4f[] transforms = GltfSceneTransforms.computeWorldTransforms(scene);

        assertVectorEquals(new Vector3f(1.0f, 2.0f, 0.0f), transforms[0].getTranslation(new Vector3f()));
        assertVectorEquals(new Vector3f(1.0f, 0.0f, 0.0f), transforms[1].getTranslation(new Vector3f()));
    }

    @Test
    void appliesMorphBeforeSkinningAndNormalizesFourJointWeights() {
        GltfSkin skin = new GltfSkin(
                new int[]{0, 1},
                List.of(
                        new Matrix4f(),
                        new Matrix4f().translation(-1.0f, 0.0f, 0.0f)
                )
        );
        Matrix4f[] jointWorlds = {
                new Matrix4f(),
                new Matrix4f().scaling(2.0f, 1.0f, 1.0f)
        };
        float invSqrt2 = (float) (1.0 / Math.sqrt(2.0));
        GltfMeshPrimitive primitive = new GltfMeshPrimitive(
                new float[]{
                        1.0f, 0.0f, 0.0f,
                        3.0f, 0.0f, 0.0f
                },
                new float[]{
                        invSqrt2, invSqrt2, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[]{
                        1.0f, 0.0f, 0.0f, 1.0f,
                        0.0f, 1.0f, 0.0f, 1.0f
                },
                new int[]{
                        1, 0, 0, 0,
                        0, 1, 0, 1
                },
                new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 1.0f, 1.0f, 1.0f
                },
                List.of(new GltfMorphTarget(
                        new float[]{
                                1.0f, 0.0f, 0.0f,
                                0.0f, 0.0f, 0.0f
                        },
                        new float[6],
                        new float[]{
                                0.0f, 1.0f, 0.0f,
                                0.0f, 0.0f, 0.0f
                        }
                )),
                new GltfMaterialReference("test")
        );

        DeformedPrimitive deformed = GltfVertexDeformer.deform(primitive, skin, jointWorlds, new float[]{1.0f});

        assertArrayEquals(new float[]{2.0f, 0.0f, 0.0f}, deformed.position(0), EPSILON);
        assertArrayEquals(new float[]{3.5f, 0.0f, 0.0f}, deformed.position(1), EPSILON);
        assertArrayEquals(new float[]{0.4472136f, 0.8944272f, 0.0f}, deformed.normal(0), EPSILON);
        assertArrayEquals(new float[]{0.4472136f, 0.8944272f, 0.0f, 1.0f}, deformed.tangent(0), EPSILON);
    }

    @Test
    void transformsNormalsWithTheInverseTransposeOfTheBlendedJointMatrix() {
        GltfSkin skin = new GltfSkin(
                new int[]{0, 1},
                List.of(new Matrix4f(), new Matrix4f())
        );
        Matrix4f[] jointWorlds = {
                new Matrix4f().scaling(2.0f, 1.0f, 1.0f),
                new Matrix4f().scaling(1.0f, 3.0f, 1.0f)
        };
        GltfMeshPrimitive primitive = new GltfMeshPrimitive(
                new float[]{1.0f, 1.0f, 0.0f},
                new float[]{1.0f, 1.0f, 0.0f},
                new float[]{1.0f, 1.0f, 0.0f, -1.0f},
                new int[]{0, 1, 0, 0},
                new float[]{0.25f, 0.75f, 0.0f, 0.0f},
                List.of(),
                new GltfMaterialReference("test")
        );

        DeformedPrimitive deformed = GltfVertexDeformer.deform(primitive, skin, jointWorlds, new float[0]);

        assertArrayEquals(new float[]{1.25f, 2.5f, 0.0f}, deformed.position(0), EPSILON);
        assertArrayEquals(new float[]{0.8944272f, 0.4472136f, 0.0f}, deformed.normal(0), EPSILON);
        assertArrayEquals(new float[]{0.8944272f, 0.4472136f, 0.0f, -1.0f}, deformed.tangent(0), EPSILON);
    }

    @Test
    void samplesStepLinearAndWeightTracks() {
        GltfAnimationSampler step = GltfAnimationSampler.translation(
                GltfInterpolation.STEP,
                new float[]{0.0f, 1.0f},
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        10.0f, 0.0f, 0.0f
                }
        );
        GltfAnimationSampler linearScale = GltfAnimationSampler.scale(
                GltfInterpolation.LINEAR,
                new float[]{0.0f, 2.0f},
                new float[]{
                        1.0f, 1.0f, 1.0f,
                        3.0f, 5.0f, 7.0f
                }
        );
        GltfAnimationSampler weights = GltfAnimationSampler.weights(
                2,
                GltfInterpolation.LINEAR,
                new float[]{0.0f, 2.0f},
                new float[]{
                        0.0f, 1.0f,
                        1.0f, 0.0f
                }
        );

        assertVectorEquals(new Vector3f(0.0f, 0.0f, 0.0f), step.sampleVector3(0.75f));
        assertVectorEquals(new Vector3f(10.0f, 0.0f, 0.0f), step.sampleVector3(1.25f));
        assertVectorEquals(new Vector3f(1.5f, 2.0f, 2.5f), linearScale.sampleVector3(0.5f));
        assertArrayEquals(new float[]{0.5f, 0.5f}, weights.sampleFloats(1.0f), EPSILON);
    }

    @Test
    void samplesRotationWithSlerpAndNormalization() {
        Quaternionf halfTurnZ = new Quaternionf().rotateZ((float) Math.PI);
        GltfAnimationSampler rotation = GltfAnimationSampler.rotation(
                GltfInterpolation.LINEAR,
                new float[]{0.0f, 1.0f},
                new float[]{
                        0.0f, 0.0f, 0.0f, 1.0f,
                        halfTurnZ.x(), halfTurnZ.y(), halfTurnZ.z(), halfTurnZ.w()
                }
        );

        Quaternionf sampled = rotation.sampleRotation(0.5f);

        assertEquals(1.0f, quaternionLength(sampled), EPSILON);
        assertEquals((float) Math.sqrt(0.5), Math.abs(sampled.z), EPSILON);
        assertEquals((float) Math.sqrt(0.5), Math.abs(sampled.w), EPSILON);
    }

    @Test
    void samplesCubicSplineTripletsWithDeltaTimeScaling() {
        GltfAnimationSampler cubicTranslation = GltfAnimationSampler.translation(
                GltfInterpolation.CUBICSPLINE,
                new float[]{0.0f, 2.0f},
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f
                }
        );
        GltfAnimationSampler cubicRotation = GltfAnimationSampler.rotation(
                GltfInterpolation.CUBICSPLINE,
                new float[]{0.0f, 2.0f},
                new float[]{
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 2.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 2.0f,
                        0.0f, 0.0f, 0.0f, 0.0f
                }
        );

        assertVectorEquals(new Vector3f(0.25f, 0.0f, 0.0f), cubicTranslation.sampleVector3(1.0f));
        assertEquals(1.0f, quaternionLength(cubicRotation.sampleRotation(1.0f)), EPSILON);
    }

    @Test
    void rejectsInvalidSceneAndSkinningState() {
        GltfScene cyclicScene = new GltfScene(
                List.of(new GltfNode("cycle", GltfNodeTransform.identity(), new int[]{0}, -1, -1)),
                List.of(),
                List.of(),
                new int[]{0}
        );
        GltfMeshPrimitive negativeWeight = new GltfMeshPrimitive(
                new float[]{0.0f, 0.0f, 0.0f},
                new float[0],
                new float[0],
                new int[]{0, 0, 0, 0},
                new float[]{-1.0f, 0.0f, 0.0f, 0.0f},
                List.of(),
                new GltfMaterialReference("test")
        );
        GltfSkin skin = new GltfSkin(new int[]{0}, List.of(new Matrix4f()));
        GltfMeshPrimitive zeroWeights = new GltfMeshPrimitive(
                new float[]{0.0f, 0.0f, 0.0f},
                new float[0],
                new float[0],
                new int[]{0, 0, 0, 0},
                new float[]{0.0f, 0.0f, 0.0f, 0.0f},
                List.of(),
                new GltfMaterialReference("test")
        );

        assertThrows(IllegalArgumentException.class, () -> GltfSceneTransforms.computeWorldTransforms(cyclicScene));
        assertThrows(IllegalArgumentException.class, () -> GltfVertexDeformer.deform(
                negativeWeight,
                skin,
                new Matrix4f[]{new Matrix4f()},
                new float[0]
        ));
        assertThrows(IllegalArgumentException.class, () -> GltfVertexDeformer.deform(
                zeroWeights,
                skin,
                new Matrix4f[]{new Matrix4f()},
                new float[0]
        ));
        assertThrows(IllegalArgumentException.class, () -> GltfAnimationSampler.translation(
                GltfInterpolation.LINEAR,
                new float[]{-1.0f, 0.0f},
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f
                }
        ));
        assertThrows(IllegalArgumentException.class, () -> GltfAnimationSampler.translation(
                GltfInterpolation.CUBICSPLINE,
                new float[]{0.0f},
                new float[9]
        ));
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    private static float quaternionLength(Quaternionf quaternion) {
        return (float) Math.sqrt(
                quaternion.x() * quaternion.x()
                        + quaternion.y() * quaternion.y()
                        + quaternion.z() * quaternion.z()
                        + quaternion.w() * quaternion.w()
        );
    }
}
