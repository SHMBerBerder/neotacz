package com.tacz.guns.client.model;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScopeMeshSemanticsTest {
    @Test
    void meshOpticsMustBeReadyBeforeAnyAppearanceCanBeSubmitted() {
        Identifier texture = Identifier.fromNamespaceAndPath("test", "scope.png");
        BedrockAttachmentModel model = model();
        model.setIsScope(true);
        assertFalse(ScopeStencilFeatureRenderer.canStageMeshOptics(null, texture));
        assertFalse(ScopeStencilFeatureRenderer.canStageMeshOptics(model, null));
        assertTrue(ScopeStencilFeatureRenderer.canStageMeshOptics(model, texture));
        model.scopeBodyPath = null;
        assertTrue(ScopeStencilFeatureRenderer.canStageMeshOptics(model, texture));
        model.setIsScope(false);
        assertFalse(ScopeStencilFeatureRenderer.canStageMeshOptics(model, texture));
        model.setIsSight(true);
        assertTrue(ScopeStencilFeatureRenderer.canStageMeshOptics(model, texture));
        model.ocularNodePaths.clear();
        assertFalse(ScopeStencilFeatureRenderer.canStageMeshOptics(model, texture));
    }

    @Test
    void meshAppearanceRejectsOnlyTheLegacyHousingAndRetainsOpticalPaths() {
        BedrockAttachmentModel model = model();
        var mesh = submit(model, true, new PoseStack());
        assertFalse(mesh.rendersAttachmentPart(model.scopeBodyPath));
        assertFalse(mesh.rendersAttachmentPart(model.ocularRingPath));
        assertTrue(mesh.rendersAttachmentPart(model.ocularNodePaths.getFirst()));
        assertTrue(mesh.rendersAttachmentPart(model.divisionNodePaths.getFirst()));
        assertTrue(mesh.rendersAttachmentPart(model.scopeViewPaths.getFirst()));
        assertTrue(mesh.rendersAttachmentPart(model.laserBeamPaths.getFirst()));
        var legacy = submit(model, false, new PoseStack());
        assertTrue(legacy.rendersAttachmentPart(model.scopeBodyPath));
        assertTrue(legacy.rendersAttachmentPart(model.ocularRingPath));
    }

    @Test
    void meshScopeCanUseAnOpticalOnlyRigButCannotInventMissingOcularSemantics() {
        BedrockAttachmentModel model = model();
        model.setIsScope(true);
        assertTrue(ScopeStencilFeatureRenderer.canStageIntegratedPass(model));
        model.scopeBodyPath = null;
        assertFalse(ScopeStencilFeatureRenderer.canStageIntegratedPass(model));
        assertTrue(ScopeStencilFeatureRenderer.canStageIntegratedPass(model, true));
        model.ocularNodePaths.clear();
        assertFalse(ScopeStencilFeatureRenderer.canStageIntegratedPass(model, true));
        model.setIsScope(false);
        model.setIsSight(true);
        assertFalse(ScopeStencilFeatureRenderer.canStageIntegratedPass(model, true));
    }

    @Test
    void attachmentOnlyOpticalPassKeepsTheAlreadyResolvedMountPoseWithoutAnotherYOffset() {
        PoseStack pose = new PoseStack();
        pose.mulPose(new Matrix4f().translation(3, 4, 5).rotateXYZ(0.2F, -0.5F, 0.7F).scale(-2, -3, 4));
        pose.translate(0, -1.5, 0);
        Matrix4f expected = new Matrix4f(pose.last().pose());
        Matrix3f expectedNormal = new Matrix3f(pose.last().normal());
        var submit = submit(model(), true, pose);
        pose.last().pose().zero();
        assertNull(submit.gunModel());
        assertFalse(submit.renderHand());
        assertTrue(expected.equals(submit.toScopePoseStack().last().pose(), 1.0E-6F));
        assertTrue(expectedNormal.equals(submit.toScopePoseStack().last().normal(), 1.0E-6F));
        submit.toScopePoseStack().last().pose().zero();
        assertTrue(expected.equals(submit.toScopePoseStack().last().pose(), 1.0E-6F));
    }

    private static ScopeStencilFeatureRenderer.ScopeSubmit submit(BedrockAttachmentModel model,
                                                                  boolean meshAppearance, PoseStack pose) {
        return new ScopeStencilFeatureRenderer.ScopeSubmit(null, model, null, null, null,
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, null, null, null, null,
                new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), null,
                false, meshAppearance, 0, 0, 0);
    }

    private static BedrockAttachmentModel model() {
        return new BedrockAttachmentModel(new Gson().fromJson("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},
                    {"name":"scope_body","parent":"root","pivot":[0,24,0]},
                    {"name":"ocular_ring","parent":"root","pivot":[0,24,0]},
                    {"name":"scope_view","parent":"root","pivot":[0,24,0]},
                    {"name":"ocular","parent":"root","pivot":[0,24,0]},
                    {"name":"division","parent":"root","pivot":[0,24,0]},
                    {"name":"laser_beam","parent":"root","pivot":[0,24,0]}]}]}
                """, BedrockModelPOJO.class), BedrockVersion.NEW);
    }
}
