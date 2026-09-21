package com.tacz.guns.client.model;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.functional.TextShowRender;
import com.tacz.guns.client.renderer.item.FirstPersonHandSway;
import com.tacz.guns.client.resource.pojo.display.gun.TextShow;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import com.tacz.guns.util.RenderHelper;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class ScopeLegacyEntryTest {
    @Test
    void publicAcceleratedGunEntryDelegatesOnceWithoutChangingThePose() {
        PoseStack pose = new PoseStack();
        pose.translate(1, 2, 3);
        int[] calls = {0};
        BedrockGunModel model = new BedrockGunModel(pojo(), BedrockVersion.NEW) {
            @Override
            public void render(PoseStack actualPose, ItemStack item, ItemDisplayContext context,
                               RenderType renderType, int light, int overlay) {
                calls[0]++;
                assertSame(pose, actualPose);
                assertEquals(2.0F, actualPose.last().pose().m31());
                assertEquals(ItemDisplayContext.FIRST_PERSON_LEFT_HAND, context);
                assertEquals(17, light);
                assertEquals(23, overlay);
            }
        };
        model.renderAccelerated(pose, ItemStack.EMPTY, ItemDisplayContext.FIRST_PERSON_LEFT_HAND, null, 17, 23);
        assertEquals(1, calls[0]);
    }

    @Test
    void publicAcceleratedAttachmentEntryKeepsTheAlreadyOffsetPose() {
        PoseStack pose = new PoseStack();
        pose.translate(0, -1.5, 0);
        int[] calls = {0};
        BedrockAttachmentModel model = new BedrockAttachmentModel(pojo(), BedrockVersion.NEW) {
            @Override
            public void render(ItemStack attachment, ItemStack gun, PoseStack actualPose,
                               ItemDisplayContext context, RenderType renderType, int light, int overlay) {
                calls[0]++;
                assertSame(pose, actualPose);
                assertEquals(-1.5F, actualPose.last().pose().m31());
                assertEquals(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, context);
                assertEquals(11, light);
                assertEquals(13, overlay);
            }
        };
        model.renderBothAccelerated(pose, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, 11, 13);
        assertEquals(1, calls[0]);
    }

    @Test
    void nonFirstPersonContextsNeverEnterTheOpticalStencilPass() {
        BedrockAttachmentModel model = new BedrockAttachmentModel(pojo(), BedrockVersion.NEW);
        model.setIsScope(true);
        for (ItemDisplayContext context : ItemDisplayContext.values()) {
            if (!context.firstPerson()) {
                assertFalse(ScopeStencilFeatureRenderer.submitLegacyAttachment(model, null, ItemStack.EMPTY,
                        new PoseStack(), null, context, null, -1, 0, 0));
            }
        }
    }

    @Test
    void integratedSemanticsRunOnceForNativeAndLegacyButNotMeshOrRejectedScopes() throws Exception {
        var collector = (SubmitNodeCollector) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected collector invocation: " + method.getName());
                });
        for (boolean legacy : new boolean[]{false, true}) {
            BedrockGunModel gun = new BedrockGunModel(pojo(), BedrockVersion.NEW);
            int[] calls = {0};
            BedrockAttachmentModel attachment = new BedrockAttachmentModel(pojo(), BedrockVersion.NEW) {
                @Override
                public void submitInstalledSemantics(ItemStack attachmentItem, ItemStack gunItem, PoseStack pose,
                                                     ItemDisplayContext context, int light, int overlay) {
                    calls[0]++;
                    assertSame(collector, RenderHelper.currentSubmitNodeCollector());
                    assertEquals(17, light);
                    assertEquals(23, overlay);
                    if (legacy) {
                        assertEquals(0.0F, gun.getRootNode().offsetX);
                    } else {
                        assertNotEquals(0.0F, gun.getRootNode().offsetX);
                    }
                }
            };
            var constructor = FirstPersonHandSway.class.getDeclaredConstructor(float.class, float.class, float.class, float.class);
            constructor.setAccessible(true);
            FirstPersonHandSway sway = legacy ? null : constructor.newInstance(0, 0, 3, 4);
            semanticSubmit(gun, attachment, sway, false, legacy).submitIntegratedSemantics(collector);
            assertEquals(1, calls[0]);
            assertEquals(0.0F, gun.getRootNode().offsetX);
            assertEquals(0.0F, gun.getRootNode().offsetY);
            assertNull(RenderHelper.currentSubmitNodeCollector());
            semanticSubmit(gun, attachment, sway, true, legacy).submitIntegratedSemantics(collector);
            semanticSubmit(null, attachment, null, false, legacy).submitIntegratedSemantics(collector);
            assertFalse(ScopeStencilFeatureRenderer.submitLegacyAttachment(attachment, null, ItemStack.EMPTY,
                    new PoseStack(), collector, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, -1, 0, 0));
            assertEquals(1, calls[0]);
        }
    }

    @Test
    void opticalSemanticsCollectTextShowNodesOnceWithTheOriginalLightAndOverlay() {
        BedrockAttachmentModel model = new BedrockAttachmentModel(pojo(), BedrockVersion.NEW);
        int[] calls = {0};
        model.setFunctionalRenderer("text_node", ignored -> new TextShowRender(model, new TextShow(), ItemStack.EMPTY) {
            @Override
            public void render(PoseStack pose, VertexConsumer buffer, ItemDisplayContext context, int light, int overlay) {
                calls[0]++;
                assertTrue(RenderHelper.isCollectingDeferredFunctionalRenderers());
                assertEquals(37, light);
                assertEquals(41, overlay);
            }
        });
        model.submitInstalledSemantics(null, ItemStack.EMPTY, new PoseStack(), ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        assertEquals(0, calls[0]);
        model.submitInstalledSemantics(null, ItemStack.EMPTY, new PoseStack(), ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, 37, 41);
        assertEquals(1, calls[0]);
        assertFalse(RenderHelper.isCollectingDeferredFunctionalRenderers());
    }

    private static ScopeStencilFeatureRenderer.ScopeSubmit semanticSubmit(BedrockGunModel gun, BedrockAttachmentModel attachment,
                                                                           FirstPersonHandSway sway, boolean mesh, boolean legacy) {
        return new ScopeStencilFeatureRenderer.ScopeSubmit(gun, attachment, null, ItemStack.EMPTY, null,
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, null, null, null,
                new Matrix4f(), new Matrix3f(), sway, false, mesh, -1, 17, 23, legacy);
    }

    private static BedrockModelPOJO pojo() {
        return new Gson().fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"scope_pos","parent":"root","pivot":[0,24,0]},
                    {"name":"text_node","parent":"root","pivot":[0,24,0]},
                    {"name":"scope_body","parent":"root","pivot":[0,24,0]},
                    {"name":"ocular","parent":"root","pivot":[0,24,0]}]}]}
                """, BedrockModelPOJO.class);
    }
}
