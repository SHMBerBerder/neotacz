package com.tacz.guns.client.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.tacz.guns.client.debug.ScopeRenderDebug;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.gltf.render.GunBodyRenderer;
import com.tacz.guns.client.renderer.item.FirstPersonHandSway;
import com.tacz.guns.client.renderer.item.FirstPersonArmSubmitter;
import com.tacz.guns.util.RenderHelper;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Draw-time first-person scope pass. The retained submit pipeline only records vertices in prepareGroup;
 * all stencil-dependent draws are executed here in the same ordered feature group.
 */
public final class ScopeStencilFeatureRenderer implements FeatureRenderer<ScopeStencilFeatureRenderer.ScopeSubmit> {
    public static final FeatureRendererType<ScopeSubmit> TYPE = FeatureRendererType.create("tacz_scope_stencil");
    public static final int ATTACHMENT_MESH_ORDER = -970_000;
    private static final int ATTACHMENT_OPTICS_ORDER = -960_000;

    private static final int APERTURE_SEGMENTS = 90;
    private static final int ORDER_CLEAR = -950_000;
    private static final int ORDER_RING = -940_000;
    private static final int ORDER_SCOPE_OCULAR_WRITE = -930_000;
    private static final int ORDER_SCOPE_BODY = -920_000;
    private static final int ORDER_SIGHT_OCULAR_WRITE = -910_000;
    private static final int ORDER_APERTURE = -900_000;
    private static final int ORDER_OCULAR_MASK = -890_000;
    private static final int ORDER_DIVISION = -880_000;
    private static final int ORDER_BASE_MODEL = -870_000;
    private static final int ORDER_GUN_BODY = -860_000;
    private static final int ORDER_HANDS = -859_000;
    private static final float DEBUG_APERTURE_SCALE = Float.parseFloat(
            System.getProperty("tacz.debug.scope.apertureScale", "1.0"));

    private final List<Group> groups = new ArrayList<>();

    /** An attachment-local pass cannot stencil-clip a separately submitted mesh gun body. */
    public static boolean submitAttachmentSemantics(BedrockAttachmentModel model, ItemStack attachmentItem,
                                                     ItemStack gunItem, PoseStack attachmentPose,
                                                     SubmitNodeCollector collector, ItemDisplayContext context,
                                                     Identifier texture, boolean meshAppearance,
                                                     int activeViewIndex, int light, int overlay) {
        if (!context.firstPerson() || !canStageIntegratedPass(model, meshAppearance)) return false;
        if (!(collector.order(ATTACHMENT_OPTICS_ORDER) instanceof SubmitNodeCollection collection)) return false;
        RenderType renderType = RenderTypes.entityCutout(texture);
        collection.solid.submit(new ScopeSubmit(null, model, null, gunItem, attachmentItem, context,
                renderType, renderType, texture, texture, new Matrix4f(attachmentPose.last().pose()),
                new Matrix3f(attachmentPose.last().normal()), null, false, meshAppearance,
                activeViewIndex, light, overlay));
        return true;
    }

    static boolean submitLegacyAttachment(BedrockAttachmentModel model, @Nullable ItemStack attachmentItem,
                                           ItemStack gunItem, PoseStack attachmentPose, SubmitNodeCollector collector,
                                           ItemDisplayContext context, RenderType renderType, int activeViewIndex,
                                           int light, int overlay) {
        if (!context.firstPerson() || !canStageIntegratedPass(model)) return false;
        if (!(collector.order(ATTACHMENT_OPTICS_ORDER) instanceof SubmitNodeCollection collection)) return false;
        // Only the stencil template uses this identifier; all bound textures come from renderType.prepare().
        Identifier templateTexture = MissingTextureAtlasSprite.getLocation();
        collection.solid.submit(new ScopeSubmit(null, model, null, gunItem, attachmentItem, context,
                renderType, renderType, templateTexture, templateTexture,
                new Matrix4f(attachmentPose.last().pose()), new Matrix3f(attachmentPose.last().normal()),
                null, false, false, activeViewIndex, light, overlay, true));
        return true;
    }

    @Override
    public void prepareGroup(FeatureFrameContext context, List<ScopeSubmit> submits, boolean strictlyOrdered) {
        Group group = new Group(context.stagedVertexBuffer(), context.font());
        for (ScopeSubmit submit : submits) {
            group.currentBody = submit.customBody;
            try {
                buildSubmit(group, submit);
            } finally {
                group.currentBody = null;
            }
        }
        for (DrawStage stage : group.stages) {
            if (stage.draw != null) {
                context.stagedVertexBuffer().requestIndexCount(stage.draw);
            }
        }
        groups.add(group);
    }

    @Override
    public void executeGroup(FeatureFrameContext context, @Nullable OitStage oitStage, RenderPass renderPass,
                             int groupIndex, List<ScopeSubmit> submits, boolean strictlyOrdered) {
        if (oitStage != null) {
            throw new IllegalStateException("Scope stencil submissions must stay in the ordered solid phase");
        }
        Group group = groups.get(groupIndex);
        ScissorState scissor = new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        try {
            for (DrawStage stage : group.stages) {
                switch (stage.control) {
                    case CLEAR_STENCIL -> clearStencil(renderPass);
                    case DRAW -> {
                        if (stage.owner == null || stage.owner.isAvailable()) drawStage(context, stage, renderPass);
                    }
                }
            }
        } finally {
            renderPass.disableScissor();
            if (scissor.enabled()) {
                renderPass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
        }
    }

    @Override
    public void finishExecute(FeatureFrameContext context) {
        groups.clear();
    }

    private static void drawStage(FeatureFrameContext context, DrawStage stage, RenderPass renderPass) {
        StagedVertexBuffer.ExecuteInfo executeInfo = context.stagedVertexBuffer().getExecuteInfo(stage.draw);
        if (executeInfo != null) {
            // PreparedRenderType only enables scissors; it does not disable a previous draw's scissor.
            renderPass.disableScissor();
            stage.renderType.drawFromBuffer(executeInfo, renderPass);
        }
    }

    private static void clearStencil(RenderPass renderPass) {
        // The hand pass may use a separate HUD depth target. Clear its stencil without ending
        // that pass, touching world depth, or relying on an OpenGL framebuffer binding.
        renderPass.disableScissor();
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(ScopeStencilRenderTypes.clearStencilPipeline()));
        renderPass.draw(3, 1, 0, 0);
    }

    private static void buildSubmit(Group group, ScopeSubmit submit) {
        if (!submit.isAvailable()) return;
        if (submit.extras != null) {
            submit.extras.withFrameState(() -> buildSubmitFromFrame(group, submit));
        } else {
            buildSubmitFromFrame(group, submit);
        }
    }

    private static void buildSubmitFromFrame(Group group, ScopeSubmit submit) {
        BedrockGunModel gunModel = submit.gunModel;
        if (gunModel == null) {
            buildSubmitWithSway(group, submit);
            return;
        }
        boolean previousRenderHand = gunModel.getRenderHand();
        gunModel.setRenderHand(submit.renderHand);
        try {
            if (submit.handSway == null) {
                buildSubmitWithSway(group, submit);
            } else {
                submit.handSway.withTemporaryModelSway(gunModel.getRootNode(), () -> buildSubmitWithSway(group, submit));
            }
        } finally {
            gunModel.setRenderHand(previousRenderHand);
            if (submit.handSway != null && submit.extras == null) {
                gunModel.cleanAnimationTransform();
            }
        }
    }

    private static void buildSubmitWithSway(Group group, ScopeSubmit submit) {
        PoseStack scopePose = submit.toScopePoseStack();
        submit.attachmentModel.prepareInstalledRenderState(submit.attachmentItem, submit.gunItem);
        Set<BedrockPart> specialLeaves = Collections.newSetFromMap(new IdentityHashMap<>());
        float aimingProgress = submit.attachmentModel.getClientAimingProgress();
        float apertureRadius = submit.attachmentModel.getScopeApertureRadius(aimingProgress);
        boolean comboScope = submit.attachmentModel.isScope() && submit.attachmentModel.isSight();
        boolean activeViewIsScopeOcular = !comboScope
                || submit.attachmentModel.isScopeOcularViewIndex(submit.activeScopeViewIndex);
        String gunClipMode = submit.gunModel == null ? "none"
                : gunClipMode(submit.attachmentModel, activeViewIsScopeOcular);

        stage("stencil_clear", submit, false, aimingProgress, apertureRadius, "GL_ALWAYS", "GL_CLEAR",
                ORDER_CLEAR, null, 0, -1, "stencil_clear", gunClipMode, true, "");
        group.control(Control.CLEAR_STENCIL);

        if (submit.attachmentModel.isScope()) {
            addPartStage(group, submit, "ocular_ring", scopePose, submit.attachmentRenderType, submit.attachmentModel.ocularRingPath,
                    ORDER_RING, "GL_ALWAYS", "GL_KEEP", 0, -1, "ocular_ring", gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, true);
            if (comboScope) {
                addOcularStencil(group, submit, scopePose, true, ORDER_SCOPE_OCULAR_WRITE, gunClipMode,
                        aimingProgress, apertureRadius, specialLeaves);
                addPartStage(group, submit, "scope_body", scopePose,
                        ScopeStencilRenderTypes.entityEqual0(submit.attachmentTexture),
                        submit.attachmentModel.scopeBodyPath, ORDER_SCOPE_BODY, "GL_EQUAL:0", "GL_KEEP", 0, -1,
                        "scope_body", gunClipMode, aimingProgress, apertureRadius, specialLeaves, true);
                addOcularStencil(group, submit, scopePose, false, ORDER_SIGHT_OCULAR_WRITE, gunClipMode,
                        aimingProgress, apertureRadius, specialLeaves);
                if (activeViewIsScopeOcular) {
                    addOcularAndDivision(group, submit, scopePose, true, true, gunClipMode,
                            aimingProgress, apertureRadius, specialLeaves);
                } else {
                    addInactiveScopeLens(group, submit, scopePose, gunClipMode,
                            aimingProgress, apertureRadius, specialLeaves);
                    addSightDivisionStages(group, submit, scopePose, gunClipMode,
                            aimingProgress, apertureRadius, specialLeaves);
                }
            } else {
                addOcularStencil(group, submit, scopePose, false, ORDER_SCOPE_OCULAR_WRITE, gunClipMode,
                        aimingProgress, apertureRadius, specialLeaves);
                addPartStage(group, submit, "scope_body", scopePose,
                        ScopeStencilRenderTypes.entityEqual0(submit.attachmentTexture),
                        submit.attachmentModel.scopeBodyPath, ORDER_SCOPE_BODY, "GL_EQUAL:0", "GL_KEEP", 0, -1,
                        "scope_body", gunClipMode, aimingProgress, apertureRadius, specialLeaves, true);
                addOcularAndDivision(group, submit, scopePose, false, true, gunClipMode,
                        aimingProgress, apertureRadius, specialLeaves);
            }
        } else if (submit.attachmentModel.isSight()) {
            addOcularStencil(group, submit, scopePose, false, ORDER_SIGHT_OCULAR_WRITE, gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves);
            addDivisionOnly(group, submit, scopePose, gunClipMode, aimingProgress, apertureRadius, specialLeaves);
            addPartStage(group, submit, "scope_body_plain", scopePose, submit.attachmentRenderType,
                    submit.attachmentModel.scopeBodyPath, ORDER_SCOPE_BODY, "visible", "none", 0, -1,
                    "scope_body", gunClipMode, aimingProgress, apertureRadius, specialLeaves, true);
        }
        addScopeViewLeaves(submit.attachmentModel, specialLeaves);

        addBaseModel(group, submit, scopePose, aimingProgress, apertureRadius, gunClipMode, specialLeaves);
        addGunBody(group, submit, submit.toGunPoseStack(), aimingProgress, apertureRadius, gunClipMode);
        addGunExtras(group, submit, gunClipMode);
        addHands(group, submit, submit.toGunPoseStack(), aimingProgress, apertureRadius, gunClipMode);
        group.control(Control.CLEAR_STENCIL);
    }

    private static void addOcularStencil(Group group, ScopeSubmit submit, PoseStack scopePose, boolean scopeOcular,
                                         int order, String gunClipMode, float aimingProgress, float apertureRadius,
                                         Set<BedrockPart> specialLeaves) {
        BedrockAttachmentModel model = submit.attachmentModel;
        boolean submitted = false;
        for (int i = model.ocularNodePaths.size() - 1; i >= 0; i--) {
            if (scopeOcular != model.isScopeOcular.get(i)) {
                continue;
            }
            int ref = i + 1;
            addPartStage(group, submit, ocularPathKind(scopeOcular) + "_stencil_write", scopePose,
                    ScopeStencilRenderTypes.ocularWrite(submit.attachmentTexture, ref), model.ocularNodePaths.get(i),
                    orderedRefStage(order, i, model.ocularNodePaths.size()), "GL_GREATER:" + ref,
                    "GL_REPLACE", ref, i, ocularPathKind(scopeOcular), gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, false);
            submitted = true;
        }
        if (!submitted) {
            stage(ocularPathKind(scopeOcular) + "_stencil_write", submit, false, aimingProgress, apertureRadius,
                    "GL_GREATER", "GL_REPLACE", order, null, -1, -1, ocularPathKind(scopeOcular),
                    gunClipMode, false, "missing_ocular_path");
        }
    }

    private static void addSightDivisionStages(Group group, ScopeSubmit submit, PoseStack scopePose, String gunClipMode,
                                               float aimingProgress, float apertureRadius,
                                               Set<BedrockPart> specialLeaves) {
        BedrockAttachmentModel model = submit.attachmentModel;
        for (int i = 0; i < model.ocularNodePaths.size() && i < model.divisionNodePaths.size(); i++) {
            if (model.isScopeOcular.get(i)) {
                continue;
            }
            int ref = i + 1;
            addPartStage(group, submit, "division_sight", scopePose,
                    ScopeStencilRenderTypes.entityEqualRefNoDepth(submit.attachmentTexture, ref),
                    model.divisionNodePaths.get(i), orderedRefStage(ORDER_DIVISION, i, model.divisionNodePaths.size()),
                    "GL_EQUAL:" + ref, "GL_KEEP", ref, i, "division_sight", gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, true);
        }
    }

    private static void addInactiveScopeLens(Group group, ScopeSubmit submit, PoseStack scopePose, String gunClipMode,
                                             float aimingProgress, float apertureRadius,
                                             Set<BedrockPart> specialLeaves) {
        BedrockAttachmentModel model = submit.attachmentModel;
        for (int i = 0; i < model.ocularNodePaths.size(); i++) {
            if (!model.isScopeOcular.get(i)) {
                continue;
            }
            int ref = i + 1;
            addPartStage(group, submit, "ocular_inactive_scope_mask", scopePose,
                    ScopeStencilRenderTypes.entityEqualRefNoDepth(submit.attachmentTexture, ref),
                    model.ocularNodePaths.get(i), orderedRefStage(ORDER_OCULAR_MASK, i, model.ocularNodePaths.size()),
                    "GL_EQUAL:" + ref, "GL_KEEP", ref, i, "ocular_scope", gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, true);
        }
    }

    private static void addOcularAndDivision(Group group, ScopeSubmit submit, PoseStack scopePose, boolean selective,
                                             boolean renderScopeOverlay, String gunClipMode, float aimingProgress,
                                             float apertureRadius,
                                             Set<BedrockPart> specialLeaves) {
        BedrockAttachmentModel model = submit.attachmentModel;
        for (int i = 0; i < model.ocularNodePaths.size(); i++) {
            boolean scopeOcular = model.isScopeOcular.get(i);
            if (selective && !scopeOcular) {
                continue;
            }
            if (selective && scopeOcular) {
                if (!renderScopeOverlay || !isActiveOcular(submit, i)) {
                    continue;
                }
            }
            int ref = i + 1;
            Vector3f ocularCenter = model.getBedrockPartCenter(copyPose(scopePose), model.ocularNodePaths.get(i));
            float scaledApertureRadius = apertureRadius * DEBUG_APERTURE_SCALE;
            RenderType renderType = ScopeStencilRenderTypes.apertureInvert(ref);
            Group.StagedBuilder fanBuilder = group.vertexBuilderWithModelView(renderType);
            ScopeApertureFanSpace fanSpace = ScopeApertureFanSpace.capture(fanBuilder.modelView(), ocularCenter);
            float centerX = fanSpace.centerX();
            float centerY = fanSpace.centerY();
            String fanDebug = String.format(Locale.ROOT,
                    "center=%.5f,%.5f,%.5f;viewCenter=%.5f,%.5f,%.5f;fan=%.2f,%.2f;scale=%.3f",
                    ocularCenter.x(), ocularCenter.y(), ocularCenter.z(), fanSpace.viewCenterX(),
                    fanSpace.viewCenterY(), fanSpace.viewCenterZ(), centerX, centerY, DEBUG_APERTURE_SCALE);
            boolean submitted = fanSpace.valid();
            String blockedReason = submitted ? "" : fanSpace.blockedReason();
            stage("aperture_fan", submit, selective, aimingProgress, apertureRadius, "GL_EQUAL:" + ref,
                    "GL_INVERT", orderedRefStage(ORDER_APERTURE, i, model.ocularNodePaths.size()), renderType,
                    ref, i, ocularPathKind(scopeOcular), gunClipMode, submitted,
                    fanDebug + ";" + fanSpace.debug());
            ScopeRenderDebug.apertureFan(submit.attachmentItem, submit.gunItem, submit.transformType,
                    ref, i, ocularPathKind(scopeOcular), centerX, centerY,
                    scaledApertureRadius, fanSpace.matrixHash(), fanSpace.determinant(), submitted,
                    blockedReason);
            if (submitted) {
                writeApertureFan(fanBuilder.buffer(), fanSpace, centerX, centerY, scaledApertureRadius);
            }
        }

        for (int i = 0; i < model.ocularNodePaths.size() && i < model.divisionNodePaths.size(); i++) {
            int ref = i + 1;
            boolean scopeOcular = model.isScopeOcular.get(i);
            if (selective && scopeOcular) {
                if (!renderScopeOverlay || !isActiveOcular(submit, i)) {
                    continue;
                }
            }
            if (selective && !scopeOcular) {
                addPartStage(group, submit, "division_sight", scopePose,
                        ScopeStencilRenderTypes.entityEqualRefNoDepth(submit.attachmentTexture, ref),
                        model.divisionNodePaths.get(i), orderedRefStage(ORDER_DIVISION, i, model.divisionNodePaths.size()),
                        "GL_EQUAL:" + ref, "GL_KEEP", ref, i, "division_sight", gunClipMode,
                        aimingProgress, apertureRadius, specialLeaves, true);
                continue;
            }
            addPartStage(group, submit, "ocular_visible_mask", scopePose,
                    ScopeStencilRenderTypes.entityEqualRefNoDepth(submit.attachmentTexture, ref),
                    model.ocularNodePaths.get(i), orderedRefStage(ORDER_OCULAR_MASK, i, model.ocularNodePaths.size()),
                    "GL_EQUAL:" + ref, "GL_KEEP", ref, i, ocularPathKind(model.isScopeOcular.get(i)), gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, true);
            int invertedRef = (~ref) & 0xFF;
            addPartStage(group, submit, "division_scope", scopePose,
                    ScopeStencilRenderTypes.entityEqualInvertedRefNoDepth(submit.attachmentTexture, ref),
                    model.divisionNodePaths.get(i), orderedRefStage(ORDER_DIVISION, i, model.divisionNodePaths.size()),
                    "GL_EQUAL:" + invertedRef, "GL_KEEP", invertedRef, i, "division_scope", gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, true);
        }
    }

    private static void addDivisionOnly(Group group, ScopeSubmit submit, PoseStack scopePose, String gunClipMode,
                                        float aimingProgress, float apertureRadius, Set<BedrockPart> specialLeaves) {
        BedrockAttachmentModel model = submit.attachmentModel;
        for (int i = 0; i < model.divisionNodePaths.size(); i++) {
            int ref = i + 1;
            addPartStage(group, submit, "division_sight", scopePose,
                    ScopeStencilRenderTypes.entityEqualRefNoDepth(submit.attachmentTexture, ref),
                    model.divisionNodePaths.get(i), orderedRefStage(ORDER_DIVISION, i, model.divisionNodePaths.size()),
                    "GL_EQUAL:" + ref, "GL_KEEP", ref, i, "division_sight", gunClipMode,
                    aimingProgress, apertureRadius, specialLeaves, true);
        }
    }

    private static boolean isActiveOcular(ScopeSubmit submit, int ocularIndex) {
        return submit.activeScopeViewIndex < 0 || submit.activeScopeViewIndex == ocularIndex;
    }

    private static void addBaseModel(Group group, ScopeSubmit submit, PoseStack scopePose, float aimingProgress,
                                     float apertureRadius, String gunClipMode, Set<BedrockPart> specialLeaves) {
        if (submit.meshAppearance) return;
        stage("base_model", submit, false, aimingProgress, apertureRadius, "none", "none",
                ORDER_BASE_MODEL, submit.attachmentRenderType, -1, -1, "base_model", gunClipMode, true, "");
        VertexConsumer buffer = group.vertexBuilder(submit.attachmentRenderType);
        submit.attachmentModel.renderBaseModelToBuffer(copyPose(scopePose), submit.transformType, buffer,
                submit.light, submit.overlay, specialLeaves);
    }

    private static void addGunBody(Group group, ScopeSubmit submit, PoseStack gunPose, float aimingProgress,
                                   float apertureRadius, String gunClipMode) {
        if (submit.gunModel == null) return;
        RenderType renderType = switch (gunClipMode) {
            case "equal0" -> ScopeStencilRenderTypes.entityEqual0(submit.gunTexture);
            case "greater127" -> ScopeStencilRenderTypes.entityGreater127(submit.gunTexture);
            default -> submit.gunRenderType;
        };
        String stencilFunc = switch (gunClipMode) {
            case "equal0" -> "GL_EQUAL:0";
            case "greater127" -> "GL_GREATER:127";
            default -> "none";
        };
        if (submit.customBody != null) {
            stage("gun_body_pbr", submit, false, aimingProgress, apertureRadius, stencilFunc, "GL_KEEP",
                    ORDER_GUN_BODY, null, -1, -1, "gun_body", gunClipMode, true, "");
            submit.customBody.emit(original -> gunClipMode.equals("none") ? group.vertexBuilder(original)
                    : group.vertexBuilder(original, renderType, true));
            return;
        }
        stage("gun_body", submit, false, aimingProgress, apertureRadius, stencilFunc, "GL_KEEP",
                ORDER_GUN_BODY, renderType, -1, -1, "gun_body", gunClipMode, true, "");
        VertexConsumer buffer = submit.preserveProvidedRenderTypes && renderType != submit.gunRenderType
                ? group.vertexBuilder(submit.gunRenderType, renderType, true)
                : group.vertexBuilder(renderType);
        RenderHelper.withDeferredRenderersSuppressed(() ->
                submit.gunModel.renderGunBodyToBuffer(copyPose(gunPose), submit.transformType,
                        buffer, submit.light, submit.overlay));
    }

    private static void addGunExtras(Group group, ScopeSubmit submit, String gunClipMode) {
        if (submit.gunModel == null || submit.extras == null) return;
        RenderType template = switch (gunClipMode) {
            case "equal0" -> ScopeStencilRenderTypes.entityEqual0(submit.gunTexture);
            case "greater127" -> ScopeStencilRenderTypes.entityGreater127(submit.gunTexture);
            default -> null;
        };
        submit.extras.emit(group.font, original -> template == null
                ? group.vertexBuilder(original) : group.vertexBuilder(original, template, true));
    }

    private static void addHands(Group group, ScopeSubmit submit, PoseStack gunPose, float aimingProgress,
                                 float apertureRadius, String gunClipMode) {
        if (submit.gunModel == null || !submit.renderHand || submit.player == null) {
            return;
        }
        Identifier skinTexture = submit.player.getSkin().body().texturePath();
        RenderType renderType = switch (gunClipMode) {
            case "equal0" -> ScopeStencilRenderTypes.entityEqual0(skinTexture);
            case "greater127" -> ScopeStencilRenderTypes.entityGreater127(skinTexture);
            default -> RenderTypes.entityTranslucent(skinTexture);
        };
        String stencilFunc = switch (gunClipMode) {
            case "equal0" -> "GL_EQUAL:0";
            case "greater127" -> "GL_GREATER:127";
            default -> "none";
        };
        stage("hands", submit, false, aimingProgress, apertureRadius, stencilFunc, "GL_KEEP",
                ORDER_HANDS, renderType, -1, -1, "hands", gunClipMode, true, "");
        VertexConsumer buffer = group.vertexBuilder(renderType);
        FirstPersonArmSubmitter.renderGunHandsToBuffer(submit.player, submit.gunModel, copyPose(gunPose),
                buffer, submit.light);
    }

    private static void addPartStage(Group group, ScopeSubmit submit, String stage, PoseStack scopePose,
                                     RenderType renderType, @Nullable List<BedrockPart> path, int order,
                                     String stencilFunc, String stencilOp, int stencilRef, int ocularIndex,
                                     String ocularPathKind, String gunClipMode, float aimingProgress,
                                     float apertureRadius, Set<BedrockPart> specialLeaves,
                                     boolean visibleSpecialLeaf) {
        if (!submit.rendersAttachmentPart(path)) return;
        if (path == null) {
            stage(stage, submit, false, aimingProgress, apertureRadius, stencilFunc, stencilOp, order,
                    renderType, stencilRef, ocularIndex, ocularPathKind, gunClipMode, false, "missing_part_path");
            return;
        }
        stage(stage, submit, false, aimingProgress, apertureRadius, stencilFunc, stencilOp, order, renderType,
                stencilRef, ocularIndex, ocularPathKind, gunClipMode, true, "");
        if (visibleSpecialLeaf) {
            addSpecialLeaf(specialLeaves, path);
        }
        VertexConsumer buffer = submit.preserveProvidedRenderTypes && renderType != submit.attachmentRenderType
                ? group.vertexBuilder(submit.attachmentRenderType, renderType,
                        renderType.pipeline().getDepthStencilState().depthTest() != CompareOp.ALWAYS_PASS)
                : group.vertexBuilder(renderType);
        submit.attachmentModel.renderTempPartToBuffer(copyPose(scopePose), submit.transformType, buffer,
                submit.light, submit.overlay, path);
    }

    private static void writeApertureFan(VertexConsumer buffer, ScopeApertureFanSpace fanSpace,
                                         float centerX, float centerY, float radius) {
        Matrix4f fanPose = new Matrix4f();
        float clampedRadius = Math.max(0.0F, radius);
        writeApertureVertex(buffer, fanPose, fanSpace, centerX, centerY, -90.0F);
        for (int j = 0; j <= APERTURE_SEGMENTS; j++) {
            float angle = (float) j * ((float) Math.PI * 2F) / APERTURE_SEGMENTS;
            float sin = Mth.sin(angle);
            float cos = Mth.cos(angle);
            writeApertureVertex(buffer, fanPose, fanSpace,
                    centerX + cos * clampedRadius, centerY + sin * clampedRadius, -90.0F);
        }
    }

    private static void writeApertureVertex(VertexConsumer buffer, Matrix4f fanPose,
                                            ScopeApertureFanSpace fanSpace,
                                            float x, float y, float z) {
        Vector3f stagedPosition = fanSpace.toStagedVertex(x, y, z);
        buffer.addVertex(fanPose, stagedPosition.x(), stagedPosition.y(), stagedPosition.z())
                .setColor(255, 255, 255, 255);
    }

    private static void stage(String stage, ScopeSubmit submit, boolean selective, float aimingProgress,
                              float apertureRadius, String stencilFunc, String stencilOp, int order,
                              @Nullable RenderType renderType, int stencilRef, int ocularIndex,
                              String ocularPathKind, String gunClipMode, boolean submitted, String blockedReason) {
        ScopeRenderDebug.stageDetailed(stage, submit.attachmentItem, submit.gunItem, submit.transformType,
                submit.attachmentModel.isScope(), submit.attachmentModel.isSight(), selective, aimingProgress,
                apertureRadius, stencilFunc, stencilOp, order, renderType == null ? "" : renderType.toString(),
                stencilRef, ocularIndex, ocularPathKind, scopeKind(submit.attachmentModel), gunClipMode,
                submitted, blockedReason);
    }

    private static String gunClipMode(BedrockAttachmentModel model, boolean activeViewIsScopeOcular) {
        if (Boolean.getBoolean("tacz.scope.disableGunBodyStencilClip")) {
            return "none";
        }
        if (model.isScope() && model.isSight()) {
            return activeViewIsScopeOcular ? "greater127" : "equal0";
        }
        if (model.isScope()) {
            return "equal0";
        }
        return "none";
    }

    private static String scopeKind(BedrockAttachmentModel model) {
        if (model.isScope() && model.isSight()) {
            return "scope_and_sight";
        }
        if (model.isScope()) {
            return "scope";
        }
        if (model.isSight()) {
            return "sight";
        }
        return "plain";
    }

    static boolean canStageIntegratedPass(BedrockAttachmentModel model) {
        return canStageIntegratedPass(model, false);
    }

    public static boolean canStageMeshOptics(@Nullable BedrockAttachmentModel model, @Nullable Identifier texture) {
        return model != null && texture != null && canStageIntegratedPass(model, true);
    }

    static boolean canStageIntegratedPass(BedrockAttachmentModel model, boolean meshAppearance) {
        if (model.isScope()) {
            return !model.ocularNodePaths.isEmpty() && (meshAppearance || model.scopeBodyPath != null);
        }
        if (model.isSight()) {
            return !model.ocularNodePaths.isEmpty();
        }
        return false;
    }

    private static String ocularPathKind(boolean scopeOcular) {
        return scopeOcular ? "ocular_scope" : "ocular";
    }

    private static int orderedRefStage(int baseOrder, int index, int count) {
        return baseOrder + Math.max(0, count - 1 - index);
    }

    private static PoseStack copyPose(PoseStack source) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(source.last().pose());
        copy.last().normal().set(source.last().normal());
        return copy;
    }

    private static void addSpecialLeaves(BedrockAttachmentModel model, Set<BedrockPart> leaves) {
        addSpecialLeaf(leaves, model.scopeBodyPath);
        addSpecialLeaf(leaves, model.ocularRingPath);
        addSpecialLeaves(leaves, model.ocularNodePaths);
        addSpecialLeaves(leaves, model.divisionNodePaths);
    }

    private static void addScopeViewLeaves(BedrockAttachmentModel model, Set<BedrockPart> leaves) {
        addSpecialLeaves(leaves, model.scopeViewPaths);
    }

    private static void addSpecialLeaves(Set<BedrockPart> leaves, @Nullable List<List<BedrockPart>> paths) {
        if (paths == null) {
            return;
        }
        for (List<BedrockPart> path : paths) {
            addSpecialLeaf(leaves, path);
        }
    }

    private static void addSpecialLeaf(Set<BedrockPart> leaves, @Nullable List<BedrockPart> path) {
        if (path != null && !path.isEmpty()) {
            leaves.add(path.get(path.size() - 1));
        }
    }

    private static final class Group {
        private final StagedVertexBuffer stagedBuffer;
        private final Font font;
        private final List<DrawStage> stages = new ArrayList<>();
        @Nullable private GunBodyRenderer.PreparedScopeBody currentBody;

        private Group(StagedVertexBuffer stagedBuffer, Font font) {
            this.stagedBuffer = stagedBuffer;
            this.font = font;
        }

        private VertexConsumer vertexBuilder(RenderType renderType) {
            PreparedRenderType preparedRenderType = renderType.prepare();
            VertexSorting sorting = renderType.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
            StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(renderType.format(), renderType.primitiveTopology(), sorting);
            stages.add(new DrawStage(preparedRenderType, draw, Control.DRAW, currentBody));
            return stagedBuffer.getVertexBuilder(draw);
        }

        private VertexConsumer vertexBuilder(RenderType original, RenderType stencilTemplate, boolean preserveDepth) {
            PreparedRenderType prepared = ScopeStencilRenderTypes.withLegacyStencil(
                    original.prepare(), stencilTemplate.pipeline(), preserveDepth);
            VertexSorting sorting = original.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
            StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(original.format(), original.primitiveTopology(), sorting);
            stages.add(new DrawStage(prepared, draw, Control.DRAW, currentBody));
            return stagedBuffer.getVertexBuilder(draw);
        }

        private StagedBuilder vertexBuilderWithModelView(RenderType renderType) {
            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            PreparedRenderType preparedRenderType = renderType.prepare();
            VertexSorting sorting = renderType.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
            StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(renderType.format(), renderType.primitiveTopology(), sorting);
            stages.add(new DrawStage(preparedRenderType, draw, Control.DRAW, currentBody));
            return new StagedBuilder(stagedBuffer.getVertexBuilder(draw), modelView);
        }

        private void control(Control control) {
            // Clears are never gated by a retired body, including retirement after staging.
            stages.add(new DrawStage(null, null, control, null));
        }

        private record StagedBuilder(VertexConsumer buffer, Matrix4f modelView) {
        }
    }

    private record ScopeApertureFanSpace(@Nullable Matrix4f inverseModelView,
                                         float determinant,
                                         int matrixHash,
                                         float viewCenterX,
                                         float viewCenterY,
                                         float viewCenterZ,
                                         float centerX,
                                         float centerY,
                                         String blockedReason) {
        private static ScopeApertureFanSpace capture(Matrix4f modelView, Vector3f stagedOcularCenter) {
            float determinant = modelView.determinant();
            int hash = matrixHash(modelView);
            Vector3f viewCenter = modelView.transformPosition(stagedOcularCenter, new Vector3f());
            float centerX = viewCenter.x() * 16 * 90;
            float centerY = viewCenter.y() * 16 * 90;
            if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0E-6F) {
                return new ScopeApertureFanSpace(null, determinant, hash, viewCenter.x(), viewCenter.y(),
                        viewCenter.z(), centerX, centerY, "invalid_model_view_inverse");
            }
            Matrix4f inverse = new Matrix4f(modelView);
            inverse.invert();
            return new ScopeApertureFanSpace(inverse, determinant, hash, viewCenter.x(), viewCenter.y(),
                    viewCenter.z(), centerX, centerY, "");
        }

        private boolean valid() {
            return inverseModelView != null;
        }

        private Vector3f toStagedVertex(float x, float y, float z) {
            if (inverseModelView == null) {
                return new Vector3f(x, y, z);
            }
            return inverseModelView.transformPosition(x, y, z, new Vector3f());
        }

        private String debug() {
            return String.format(Locale.ROOT, "modelViewHash=%08x;det=%.6g;space=%s",
                    matrixHash, determinant, valid() ? "view_plane_inverse_model_view" : blockedReason);
        }

        private static int matrixHash(Matrix4f matrix) {
            int result = 1;
            result = 31 * result + Float.floatToIntBits(matrix.m00());
            result = 31 * result + Float.floatToIntBits(matrix.m01());
            result = 31 * result + Float.floatToIntBits(matrix.m02());
            result = 31 * result + Float.floatToIntBits(matrix.m03());
            result = 31 * result + Float.floatToIntBits(matrix.m10());
            result = 31 * result + Float.floatToIntBits(matrix.m11());
            result = 31 * result + Float.floatToIntBits(matrix.m12());
            result = 31 * result + Float.floatToIntBits(matrix.m13());
            result = 31 * result + Float.floatToIntBits(matrix.m20());
            result = 31 * result + Float.floatToIntBits(matrix.m21());
            result = 31 * result + Float.floatToIntBits(matrix.m22());
            result = 31 * result + Float.floatToIntBits(matrix.m23());
            result = 31 * result + Float.floatToIntBits(matrix.m30());
            result = 31 * result + Float.floatToIntBits(matrix.m31());
            result = 31 * result + Float.floatToIntBits(matrix.m32());
            result = 31 * result + Float.floatToIntBits(matrix.m33());
            return result;
        }
    }

    private record DrawStage(@Nullable PreparedRenderType renderType,
                             @Nullable StagedVertexBuffer.Draw draw,
                             Control control,
                             @Nullable GunBodyRenderer.PreparedScopeBody owner) {
    }

    private enum Control {
        CLEAR_STENCIL,
        DRAW
    }

    public record ScopeSubmit(@Nullable BedrockGunModel gunModel,
                              BedrockAttachmentModel attachmentModel,
                              @Nullable AbstractClientPlayer player,
                              ItemStack gunItem,
                              @Nullable ItemStack attachmentItem,
                              ItemDisplayContext transformType,
                              RenderType gunRenderType,
                              RenderType attachmentRenderType,
                              Identifier gunTexture,
                              Identifier attachmentTexture,
                              Matrix4f gunPose,
                              Matrix3f gunNormal,
                              @Nullable FirstPersonHandSway handSway,
                              boolean renderHand,
                              boolean meshAppearance,
                              int activeScopeViewIndex,
                              int light,
                              int overlay,
                              boolean preserveProvidedRenderTypes,
                              @Nullable ScopeRenderExtras extras,
                              @Nullable GunBodyRenderer.PreparedScopeBody customBody) implements SubmitNode {
        public ScopeSubmit(@Nullable BedrockGunModel gunModel, BedrockAttachmentModel attachmentModel,
                           @Nullable AbstractClientPlayer player, ItemStack gunItem, @Nullable ItemStack attachmentItem,
                           ItemDisplayContext transformType, RenderType gunRenderType, RenderType attachmentRenderType,
                           Identifier gunTexture, Identifier attachmentTexture, Matrix4f gunPose, Matrix3f gunNormal,
                           @Nullable FirstPersonHandSway handSway, boolean renderHand, boolean meshAppearance,
                           int activeScopeViewIndex, int light, int overlay, boolean preserveProvidedRenderTypes,
                           @Nullable ScopeRenderExtras extras) {
            this(gunModel, attachmentModel, player, gunItem, attachmentItem, transformType, gunRenderType,
                    attachmentRenderType, gunTexture, attachmentTexture, gunPose, gunNormal, handSway,
                    renderHand, meshAppearance, activeScopeViewIndex, light, overlay, preserveProvidedRenderTypes, extras, null);
        }

        public ScopeSubmit(@Nullable BedrockGunModel gunModel, BedrockAttachmentModel attachmentModel,
                           @Nullable AbstractClientPlayer player, ItemStack gunItem, @Nullable ItemStack attachmentItem,
                           ItemDisplayContext transformType, RenderType gunRenderType, RenderType attachmentRenderType,
                           Identifier gunTexture, Identifier attachmentTexture, Matrix4f gunPose, Matrix3f gunNormal,
                           @Nullable FirstPersonHandSway handSway, boolean renderHand, boolean meshAppearance,
                           int activeScopeViewIndex, int light, int overlay, boolean preserveProvidedRenderTypes) {
            this(gunModel, attachmentModel, player, gunItem, attachmentItem, transformType, gunRenderType,
                    attachmentRenderType, gunTexture, attachmentTexture, gunPose, gunNormal, handSway,
                    renderHand, meshAppearance, activeScopeViewIndex, light, overlay, preserveProvidedRenderTypes, null);
        }

        public ScopeSubmit(@Nullable BedrockGunModel gunModel, BedrockAttachmentModel attachmentModel,
                           @Nullable AbstractClientPlayer player, ItemStack gunItem, @Nullable ItemStack attachmentItem,
                           ItemDisplayContext transformType, RenderType gunRenderType, RenderType attachmentRenderType,
                           Identifier gunTexture, Identifier attachmentTexture, Matrix4f gunPose, Matrix3f gunNormal,
                           @Nullable FirstPersonHandSway handSway, boolean renderHand, boolean meshAppearance,
                           int activeScopeViewIndex, int light, int overlay) {
            this(gunModel, attachmentModel, player, gunItem, attachmentItem, transformType, gunRenderType,
                    attachmentRenderType, gunTexture, attachmentTexture, gunPose, gunNormal, handSway,
                    renderHand, meshAppearance, activeScopeViewIndex, light, overlay, false);
        }

        @Override
        public FeatureRendererType<? extends SubmitNode> featureType() {
            return TYPE;
        }

        void submitIntegratedSemantics(SubmitNodeCollector collector) {
            if (gunModel == null || meshAppearance || !isAvailable()) {
                return;
            }
            Runnable submit = () -> RenderHelper.withSubmitNodeCollector(collector, () ->
                    attachmentModel.submitInstalledSemantics(attachmentItem, gunItem, toScopePoseStack(),
                            transformType, light, overlay));
            Runnable withSway = () -> {
                if (handSway == null) submit.run();
                else handSway.withTemporaryModelSway(gunModel.getRootNode(), submit);
            };
            if (extras == null) withSway.run();
            else extras.withFrameState(withSway);
        }

        boolean isAvailable() {
            return customBody == null || customBody.isAvailable();
        }

        private PoseStack toGunPoseStack() {
            PoseStack stack = new PoseStack();
            stack.last().pose().set(gunPose);
            stack.last().normal().set(gunNormal);
            return stack;
        }

        boolean rendersAttachmentPart(@Nullable List<BedrockPart> path) {
            return !meshAppearance || (path != attachmentModel.scopeBodyPath && path != attachmentModel.ocularRingPath);
        }

        PoseStack toScopePoseStack() {
            PoseStack stack = toGunPoseStack();
            if (gunModel == null) return stack;
            for (BedrockPart bedrockPart : gunModel.scopePosPath) {
                bedrockPart.translateAndRotateAndScale(stack);
            }
            stack.translate(0, -1.5, 0);
            return stack;
        }
    }
}
