package com.tacz.guns.client.model.gltf.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.GltfAnimationClip;
import com.tacz.guns.client.model.gltf.convert.GltfAlphaMode;
import com.tacz.guns.client.model.gltf.convert.GltfImageData;
import com.tacz.guns.client.model.gltf.convert.GltfRenderMesh;
import com.tacz.guns.client.model.gltf.convert.GltfRenderPrimitive;
import com.tacz.guns.client.model.gltf.convert.GltfSamplerData;
import com.tacz.guns.client.model.gltf.convert.GltfSceneData;
import com.tacz.guns.client.model.gltf.convert.GltfTextureBinding;
import com.tacz.guns.client.model.gltf.runtime.DeformedPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimation;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationChannel;
import com.tacz.guns.client.model.gltf.runtime.GltfAnimationSampler;
import com.tacz.guns.client.model.gltf.runtime.GltfInterpolation;
import com.tacz.guns.client.model.gltf.runtime.GltfMaterialReference;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;
import com.tacz.guns.client.model.gltf.runtime.GltfMeshPrimitive;
import com.tacz.guns.client.model.gltf.runtime.GltfMorphTarget;
import com.tacz.guns.client.model.gltf.runtime.GltfNode;
import com.tacz.guns.client.model.gltf.runtime.GltfNodeTransform;
import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfSkin;
import com.tacz.guns.client.model.gltf.runtime.GltfVertexDeformer;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import de.javagl.jgltf.model.GltfConstants;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfGunBodyRendererSupportTest {
    private static final float EPSILON = 1.0E-5f;
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer())
            .create();

    @Test
    void preparesStaticTriangleWithDefaultUvColorAndStableFaceNormal() {
        GltfMeshPrimitive runtime = primitive(
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[0]
        );
        GltfRenderPrimitive render = new GltfRenderPrimitive(
                runtime,
                new int[]{0, 1, 2},
                new float[0],
                new float[0],
                -1
        );
        DeformedPrimitive deformed = GltfVertexDeformer.deform(runtime, null, null, new float[0]);

        GltfPreparedGeometry geometry = GltfPreparedGeometry.create(
                render,
                deformed,
                new Matrix4f().translation(1.0f, 2.0f, 3.0f),
                false
        );

        assertArrayEquals(new float[]{
                1.0f, 2.0f, 3.0f,
                2.0f, 2.0f, 3.0f,
                1.0f, 3.0f, 3.0f
        }, geometry.positions(), EPSILON);
        assertArrayEquals(new float[]{
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        }, geometry.normals(), EPSILON);
        assertArrayEquals(new float[6], geometry.texCoords(), EPSILON);
        assertArrayEquals(new float[]{
                1.0f, 1.0f, 1.0f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f
        }, geometry.colors(), EPSILON);
    }

    @Test
    void reversesWindingForNegativeNodeDeterminant() {
        GltfMeshPrimitive runtime = primitive(
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[0]
        );
        GltfRenderPrimitive render = new GltfRenderPrimitive(
                runtime,
                new int[]{0, 1, 2},
                new float[0],
                new float[0],
                -1
        );
        Matrix4f reflection = new Matrix4f().scaling(-1.0f, 1.0f, 1.0f);

        assertTrue(GltfPreparedGeometry.requiresWindingReversal(reflection));
        GltfPreparedGeometry geometry = GltfPreparedGeometry.create(
                render,
                GltfVertexDeformer.deform(runtime, null, null, new float[0]),
                reflection,
                true
        );

        assertArrayEquals(new float[]{
                0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f,
                -1.0f, 0.0f, 0.0f
        }, geometry.positions(), EPSILON);
        assertArrayEquals(new float[]{
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        }, geometry.normals(), EPSILON);
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfPreparedGeometry.requiresWindingReversal(new Matrix4f().scaling(0.0f, 1.0f, 1.0f))
        );
    }

    @Test
    void emitsEveryCapturedVertexWithUvColorOverlayLightAndNormal() {
        GltfMeshPrimitive runtime = primitive(
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                }
        );
        GltfRenderPrimitive render = new GltfRenderPrimitive(
                runtime,
                new int[]{0, 1, 2},
                new float[]{0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f},
                new float[]{
                        1.0f, 0.0f, 0.0f, 1.0f,
                        0.0f, 1.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f, 1.0f
                },
                -1
        );
        GltfPreparedGeometry geometry = GltfPreparedGeometry.create(
                render,
                GltfVertexDeformer.deform(runtime, null, null, new float[0]),
                new Matrix4f(),
                false
        );
        CapturingVertexConsumer consumer = new CapturingVertexConsumer();

        geometry.emit(new PoseStack().last(), consumer, 0x00F000F0, 7 | (11 << 16));

        assertEquals(3, consumer.vertexCount);
        assertArrayEquals(new float[]{0.0f, 1.0f}, consumer.lastUv, EPSILON);
        assertArrayEquals(new int[]{0, 0, 255, 255}, consumer.lastColor);
        assertArrayEquals(new int[]{7, 11}, consumer.lastOverlay);
        assertArrayEquals(new int[]{240, 240}, consumer.lastLight);
        assertArrayEquals(new float[]{0.0f, 0.0f, 1.0f}, consumer.lastNormal, EPSILON);
    }

    @Test
    void samplesAnimationPoseAndAppliesMorphBeforeSkin() {
        GltfMeshPrimitive primitive = new GltfMeshPrimitive(
                new float[]{
                        1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new float[0],
                new int[]{
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0, 0, 0
                },
                new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f
                },
                List.of(new GltfMorphTarget(
                        new float[]{
                                1.0f, 0.0f, 0.0f,
                                0.0f, 0.0f, 0.0f,
                                0.0f, 0.0f, 0.0f
                        },
                        new float[9],
                        new float[9]
                )),
                new GltfMaterialReference("default")
        );
        GltfSkin skin = new GltfSkin(new int[]{0}, List.of(new Matrix4f()));
        GltfScene scene = new GltfScene(
                List.of(
                        new GltfNode("joint", GltfNodeTransform.identity(), new int[0], -1, -1),
                        new GltfNode("mesh", GltfNodeTransform.identity(), new int[0], 0, 0)
                ),
                List.of(new GltfMesh(List.of(primitive))),
                List.of(skin),
                new int[]{0, 1}
        );
        GltfAnimation animation = new GltfAnimation(List.of(
                new GltfAnimationChannel(0, GltfAnimationSampler.scale(
                        GltfInterpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new float[]{
                                1.0f, 1.0f, 1.0f,
                                2.0f, 2.0f, 2.0f
                        }
                )),
                new GltfAnimationChannel(1, GltfAnimationSampler.weights(
                        1,
                        GltfInterpolation.LINEAR,
                        new float[]{0.0f, 1.0f},
                        new float[]{0.0f, 1.0f}
                ))
        ));
        GltfAnimationClip clip = new GltfAnimationClip("cycle", 1.0f, animation);
        GltfRenderPrimitive renderPrimitive = new GltfRenderPrimitive(
                primitive,
                new int[]{0, 1, 2},
                new float[0],
                new float[0],
                -1
        );
        ConvertedGltfAsset asset = new ConvertedGltfAsset(
                scene,
                List.of(new GltfRenderMesh("mesh", List.of(renderPrimitive), new float[]{0.0f})),
                List.of(),
                List.of(),
                List.of(clip),
                List.of(new float[0], new float[]{0.0f}),
                List.of(new float[]{0.0f}),
                List.of(new GltfSceneData("scene", new int[]{0, 1})),
                0,
                0
        );

        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, clip, 1.0);
        DeformedPrimitive deformed = GltfVertexDeformer.deform(
                primitive,
                skin,
                frame.worldTransforms(),
                frame.morphWeights(1)
        );

        // (base x=1 + morph x=1) is scaled by the animated joint scale 2.
        assertArrayEquals(new float[]{4.0f, 0.0f, 0.0f}, deformed.position(0), EPSILON);
    }

    @Test
    void acceptsFiniteInvertibleNegativeDeterminantProducedByJointBlending() {
        GltfMeshPrimitive primitive = new GltfMeshPrimitive(
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[0],
                new float[0],
                new int[]{
                        0, 1, 2, 0,
                        0, 1, 2, 0,
                        0, 1, 2, 0
                },
                new float[]{
                        1.0f / 3.0f, 1.0f / 3.0f, 1.0f / 3.0f, 0.0f,
                        1.0f / 3.0f, 1.0f / 3.0f, 1.0f / 3.0f, 0.0f,
                        1.0f / 3.0f, 1.0f / 3.0f, 1.0f / 3.0f, 0.0f
                },
                List.of(),
                new GltfMaterialReference("default")
        );
        GltfSkin skin = new GltfSkin(
                new int[]{0, 1, 2},
                List.of(new Matrix4f(), new Matrix4f(), new Matrix4f())
        );
        Matrix4f[] jointWorld = {
                new Matrix4f().rotationX((float) Math.PI),
                new Matrix4f().rotationY((float) Math.PI),
                new Matrix4f().rotationZ((float) Math.PI)
        };

        // Every joint is a proper rotation (determinant +1), while their equal linear blend is
        // invertible with determinant -1/27. glTF LBS does not forbid that finite result.
        DeformedPrimitive deformed = assertDoesNotThrow(
                () -> GltfVertexDeformer.deform(primitive, skin, jointWorld, new float[0])
        );
        assertArrayEquals(new float[]{-1.0f / 3.0f, 0.0f, 0.0f}, deformed.position(1), EPSILON);
        assertArrayEquals(new float[]{0.0f, -1.0f / 3.0f, 0.0f}, deformed.position(2), EPSILON);
    }

    @Test
    void staticRendererReusesPreparedGeometryAndRejectsUnsupportedOneShotAnimation() {
        Identifier modelId = Identifier.fromNamespaceAndPath("tacz", "models/gltf/static.glb");
        ConvertedGltfAsset asset = staticTriangleAsset();
        GunRenderModelConfig staticConfig = GSON.fromJson("""
                {
                  "type": "gltf",
                  "location": "tacz:models/gltf/static.glb"
                }
                """, GunRenderModelConfig.class);
        GltfGunBodyRenderer renderer = new GltfGunBodyRenderer(
                modelId,
                asset,
                new GltfModelManager(),
                0L,
                staticConfig
        );

        assertSame(renderer.preparedAtTime(0.0), renderer.preparedAtTime(10_000.0));

        GunRenderModelConfig oneShot = GSON.fromJson("""
                {
                  "type": "gltf",
                  "location": "tacz:models/gltf/static.glb",
                  "animation": "fire",
                  "loop_animation": false
                }
                """, GunRenderModelConfig.class);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GltfGunBodyRenderer(modelId, asset, new GltfModelManager(), 0L, oneShot)
        );
        assertTrue(exception.getMessage().contains("per-instance trigger state"));
    }

    @Test
    void resourceUploadsAreDeferredAndRejectAnInvalidatedRendererBeforeTouchingGpu() {
        Identifier modelId = Identifier.fromNamespaceAndPath("tacz", "models/gltf/static.glb");
        GunRenderModelConfig config = GSON.fromJson("""
                {"type":"gltf","location":"tacz:models/gltf/static.glb"}
                """, GunRenderModelConfig.class);
        GltfModelManager manager = new GltfModelManager();
        GltfGunBodyRenderer renderer = new GltfGunBodyRenderer(modelId, staticTriangleAsset(), manager, 0, config);

        // No graphics context is available in this test; enumerating work must not upload anything.
        List<Runnable> uploads = renderer.prepareResourceUploads();
        assertEquals(1, uploads.size());
        manager.clearCache();
        assertThrows(java.util.concurrent.CancellationException.class, uploads.getFirst()::run);
    }

    @Test
    void preparesEachReferencedImageOnlyOnce() {
        Identifier modelId = Identifier.fromNamespaceAndPath("tacz", "models/gltf/static.glb");
        ConvertedGltfAsset asset = staticTriangleAsset(List.of(
                new GltfImageData("pixel", "", "image/png", ONE_PIXEL_PNG)
        ));
        GunRenderModelConfig config = GSON.fromJson("""
                {
                  "type": "gltf",
                  "location": "tacz:models/gltf/static.glb"
                }
                """, GunRenderModelConfig.class);
        GltfGunBodyRenderer renderer = new GltfGunBodyRenderer(
                modelId,
                asset,
                new GltfModelManager(),
                0L,
                config
        );
        GltfTextureBinding binding = new GltfTextureBinding(0, 0, GltfSamplerData.DEFAULT);

        assertSame(renderer.textureSource(binding), renderer.textureSource(binding));
        var base = new GltfPbrTextureSource.Settings(GltfSamplerData.DEFAULT,
                com.tacz.guns.client.model.gltf.quality.TextureImageFilter.Role.BASE_COLOR,
                GltfAlphaMode.OPAQUE, 0.5f, 1f);
        var normal = new GltfPbrTextureSource.Settings(GltfSamplerData.DEFAULT,
                com.tacz.guns.client.model.gltf.quality.TextureImageFilter.Role.NORMAL,
                GltfAlphaMode.OPAQUE, 0.5f, 1f);
        var mipSampler = new GltfSamplerData(9729, 9987, 10497, 10497);
        var mip = new GltfPbrTextureSource.Settings(mipSampler,
                com.tacz.guns.client.model.gltf.quality.TextureImageFilter.Role.BASE_COLOR,
                GltfAlphaMode.OPAQUE, 0.5f, 1f);
        assertSame(renderer.textureSource(binding), renderer.textureSource(binding, base));
        assertNotSame(renderer.textureSource(binding, base), renderer.textureSource(binding, normal));
        GltfTextureBinding mipBinding = new GltfTextureBinding(0, 0, mipSampler);
        assertNotSame(renderer.textureSource(binding, base), renderer.textureSource(mipBinding, mip));
        assertSame(renderer.textureSource(mipBinding, mip), renderer.textureSource(mipBinding, mip));
        assertEquals(mip, renderer.textureSource(mipBinding, mip).settings());
    }

    @Test
    void snapshotsGlobalModelViewAndBedrockRootBeforeSubmission() {
        Matrix4f captured = GltfGunBodyRenderer.captureSortModelView(
                new Matrix4f().translation(5.0f, 0.0f, -10.0f).scale(2.0f),
                new Matrix4f().translation(1.0f, 0.0f, -2.0f)
        );
        assertArrayEquals(
                new float[]{7.0f, 0.0f, -14.0f},
                vector(captured.getTranslation(new Vector3f())),
                EPSILON
        );

        BedrockPart root = new BedrockPart("root");
        root.setPos(16.0f, 32.0f, 0.0f);
        root.offsetX = 0.5f;
        PoseStack rootPose = new PoseStack();
        GltfGunBodyRenderer.applyBedrockRootTransform(root, rootPose);
        assertArrayEquals(
                new float[]{1.5f, 2.0f, 0.0f},
                vector(rootPose.last().pose().getTranslation(new Vector3f())),
                EPSILON
        );
    }

    @Test
    void constructorRejectsIndexExpansionAmplifiedByMeshInstances() {
        GltfMeshPrimitive runtime = primitive(
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[0]
        );
        int indicesPerInstance = (int) (((GltfGunBodyRenderer.MAX_EMITTED_VERTICES / 2L) / 3L + 1L) * 3L);
        int[] indices = new int[indicesPerInstance];
        for (int index = 0; index < indices.length; index++) {
            indices[index] = index % 3;
        }
        GltfRenderPrimitive renderPrimitive = new GltfRenderPrimitive(
                runtime,
                indices,
                new float[0],
                new float[0],
                -1
        );
        GltfScene scene = new GltfScene(
                List.of(
                        new GltfNode("first", GltfNodeTransform.identity(), new int[0], 0, -1),
                        new GltfNode("second", GltfNodeTransform.identity(), new int[0], 0, -1)
                ),
                List.of(new GltfMesh(List.of(runtime))),
                List.of(),
                new int[]{0, 1}
        );
        ConvertedGltfAsset asset = new ConvertedGltfAsset(
                scene,
                List.of(new GltfRenderMesh("instanced", List.of(renderPrimitive), new float[0])),
                List.of(),
                List.of(),
                List.of(),
                List.of(new float[0], new float[0]),
                List.of(new float[0]),
                List.of(new GltfSceneData("scene", new int[]{0, 1})),
                0,
                0
        );
        Identifier modelId = Identifier.fromNamespaceAndPath("tacz", "models/gltf/oversized.glb");
        GunRenderModelConfig config = GSON.fromJson("""
                {
                  "type": "gltf",
                  "location": "tacz:models/gltf/oversized.glb"
                }
                """, GunRenderModelConfig.class);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GltfGunBodyRenderer(modelId, asset, new GltfModelManager(), 0L, config)
        );
        assertTrue(exception.getMessage().contains("emitted geometry budget"));
    }

    @Test
    void preservesParentTransformForExternalJointWhoseIndexPrecedesItsParent() {
        GltfScene scene = new GltfScene(
                List.of(
                        new GltfNode("external_child", GltfNodeTransform.translation(0.0f, 2.0f, 0.0f),
                                new int[0], -1, -1),
                        new GltfNode("external_parent", GltfNodeTransform.translation(3.0f, 0.0f, 0.0f),
                                new int[]{0}, -1, -1),
                        new GltfNode("active_root", GltfNodeTransform.identity(), new int[0], -1, -1)
                ),
                List.of(),
                List.of(),
                new int[]{2}
        );
        ConvertedGltfAsset asset = new ConvertedGltfAsset(
                scene,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new float[0], new float[0], new float[0]),
                List.of(),
                List.of(new GltfSceneData("selected", new int[]{2})),
                0,
                0
        );

        GltfGunBodyPose.Frame frame = GltfGunBodyPose.sample(asset, null, 0.0);

        assertArrayEquals(
                new float[]{3.0f, 2.0f, 0.0f},
                vector(frame.worldTransform(0).getTranslation(new Vector3f())),
                EPSILON
        );
        assertTrue(frame.active(2));
        assertEquals(false, frame.active(0));
    }

    @Test
    void rejectsUnsupportedSamplersAndComputesDeterministicTickTime() {
        assertDoesNotThrow(() -> GltfGunBodyRenderer.validateSampler(new GltfSamplerData(
                GltfConstants.GL_LINEAR,
                GltfConstants.GL_LINEAR,
                GltfConstants.GL_REPEAT,
                GltfConstants.GL_REPEAT
        )));
        assertDoesNotThrow(() -> GltfGunBodyRenderer.validateSampler(
                GltfSamplerData.DEFAULT
        ));
        assertDoesNotThrow(() -> GltfGunBodyRenderer.validateSampler(new GltfSamplerData(
                GltfConstants.GL_NEAREST,
                GltfConstants.GL_LINEAR,
                GltfConstants.GL_REPEAT,
                GltfConstants.GL_REPEAT
        )));
        assertDoesNotThrow(() -> GltfGunBodyRenderer.validateSampler(new GltfSamplerData(
                GltfConstants.GL_LINEAR,
                GltfConstants.GL_LINEAR,
                GltfConstants.GL_CLAMP_TO_EDGE,
                GltfConstants.GL_REPEAT
        )));
        for (int min : new int[]{9984, 9985, -1}) {
            assertThrows(IllegalArgumentException.class, () -> GltfGunBodyRenderer.validateSampler(
                    new GltfSamplerData(9729, min, 10497, 10497)));
        }
        assertThrows(IllegalArgumentException.class, () -> GltfGunBodyRenderer.validateSampler(
                new GltfSamplerData(9729, 9729, 33648, 10497)));
        assertEquals(GltfSamplerData.DEFAULT, GltfPbrSamplers.DEFAULT.baseColor());
        assertEquals(GltfSamplerData.DEFAULT, GltfPbrMaterial.opaque(null).samplers().normal());

        assertEquals(0.25, GltfGunBodyPose.animationTimeSeconds(25, 0.0f, 1.0f, 1.0f, true), 1.0E-8);
        assertEquals(1.0, GltfGunBodyPose.animationTimeSeconds(100, 0.0f, 1.0f, 1.0f, false), 1.0E-8);
        assertThrows(
                IllegalArgumentException.class,
                () -> GltfGunBodyPose.animationTimeSeconds(1, Float.NaN, 1.0f, 1.0f, true)
        );
    }

    @Test
    void sortsBlendTrianglesBackToFrontInCapturedModelViewSpace() {
        GltfMeshPrimitive runtime = primitive(
                new float[]{
                        100.0f, 0.0f, -1.0f,
                        101.0f, 0.0f, -1.0f,
                        100.0f, 1.0f, -1.0f,
                        0.0f, 0.0f, -5.0f,
                        1.0f, 0.0f, -5.0f,
                        0.0f, 1.0f, -5.0f
                },
                new float[0]
        );
        GltfRenderPrimitive render = new GltfRenderPrimitive(
                runtime,
                new int[]{0, 1, 2, 3, 4, 5},
                new float[0],
                new float[0],
                -1
        );
        GltfPreparedGeometry geometry = GltfPreparedGeometry.create(
                render,
                GltfVertexDeformer.deform(runtime, null, null, new float[0]),
                new Matrix4f(),
                false
        ).sortedBackToFront(new Matrix4f());

        assertEquals(-5.0f, geometry.positions()[2], EPSILON);
        assertEquals(-1.0f, geometry.positions()[11], EPSILON);
    }

    private static GltfMeshPrimitive primitive(float[] positions, float[] normals) {
        return new GltfMeshPrimitive(
                positions,
                normals,
                new float[0],
                new int[0],
                new float[0],
                List.of(),
                new GltfMaterialReference("default")
        );
    }

    private static ConvertedGltfAsset staticTriangleAsset() {
        return staticTriangleAsset(List.of());
    }

    private static ConvertedGltfAsset staticTriangleAsset(List<GltfImageData> images) {
        GltfMeshPrimitive runtime = primitive(
                new float[]{
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[0]
        );
        GltfRenderPrimitive render = new GltfRenderPrimitive(
                runtime,
                new int[]{0, 1, 2},
                new float[0],
                new float[0],
                -1
        );
        GltfScene scene = new GltfScene(
                List.of(new GltfNode("root", GltfNodeTransform.identity(), new int[0], 0, -1)),
                List.of(new GltfMesh(List.of(runtime))),
                List.of(),
                new int[]{0}
        );
        return new ConvertedGltfAsset(
                scene,
                List.of(new GltfRenderMesh("mesh", List.of(render), new float[0])),
                List.of(),
                images,
                List.of(),
                List.of(new float[0]),
                List.of(new float[0]),
                List.of(new GltfSceneData("scene", new int[]{0})),
                0,
                0
        );
    }

    private static float[] vector(Vector3f vector) {
        return new float[]{vector.x, vector.y, vector.z};
    }

    private static final class CapturingVertexConsumer implements VertexConsumer {
        private int vertexCount;
        private int[] lastColor = new int[4];
        private float[] lastUv = new float[2];
        private int[] lastOverlay = new int[2];
        private int[] lastLight = new int[2];
        private float[] lastNormal = new float[3];

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertexCount++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            lastColor = new int[]{red, green, blue, alpha};
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return setColor(
                    color >> 16 & 255,
                    color >> 8 & 255,
                    color & 255,
                    color >>> 24
            );
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            lastUv = new float[]{u, v};
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            lastOverlay = new int[]{u, v};
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            lastLight = new int[]{u, v};
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            lastNormal = new float[]{x, y, z};
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
