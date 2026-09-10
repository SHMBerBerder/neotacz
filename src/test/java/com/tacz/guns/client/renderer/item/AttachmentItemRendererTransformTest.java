package com.tacz.guns.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.gltf.render.GltfGuiIconRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentItemRendererTransformTest {
    @Test
    void guiFallbackRestoresTheCallerPoseAfterSubmissionAndCollectorFailure() {
        AttachmentItemRenderer renderer = new AttachmentItemRenderer();
        GltfGuiIconRenderer.Snapshot snapshot = new GltfGuiIconRenderer.Snapshot(new Object(), null,
                Identifier.fromNamespaceAndPath("test", "fallback.png"));
        for (boolean fail : new boolean[]{false, true}) {
            PoseStack pose = new PoseStack();
            pose.mulPose(new Matrix4f().translation(3, -2, 7).rotateXYZ(0.2F, -0.4F, 0.1F).scale(2, 3, 4));
            var originalFrame = pose.last();
            Matrix4f expected = new Matrix4f(originalFrame.pose());
            var expectedNormal = new org.joml.Matrix3f(originalFrame.normal());
            AtomicInteger submissions = new AtomicInteger();
            SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                    SubmitNodeCollector.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("submitCustomGeometry")) {
                            submissions.incrementAndGet();
                            if (fail) throw new IllegalStateException("test collector failure");
                        }
                        return null;
                    });
            if (fail) {
                assertThrows(IllegalStateException.class, () -> renderer.submitGuiIcon(snapshot, pose, collector, 0, 0));
            } else {
                renderer.submitGuiIcon(snapshot, pose, collector, 0, 0);
            }
            assertEquals(1, submissions.get());
            assertSame(originalFrame, pose.last());
            assertTrue(expected.equals(pose.last().pose(), 1.0E-6F));
            assertTrue(expectedNormal.equals(pose.last().normal(), 1.0E-6F));
        }
    }

    @Test
    void standaloneMeshConversionIsCenteredAndUprightWithoutTheLegacyTwentyFourPixelOffset() {
        for (ItemDisplayContext context : new ItemDisplayContext[]{ItemDisplayContext.GROUND,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND}) {
            PoseStack pose = new PoseStack();
            AttachmentItemRenderer.applyMeshItemTransform(context, pose);
            // submitAttachment converts from the same Bedrock XY basis used by mounted anchors.
            pose.scale(-0.75F, -0.75F, 0.75F);
            Matrix4f expected = new Matrix4f().translation(0.5F, 0.5F, 0.5F).scale(0.75F);
            assertTrue(expected.equals(pose.last().pose(), 1.0E-6F), context.toString());
            assertTrue(new Vector3f(0, 1, 0).mulPosition(pose.last().pose())
                    .equals(new Vector3f(0.5F, 1.25F, 0.5F), 1.0E-6F));
        }
    }

    @Test
    void fixedOrientationAndExistingWorldTransformAreComposedWithoutChangingTheAttachmentOrigin() {
        PoseStack pose = new PoseStack();
        Matrix4f outer = new Matrix4f().translation(3, -2, 7).rotateXYZ(0.2F, -0.4F, 0.1F).scale(2, 3, 4);
        pose.mulPose(outer);
        AttachmentItemRenderer.applyMeshItemTransform(ItemDisplayContext.FIXED, pose);
        pose.scale(-2, -2, 2);
        Matrix4f expected = new Matrix4f(outer).translate(0.5F, 0.5F, 0.5F)
                .scale(-1, -1, 1).rotateY((float) -Math.PI / 2).scale(-2, -2, 2);
        assertTrue(expected.equals(pose.last().pose(), 1.0E-6F));
        assertTrue(new Vector3f().mulPosition(pose.last().pose())
                .equals(new Vector3f(0.5F).mulPosition(outer), 1.0E-6F));
    }
}
