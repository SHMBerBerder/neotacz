package com.tacz.guns.client.model;

import com.mojang.blaze3d.vertex.*;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.debug.ScopeRenderDebug;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.bedrock.ModelRendererWrapper;
import com.tacz.guns.client.model.functional.BeamRenderer;
import com.tacz.guns.client.model.functional.TextShowRender;
import com.tacz.guns.client.renderer.BedrockSubmitUtils;
import com.tacz.guns.client.resource.pojo.display.gun.TextShow;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.util.RenderHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

public class BedrockAttachmentModel extends BedrockAnimatedModel {
    private static final String SCOPE_VIEW_NODE = "scope_view";
    private static final String SCOPE_BODY_NODE = "scope_body";
    private static final String OCULAR_RING_NODE = "ocular_ring";
    private static final String DIVISION_NODE = "division";
    private static final String OCULAR_NODE = "ocular";
    private static final String OCULAR_SIGHT_NODE = "ocular_sight";
    private static final String OCULAR_SCOPE_NODE = "ocular_scope";
    private static final Pattern LASER_BEAM_PATTERN = Pattern.compile("^laser_beam(_(\\d+))?$");

    protected List<List<BedrockPart>> scopeViewPaths;
    protected @Nullable List<BedrockPart> scopeBodyPath;
    protected @Nullable List<BedrockPart> ocularRingPath;
    protected List<List<BedrockPart>> ocularNodePaths;
    protected List<Boolean> isScopeOcular;
    protected List<List<BedrockPart>> divisionNodePaths;
    protected @Nullable List<List<BedrockPart>> laserBeamPaths;

    private @Nullable ItemStack currentGunItem;
    private @Nullable ItemStack attachmentItem;

    private boolean isScope = false;
    private boolean isSight = false;
    private float scopeViewRadiusModifier = 1;

    public BedrockAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
        scopeViewPaths = new ArrayList<>();
        ocularNodePaths = new ArrayList<>();
        isScopeOcular = new ArrayList<>();
        divisionNodePaths = new ArrayList<>();
        laserBeamPaths = new ArrayList<>();
        // 初始化 view 的 node path
        List<BedrockPart> path = getPath(modelMap.get(SCOPE_VIEW_NODE));
        int i = 2;
        while (path != null) {
            scopeViewPaths.add(path);
            path = getPath(modelMap.get(SCOPE_VIEW_NODE + '_' + i++));
        }
        // 初始化 ocular 的 node path
        String ocularRegex = "^(" + OCULAR_NODE + "|" + OCULAR_SIGHT_NODE + "|" + OCULAR_SCOPE_NODE + ")(_(\\d+))?$";
        Pattern ocularPattern = Pattern.compile(ocularRegex);
        TreeMap<Integer, OcularWrapper> map = new TreeMap<>();
        for (Map.Entry<String, ModelRendererWrapper> entry : modelMap.entrySet()) {
            Matcher matcher = ocularPattern.matcher(entry.getKey());
            if (matcher.matches()) {
                int num = 1;
                String numStr = matcher.group(3);
                if (numStr != null) {
                    num = Integer.parseInt(numStr);
                }
                String type = matcher.group(1);
                boolean isScope = OCULAR_SCOPE_NODE.equals(type);
                map.put(num, new OcularWrapper(entry.getValue(), isScope));
            }
            if (LASER_BEAM_PATTERN.matcher(entry.getKey()).find()) {
                laserBeamPaths.add(getPath(entry.getValue()));
            }
        }
        for (OcularWrapper wrapper : map.values()) {
            ocularNodePaths.add(getPath(wrapper.renderer));
            isScopeOcular.add(wrapper.isScope);
        }
        // 初始化 division 的 node path
        ModelRendererWrapper divisionModel = modelMap.get(DIVISION_NODE);
        path = getPath(modelMap.get(DIVISION_NODE));
        i = 2;
        while (path != null) {
            divisionNodePaths.add(path);
            divisionModel.setHidden(true);
            divisionModel = modelMap.get(DIVISION_NODE + '_' + i++);
            path = getPath(divisionModel);
        }

        scopeBodyPath = getPath(modelMap.get(SCOPE_BODY_NODE));
        ocularRingPath = getPath(modelMap.get(OCULAR_RING_NODE));
    }

    @Nullable
    public List<BedrockPart> getScopeViewPath(int viewSwitchCount) {
        if (scopeViewPaths.isEmpty()) {
            return null;
        }
        if (viewSwitchCount >= scopeViewPaths.size()) {
            return scopeViewPaths.get(0);
        }
        return scopeViewPaths.get(viewSwitchCount);
    }

    public int getScopeViewPathCount() {
        return scopeViewPaths.size();
    }

    boolean isScopeOcularViewIndex(int viewIndex) {
        if (viewIndex < 0 || viewIndex >= isScopeOcular.size()) {
            return isScope;
        }
        return isScopeOcular.get(viewIndex);
    }

    public void setIsScope(boolean isScope) {
        this.isScope = isScope;
    }

    public void setIsSight(boolean isSight) {
        this.isSight = isSight;
    }

    public boolean isScope() {
        return isScope;
    }

    public boolean isSight() {
        return isSight;
    }

    public void setScopeViewRadiusModifier(float scopeViewRadiusModifier) {
        this.scopeViewRadiusModifier = scopeViewRadiusModifier;
    }

    /**
     * 添加枪械自定义的文本显示
     */
    public void setTextShowList(Map<String, TextShow> textShowList) {
        textShowList.forEach((name, textShow) -> this.setFunctionalRenderer(name,
                bedrockPart -> new TextShowRender(this, textShow, currentGunItem)));
    }

    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack matrixStack,
                       ItemDisplayContext transformType, RenderType renderType, int light, int overlay) {
        SubmitNodeCollector collector = RenderHelper.currentSubmitNodeCollector();
        if (collector == null) {
            return;
        }
        prepareInstalledRenderState(attachmentItem, currentGunItem);
        int viewIndex = -1;
        IAttachment attachment = IAttachment.getIAttachmentOrNull(attachmentItem);
        if (attachment != null) {
            var index = TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(attachmentItem));
            if (index.isPresent()) {
                int[] views = index.get().getViews();
                if (views.length > 0) {
                    viewIndex = views[Math.floorMod(attachment.getZoomNumber(attachmentItem), views.length)] - 1;
                }
            }
        }
        if (ScopeStencilFeatureRenderer.submitLegacyAttachment(this, attachmentItem, currentGunItem, matrixStack,
                collector, transformType, renderType, viewIndex, light, overlay)) {
            submitInstalledSemantics(attachmentItem, currentGunItem, matrixStack, transformType, light, overlay);
            return;
        }
        submitPlainInstalled(matrixStack, collector, transformType, renderType, light, overlay);
    }

    void prepareInstalledRenderState(@Nullable ItemStack attachmentItem, ItemStack currentGunItem) {
        this.currentGunItem = currentGunItem;
        this.attachmentItem = attachmentItem;
    }

    /** Functional effects only; the caller owns the attachment's visible geometry. */
    public void submitInstalledSemantics(@Nullable ItemStack attachmentItem, ItemStack gunItem,
                                         PoseStack matrixStack, ItemDisplayContext transformType) {
        prepareInstalledRenderState(attachmentItem, gunItem);
        if (laserBeamPaths != null) {
            for (var path : laserBeamPaths) {
                BeamRenderer.renderLaserBeam(attachmentItem, matrixStack, transformType, path);
            }
        }
    }

    public void submitInstalledSemantics(@Nullable ItemStack attachmentItem, ItemStack gunItem,
                                         PoseStack matrixStack, ItemDisplayContext transformType,
                                         int light, int overlay) {
        submitInstalledSemantics(attachmentItem, gunItem, matrixStack, transformType);
        RenderHelper.withDeferredFunctionalRendererCollection(() ->
                collectDeferredFunctionalRenderers(matrixStack, transformType, light, overlay));
    }

    public int submitInstalled(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack matrixStack,
                                SubmitNodeCollector collector, ItemDisplayContext transformType, RenderType renderType,
                                Identifier texture, int light, int overlay) {
        this.currentGunItem = currentGunItem;
        this.attachmentItem = attachmentItem;
        if (transformType.firstPerson()) {
            submitPlainInstalled(matrixStack, collector, transformType, renderType, light, overlay);
        } else {
            submitPlainInstalled(matrixStack, collector, transformType, renderType, light, overlay);
        }
        return BedrockGunModel.SCOPE_GUN_CLIP_NONE;
    }
    private void submitPlainInstalled(PoseStack matrixStack, SubmitNodeCollector collector, ItemDisplayContext transformType,
                                      RenderType renderType, int light, int overlay) {
        if (!transformType.firstPerson()) {
            submitPlainPartStage("scope_body_plain", matrixStack, collector, renderType, transformType, light, overlay,
                    scopeBodyPath, false, getClientAimingProgress(), getScopeApertureRadius());
            submitPlainPartStage("ocular_ring_plain", matrixStack, collector, renderType, transformType, light, overlay,
                    ocularRingPath, false, getClientAimingProgress(), getScopeApertureRadius());
        }
        if (!isScope && !isSight && laserBeamPaths != null) {
            for (var entry : laserBeamPaths) {
                BeamRenderer.renderLaserBeam(attachmentItem, matrixStack, transformType, entry);
            }
        }
        submitModelStage("plain_model", matrixStack, collector, renderType, transformType, light, overlay);
        if ((isScope || isSight) && laserBeamPaths != null) {
            for (var entry : laserBeamPaths) {
                BeamRenderer.renderLaserBeam(attachmentItem, matrixStack, transformType, entry);
            }
        }
    }
    Vector3f getBedrockPartCenter(PoseStack poseStack, @Nonnull List<BedrockPart> path) {
        poseStack.pushPose();
        for (BedrockPart part : path) {
            part.translateAndRotateAndScale(poseStack);
        }
        Vector3f result = new Vector3f(poseStack.last().pose().m30(), poseStack.last().pose().m31(), poseStack.last().pose().m32());
        poseStack.popPose();
        return result;
    }
    void renderTempPartToBuffer(PoseStack poseStack, ItemDisplayContext transformType, VertexConsumer buffer,
                                int light, int overlay, @Nonnull List<BedrockPart> path) {
        poseStack.pushPose();
        for (int i = 0; i < path.size() - 1; ++i) {
            path.get(i).translateAndRotateAndScale(poseStack);
        }
        BedrockPart part = path.get(path.size() - 1);
        part.visible = true;
        part.render(poseStack, transformType, buffer, light, overlay);
        part.visible = false;
        poseStack.popPose();
    }
    private void submitPlainPartStage(String stage, PoseStack matrixStack, SubmitNodeCollector collector,
                                      RenderType renderType, ItemDisplayContext transformType, int light, int overlay,
                                      @Nullable List<BedrockPart> path, boolean selective, float aimingProgress,
                                      float apertureRadius) {
        if (path == null) {
            ScopeRenderDebug.stage(stage, attachmentItem, currentGunItem, transformType, isScope, isSight, selective,
                    aimingProgress, apertureRadius, "none", "none", false, "missing_part_path");
            return;
        }
        ScopeRenderDebug.stage(stage, attachmentItem, currentGunItem, transformType, isScope, isSight, selective,
                aimingProgress, apertureRadius, "none", "none", true, "");
        RenderHelper.submitCustomGeometry(collector, matrixStack, renderType, (pose, buffer) -> {
            PoseStack callbackPoseStack = BedrockSubmitUtils.fromPose(pose);
            renderTempPartToBuffer(callbackPoseStack, transformType, buffer, light, overlay, path);
        });
    }
    private void submitModelStage(String stage, PoseStack matrixStack, SubmitNodeCollector collector, RenderType renderType,
                                  ItemDisplayContext transformType, int light, int overlay) {
        ScopeRenderDebug.stage(stage, attachmentItem, currentGunItem, transformType, isScope, isSight, false,
                getClientAimingProgress(), getScopeApertureRadius(), "none", "none", true, "");
        Consumer<Runnable> frameState = captureRenderState();
        frameState.accept(() -> RenderHelper.withSubmitNodeCollector(collector, () ->
                RenderHelper.withDeferredFunctionalRendererCollection(() ->
                        collectDeferredFunctionalRenderers(BedrockSubmitUtils.fromPose(matrixStack.last()),
                                transformType, light, overlay))));
        RenderHelper.submitCustomGeometry(collector, matrixStack, renderType, (pose, buffer) -> {
            PoseStack callbackPoseStack = BedrockSubmitUtils.fromPose(pose);
            frameState.accept(() -> RenderHelper.withDeferredRenderersSuppressed(() ->
                    renderBaseModelToBuffer(callbackPoseStack, transformType, buffer, light, overlay)));
        });
    }

    Consumer<Runnable> captureRenderState() {
        ScopeModelState parts = ScopeModelState.capture(this);
        ItemStack gun = currentGunItem == null ? null : currentGunItem.copy();
        ItemStack attachment = attachmentItem == null ? null : attachmentItem.copy();
        return action -> {
            ItemStack previousGun = currentGunItem;
            ItemStack previousAttachment = attachmentItem;
            List<IFunctionalRenderer> previousDelegates = delegateRenderers;
            currentGunItem = gun;
            attachmentItem = attachment;
            delegateRenderers = new ArrayList<>();
            try {
                parts.withState(action);
            } finally {
                currentGunItem = previousGun;
                attachmentItem = previousAttachment;
                delegateRenderers = previousDelegates;
            }
        };
    }
    void renderBaseModelToBuffer(PoseStack matrixStack, ItemDisplayContext transformType, VertexConsumer buffer,
                                 int light, int overlay) {
        super.renderToBuffer(matrixStack, transformType, buffer, light, overlay);
    }

    void renderBaseModelToBuffer(PoseStack matrixStack, ItemDisplayContext transformType, VertexConsumer buffer,
                                 int light, int overlay, Set<BedrockPart> submittedVisibleSpecialLeaves) {
        if (transformType.firstPerson() && (isScope || isSight) && !submittedVisibleSpecialLeaves.isEmpty()) {
            // Retained stencil stages can be rejected later by GPU state ordering. Only filter a special leaf
            // after its ordinary visible fallback was submitted, so a failed stencil path cannot leave only the ring.
            renderBaseModelFilteredToBuffer(matrixStack, transformType, buffer, light, overlay, submittedVisibleSpecialLeaves);
        } else {
            super.renderToBuffer(matrixStack, transformType, buffer, light, overlay);
        }
    }

    private void renderBaseModelFilteredToBuffer(PoseStack matrixStack, ItemDisplayContext transformType,
                                                 VertexConsumer buffer, int light, int overlay,
                                                 Set<BedrockPart> excluded) {
        matrixStack.pushPose();
        for (BedrockPart model : shouldRender) {
            renderPartFiltered(matrixStack, transformType, buffer, light, overlay, model, excluded);
        }
        matrixStack.popPose();
        for (IFunctionalRenderer renderer : delegateRenderers) {
            renderer.render(matrixStack, buffer, transformType, light, overlay);
        }
        delegateRenderers = new ArrayList<>();
    }
    private static void renderPartFiltered(PoseStack poseStack, ItemDisplayContext transformType, VertexConsumer consumer,
                                           int light, int overlay, BedrockPart part, Set<BedrockPart> excluded) {
        if (!part.visible || excluded.contains(part)) {
            return;
        }
        int cubePackedLight = part.illuminated ? LightCoordsUtil.pack(15, 15) : light;
        if (part.cubes.isEmpty() && part.children.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        part.translateAndRotateAndScale(poseStack);
        part.compile(poseStack.last(), consumer, cubePackedLight, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
        for (BedrockPart child : part.children) {
            renderPartFiltered(poseStack, transformType, consumer, cubePackedLight, overlay, child, excluded);
        }
        poseStack.popPose();
    }
    private static void addSpecialLeaves(Set<BedrockPart> excluded, @Nullable List<List<BedrockPart>> paths) {
        if (paths == null) {
            return;
        }
        for (List<BedrockPart> path : paths) {
            addSpecialLeaf(excluded, path);
        }
    }
    private static void addSpecialLeaf(Set<BedrockPart> excluded, @Nullable List<BedrockPart> path) {
        if (path != null && !path.isEmpty()) {
            excluded.add(path.get(path.size() - 1));
        }
    }
    float getScopeApertureRadius() {
        return getScopeApertureRadius(getClientAimingProgress());
    }
    float getScopeApertureRadius(float aimingProgress) {
        // 80 follows the original 1.20.1 aperture size; ADS progress only scales the aperture.
        float radius = 80 * scopeViewRadiusModifier;
        return radius * aimingProgress;
    }
    float getClientAimingProgress() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            return IClientPlayerGunOperator.fromLocalPlayer(player).getClientAimingProgress(partialTick);
        }
        return 0.0F;
    }
    public void renderBothAccelerated(PoseStack matrixStack, ItemDisplayContext transformType,
                                      RenderType renderType, int light, int overlay) {
        render(attachmentItem, currentGunItem == null ? ItemStack.EMPTY : currentGunItem,
                matrixStack, transformType, renderType, light, overlay);
    }

    private static class OcularWrapper{
        public ModelRendererWrapper renderer;
        public boolean isScope;

        public OcularWrapper (ModelRendererWrapper renderer, boolean isScope){
            this.renderer = renderer;
            this.isScope = isScope;
        }
    }
}
