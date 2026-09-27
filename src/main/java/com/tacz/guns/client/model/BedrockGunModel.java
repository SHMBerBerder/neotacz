package com.tacz.guns.client.model;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.client.animation.AnimationListener;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.debug.ScopeRenderDebug;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.bedrock.ModelRendererWrapper;
import com.tacz.guns.client.model.functional.*;
import com.tacz.guns.client.model.gltf.render.GunBodyRenderer;
import com.tacz.guns.client.model.listener.model.ModelAdditionalMagazineListener;
import com.tacz.guns.client.renderer.item.FirstPersonHandSway;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.gun.TextShow;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.util.RenderHelper;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Consumer;

import static com.tacz.guns.client.model.GunModelConstant.*;

public class BedrockGunModel extends BedrockAnimatedModel {
    public static final int SCOPE_GUN_CLIP_NONE = 0;

    protected final EnumMap<AttachmentType, List<BedrockPart>> refitAttachmentViewPath = Maps.newEnumMap(AttachmentType.class);
    private final EnumMap<AttachmentType, ItemStack> currentAttachmentItem = Maps.newEnumMap(AttachmentType.class);
    private final Set<String> adapterToRender = Sets.newHashSet();
    private final ArrayList<ShellRender> shellRenderList = new ArrayList<>();
    private final AtomicReference<GunBodyRenderer> bodyRenderer = new AtomicReference<>();

    // 第一人称机瞄摄像机定位组的路径
    protected @Nullable List<BedrockPart> ironSightPath;
    // 第一人称idle状态摄像机定位组的路径
    protected @Nullable List<BedrockPart> idleSightPath;
    // 第三人称手部物品渲染原点定位组的路径
    protected @Nullable List<BedrockPart> thirdPersonHandOriginPath;
    // 展示框渲染原点定位组的路径
    protected @Nullable List<BedrockPart> fixedOriginPath;
    // 地面实体渲染原点定位组的路径
    protected @Nullable List<BedrockPart> groundOriginPath;
    // 瞄具配件定位组的路径。其他配件不需要存路径，只需要替换渲染。但是瞄具定位组需要用来辅助第一人称瞄准的摄像机定位。
    protected @Nullable List<BedrockPart> scopePosPath;
    // 枪口火焰定位组
    protected @Nullable List<BedrockPart> muzzleFlashPosPath;
    // 第一人称左手手臂定位组
    protected @Nullable List<BedrockPart> leftHandPosPath;
    // 第一人称右手手臂定位组
    protected @Nullable List<BedrockPart> rightHandPosPath;
    // 根组
    protected @Nullable BedrockPart root;
    // 弹匣定位组
    protected @Nullable BedrockPart magazineNode;
    // 换弹时第二个弹匣定位组
    protected @Nullable BedrockPart additionalMagazineNode;
    protected @Nullable List<BedrockPart> laserBeamPaths;

    private boolean renderHand = true;
    private boolean renderMount;
    private ItemStack currentGunItem;
    private int currentExtendMagLevel = 0;

    public BedrockGunModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);

        this.magazineNode = Optional.ofNullable(modelMap.get(MAG_NORMAL_NODE)).map(ModelRendererWrapper::getModelRenderer).orElse(null);
        this.additionalMagazineNode = Optional.ofNullable(modelMap.get(MAG_ADDITIONAL_NODE)).map(ModelRendererWrapper::getModelRenderer).orElse(null);

        // 左手手臂
        this.setFunctionalRenderer(LEFTHAND_POS_NODE, bedrockPart -> new LeftHandRender(this));
        // 右手手臂
        this.setFunctionalRenderer(RIGHTHAND_POS_NODE, bedrockPart -> new RightHandRender(this));
        // 枪口火焰
        this.setFunctionalRenderer(MUZZLE_FLASH_ORIGIN_NODE, bedrockPart -> new MuzzleFlashRender(this));
        // 枪管内的子弹，用于闭膛待机枪械
        this.setFunctionalRenderer(BULLET_IN_BARREL, bedrockPart -> ammoHiddenRender(bedrockPart, iGun -> iGun.hasBulletInBarrel(currentGunItem)));
        // 弹匣内子弹
        this.setFunctionalRenderer(BULLET_IN_MAG, bedrockPart -> ammoHiddenRender(bedrockPart, iGun -> iGun.getCurrentAmmoCount(currentGunItem) > 0));
        // 机枪弹链
        this.setFunctionalRenderer(BULLET_CHAIN, bedrockPart -> ammoHiddenRender(bedrockPart, iGun -> iGun.getCurrentAmmoCount(currentGunItem) > 0));
        // 有通用瞄具时显示，用于放瞄具的导轨（如 AKM 的导轨）
        this.setFunctionalRenderer(MOUNT, bedrockPart -> scopeHiddenRender(bedrockPart, scopeItem -> scopeItem != null && !scopeItem.isEmpty() && renderMount));
        // 无瞄具时可见，通常用于 M4 上
        this.setFunctionalRenderer(CARRY, bedrockPart -> scopeHiddenRender(bedrockPart, scopeItem -> scopeItem == null || scopeItem.isEmpty()));
        // 有瞄具时显示，折叠的机械瞄具
        this.setFunctionalRenderer(SIGHT_FOLDED, bedrockPart -> scopeHiddenRender(bedrockPart, scopeItem -> scopeItem != null && !scopeItem.isEmpty()));
        // 无瞄具时可见，机械瞄具
        this.setFunctionalRenderer(SIGHT, bedrockPart -> scopeHiddenRender(bedrockPart, scopeItem -> scopeItem == null || scopeItem.isEmpty()));
        // 安装一级扩容弹匣时显示
        this.setFunctionalRenderer(MAG_EXTENDED_1, bedrockPart -> extendedMagHiddenRender(bedrockPart, 1));
        // 安装二级扩容弹匣时显示
        this.setFunctionalRenderer(MAG_EXTENDED_2, bedrockPart -> extendedMagHiddenRender(bedrockPart, 2));
        // 安装三级扩容弹匣时显示
        this.setFunctionalRenderer(MAG_EXTENDED_3, bedrockPart -> extendedMagHiddenRender(bedrockPart, 3));
        // 没有安装扩容弹匣时显示
        this.setFunctionalRenderer(MAG_STANDARD, bedrockPart -> extendedMagHiddenRender(bedrockPart, 0));
        // 部分枪械换弹动画播放时，会同时出现两个弹匣，这个就是程序自动渲染另一个弹匣的代码
        this.setFunctionalRenderer(MAG_ADDITIONAL_NODE, this::renderAdditionalMagazine);
        // 默认护木渲染
        this.setFunctionalRenderer(HANDGUARD_DEFAULT_NODE, this::handguardDefaultRender);
        // 战术护木渲染
        this.setFunctionalRenderer(HANDGUARD_TACTICAL_NODE, this::handguardTacticalRender);
        // 缓存其他定位组
        this.cacheOtherPath();
        // 缓存改装 UI 下各个配件的特写视角定位组
        this.cacheRefitAttachmentViewPath();
        // 缓存抛壳窗
        this.cacheShellOriginNodes();
        // 准备各个配件的渲染
        this.allAttachmentRender();
        // 配件转接口渲染
        this.setFunctionalRenderer(ATTACHMENT_ADAPTER_NODE, this::attachmentAdapterNodeRender);
    }

    private void cacheOtherPath() {
        ironSightPath = getPath(modelMap.get(IRON_VIEW_NODE));
        idleSightPath = getPath(modelMap.get(IDLE_VIEW_NODE));
        thirdPersonHandOriginPath = getPath(modelMap.get(THIRD_PERSON_HAND_ORIGIN_NODE));
        fixedOriginPath = getPath(modelMap.get(FIXED_ORIGIN_NODE));
        groundOriginPath = getPath(modelMap.get(GROUND_ORIGIN_NODE));
        muzzleFlashPosPath = getPath(modelMap.get(MUZZLE_FLASH_ORIGIN_NODE));
        leftHandPosPath = getPath(modelMap.get(LEFTHAND_POS_NODE));
        rightHandPosPath = getPath(modelMap.get(RIGHTHAND_POS_NODE));
        scopePosPath = getPath(modelMap.get(AttachmentType.SCOPE.name().toLowerCase() + ATTACHMENT_POS_SUFFIX));
        laserBeamPaths = getPath(modelMap.get("laser_beam"));
        root = Optional.ofNullable(modelMap.get(ROOT_NODE)).map(ModelRendererWrapper::getModelRenderer).orElse(null);
    }

    private void cacheRefitAttachmentViewPath() {
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) {
                refitAttachmentViewPath.put(type, getPath(modelMap.get(REFIT_VIEW_NODE)));
                continue;
            }
            String nodeName = REFIT_VIEW_PREFIX + type.name().toLowerCase() + REFIT_VIEW_SUFFIX;
            refitAttachmentViewPath.put(type, getPath(modelMap.get(nodeName)));
        }
    }

    private void cacheShellOriginNodes() {
        ModelRendererWrapper rendererWrapper = modelMap.get(SHELL_ORIGIN_NODE);
        int i = 1;
        while (rendererWrapper != null) {
            ShellRender shellRender = new ShellRender(this);
            this.setFunctionalRenderer(rendererWrapper.getModelRenderer().name, bedrockPart -> shellRender);
            shellRenderList.add(shellRender);
            rendererWrapper = modelMap.get(SHELL_ORIGIN_NODE_PREFIX + i);
            i++;
        }
    }

    @Nullable
    private IFunctionalRenderer attachmentAdapterNodeRender(BedrockPart bedrockPart) {
        for (BedrockPart child : bedrockPart.children) {
            if (child.name == null) {
                child.visible = false;
                continue;
            }
            child.visible = adapterToRender.contains(child.name);
        }
        return null;
    }

    private void allAttachmentRender() {
        for (AttachmentType type : AttachmentType.values()) {
            // 瞄具的渲染需要提前
            if (type == AttachmentType.NONE || type == AttachmentType.SCOPE) {
                continue;
            }
            String positionNodeName = type.name().toLowerCase() + ATTACHMENT_POS_SUFFIX;
            String defaultNodeName = type.name().toLowerCase() + DEFAULT_ATTACHMENT_SUFFIX;
            this.setFunctionalRenderer(positionNodeName, bedrockPart -> {
                bedrockPart.visible = false;
                return new AttachmentRender(this, type);
            });
            this.setFunctionalRenderer(defaultNodeName, bedrockPart -> {
                ItemStack attachmentItem = currentAttachmentItem.get(type);
                if (type == AttachmentType.MUZZLE && checkShowMuzzle(bedrockPart, attachmentItem)) {
                    return null;
                }
                bedrockPart.visible = attachmentItem == null || attachmentItem.isEmpty();
                return null;
            });
        }
    }

    private static boolean checkShowMuzzle(BedrockPart bedrockPart, ItemStack attachmentItem) {
        IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
        if (iAttachment != null) {
            Identifier attachmentId = iAttachment.getAttachmentId(attachmentItem);
            var attachmentIndex = TimelessAPI.getClientAttachmentIndex(attachmentId);
            if (attachmentIndex.isPresent()) {
                bedrockPart.visible = attachmentIndex.get().isShowMuzzle();
                return true;
            }
        }
        return false;
    }

    @Nullable
    private IFunctionalRenderer handguardTacticalRender(BedrockPart bedrockPart) {
        ItemStack laserItem = currentAttachmentItem.get(AttachmentType.LASER);
        ItemStack gripItem = currentAttachmentItem.get(AttachmentType.GRIP);
        bedrockPart.visible = !laserItem.isEmpty() || !gripItem.isEmpty();
        return null;
    }

    @Nullable
    private IFunctionalRenderer handguardDefaultRender(BedrockPart bedrockPart) {
        ItemStack laserItem = currentAttachmentItem.get(AttachmentType.LASER);
        ItemStack gripItem = currentAttachmentItem.get(AttachmentType.GRIP);
        bedrockPart.visible = laserItem.isEmpty() && gripItem.isEmpty();
        return null;
    }

    @NotNull
    private IFunctionalRenderer renderAdditionalMagazine(BedrockPart bedrockPart) {
        return (poseStack, vertexBuffer, transformType, light, overlay) -> {
            if (bedrockPart.visible) {
                bedrockPart.compile(poseStack.last(), vertexBuffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
                for (BedrockPart part : bedrockPart.children) {
                    part.render(poseStack, transformType, vertexBuffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
                }
                if (magazineNode != null && magazineNode.visible) {
                    magazineNode.compile(poseStack.last(), vertexBuffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
                    for (BedrockPart part : magazineNode.children) {
                        part.render(poseStack, transformType, vertexBuffer, light, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
                    }
                }
            }
        };
    }

    /**
     * 添加枪械自定义的文本显示
     */
    public void setTextShowList(Map<String, TextShow> textShowList) {
        textShowList.forEach((name, textShow) -> this.setFunctionalRenderer(name, bedrockPart -> new TextShowRender(this, textShow, currentGunItem)));
    }

    public void setBodyRenderer(@Nullable GunBodyRenderer renderer) {
        bodyRenderer.set(renderer);
    }

    @Nullable
    public GunBodyRenderer getBodyRenderer() {
        return bodyRenderer.get();
    }

    public boolean hasCustomBodyRenderer() {
        return availableBodyRenderer() != null;
    }

    public boolean submitCustomBody(OrderedSubmitNodeCollector collector, PoseStack matrixStack,
                                    ItemStack gunItem, ItemDisplayContext transformType, int light, int overlay) {
        GunBodyRenderer renderer = availableBodyRenderer();
        if (renderer == null) {
            return false;
        }
        try {
            boolean submitted = renderer.submit(this, matrixStack, gunItem, transformType, collector, light, overlay);
            if (!submitted && !renderer.isAvailable()) {
                bodyRenderer.compareAndSet(renderer, null);
            }
            return submitted;
        } catch (RuntimeException exception) {
            bodyRenderer.compareAndSet(renderer, null);
            // Submission is not transactional. Suppress same-frame Bedrock fallback to avoid
            // drawing over any custom nodes that may already have been queued.
            GunMod.LOGGER.warn("Disabling failed custom gun body renderer; declared mesh displays remain unavailable", exception);
            return true;
        }
    }

    @Nullable
    private GunBodyRenderer availableBodyRenderer() {
        while (true) {
            GunBodyRenderer renderer = bodyRenderer.get();
            if (renderer == null || renderer.isAvailable()) {
                return renderer;
            }
            bodyRenderer.compareAndSet(renderer, null);
        }
    }

    public void render(PoseStack matrixStack, ItemStack gunItem, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {
        SubmitNodeCollector collector = RenderHelper.currentSubmitNodeCollector();
        PreparedRender prepared = prepareRender(collector, matrixStack, null, gunItem, transformType, renderType,
                MissingTextureAtlasSprite.getLocation(), light, overlay, -860_000, null, renderHand, true);
        if (!prepared.valid) return;
        prepared.collectDeferredFunctionalRenderers(matrixStack);
        if (prepared.submitScope()) {
            return;
        }

        ItemStack attachmentItem = currentAttachmentItem.get(AttachmentType.SCOPE);
        IAttachment attachment = IAttachment.getIAttachmentOrNull(attachmentItem);
        boolean meshScope = attachment != null
                && TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(attachmentItem))
                .map(ClientAttachmentIndex::usesMeshRenderModel).orElse(false);
        // The ordinary prepass leaves a first-person Bedrock scope to the integrated pass.
        // If that pass cannot be staged, keep the old attachment's visible fallback.
        if (transformType.firstPerson() && !hasCustomBodyRenderer() && !meshScope
                && scopePosPath != null && attachmentItem != null && !attachmentItem.isEmpty()) {
            matrixStack.pushPose();
            try {
                for (BedrockPart part : scopePosPath) {
                    part.translateAndRotateAndScale(matrixStack);
                }
                AttachmentRender.submitMountedAttachment(attachmentItem, gunItem, matrixStack, transformType, light, overlay);
            } finally {
                matrixStack.popPose();
            }
        }
        RenderHelper.submitCustomGeometry(collector, matrixStack, renderType, (pose, buffer) -> {
            PoseStack callbackPose = new PoseStack();
            callbackPose.last().pose().set(pose.pose());
            callbackPose.last().normal().set(pose.normal());
            prepared.renderGunBodyToBuffer(callbackPose, buffer);
        });
    }

    public void renderToBuffer(PoseStack matrixStack, ItemStack gunItem, ItemDisplayContext transformType, VertexConsumer buffer, int light, int overlay) {
        if (!prepareRenderState(gunItem)) {
            return;
        }
        renderPreparedToBuffer(matrixStack, transformType, buffer, light, overlay, null);
    }

    private void renderPreparedToBuffer(PoseStack matrixStack, ItemDisplayContext transformType, VertexConsumer buffer,
                                         int light, int overlay, @Nullable ScopeRenderExtras extras) {
        ScopeRenderExtras.withoutCapture(() -> {
        boolean suppressFunctionalExtras = RenderHelper.areDeferredRenderersSuppressed();
        boolean collectingDeferredRenderers = RenderHelper.isCollectingDeferredFunctionalRenderers();
        // Submit callbacks still need the full non-accelerated gun render side effects owned by render(...).
        if (!suppressFunctionalExtras && laserBeamPaths != null) {
            BeamRenderer.renderLaserBeam(currentGunItem, matrixStack, transformType, laserBeamPaths);
        }

        ItemStack attachmentItem = currentAttachmentItem.get(AttachmentType.SCOPE);
        IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
        boolean hasMountedScope = scopePosPath != null && attachmentItem != null && !attachmentItem.isEmpty();
        boolean meshScope = iAttachment != null && TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachmentItem))
                .map(ClientAttachmentIndex::usesMeshRenderModel).orElse(false);
        ScopeRenderDebug.path("gun_model_render_to_buffer", attachmentItem, currentGunItem, transformType,
                "collecting=" + collectingDeferredRenderers + ",mounted=" + hasMountedScope + ",scopePos=" + (scopePosPath != null));
        // Rejected custom scope plans retain the attachment-only optical path. An accepted
        // plan owns its optics and body together, so it must not submit a second local pass.
        if (collectingDeferredRenderers && hasMountedScope
                && (!transformType.firstPerson() || (hasCustomBodyRenderer() && extras == null) || meshScope)) {
            matrixStack.pushPose();
            try {
                for (BedrockPart bedrockPart : scopePosPath) {
                    bedrockPart.translateAndRotateAndScale(matrixStack);
                }
                AttachmentRender.submitMountedAttachment(attachmentItem, currentGunItem, matrixStack, transformType, light, overlay,
                        transformType.firstPerson() && !hasCustomBodyRenderer());
            } finally {
                matrixStack.popPose();
            }
        }
        Runnable render = () -> super.renderToBuffer(matrixStack, transformType, buffer, light, overlay);
        if (extras == null) render.run();
        else extras.capture(render);
        });
    }

    public void collectDeferredFunctionalRenderers(PoseStack matrixStack, ItemStack gunItem, ItemDisplayContext transformType, int light, int overlay) {
        renderToBuffer(matrixStack, gunItem, transformType, RenderHelper.noopVertexConsumer(), light, overlay);
    }

    public boolean submitFirstPersonScopeStencilPass(SubmitNodeCollector collector, PoseStack matrixStack,
                                                     AbstractClientPlayer player, ItemStack gunItem,
                                                     ItemDisplayContext transformType, RenderType gunRenderType,
                                                     Identifier gunTexture, int light, int overlay, int order,
                                                     FirstPersonHandSway handSway, boolean renderHandForCallback) {
        return submitScopeStencilPass(collector, matrixStack, player, gunItem, transformType, gunRenderType,
                gunTexture, light, overlay, order, handSway, renderHandForCallback, false);
    }

    private boolean submitScopeStencilPass(SubmitNodeCollector collector, PoseStack matrixStack,
                                           @Nullable AbstractClientPlayer player, ItemStack gunItem,
                                           ItemDisplayContext transformType, RenderType gunRenderType,
                                           Identifier gunTexture, int light, int overlay, int order,
                                           @Nullable FirstPersonHandSway handSway, boolean renderHandForCallback,
                                           boolean preserveProvidedRenderTypes) {
        if (collector == null || !prepareRenderState(gunItem)) return false;
        ScopePass pass = prepareScopePass(collector, matrixStack, player, gunItem, transformType, gunRenderType,
                gunTexture, light, overlay, order, handSway, renderHandForCallback, preserveProvidedRenderTypes,
                captureRenderState());
        if (pass == null) return false;
        pass.submit(collector);
        return true;
    }

    public PreparedRender prepareRender(SubmitNodeCollector collector, PoseStack matrixStack,
                                         @Nullable AbstractClientPlayer player, ItemStack gunItem,
                                         ItemDisplayContext transformType, RenderType gunRenderType,
                                         Identifier gunTexture, int light, int overlay, int order,
                                         @Nullable FirstPersonHandSway handSway, boolean renderHandForCallback,
                                         boolean preserveProvidedRenderTypes) {
        boolean valid = collector != null && prepareRenderState(gunItem);
        Consumer<Runnable> frameState = valid ? captureRenderState() : Runnable::run;
        ScopePass pass = valid ? prepareScopePass(collector, matrixStack, player, gunItem, transformType,
                gunRenderType, gunTexture, light, overlay, order, handSway, renderHandForCallback,
                preserveProvidedRenderTypes, frameState) : null;
        return new PreparedRender(valid, pass, collector, transformType, light, overlay, frameState,
                handSway, renderHandForCallback);
    }

    @Nullable
    private ScopePass prepareScopePass(SubmitNodeCollector collector, PoseStack matrixStack,
                                        @Nullable AbstractClientPlayer player, ItemStack gunItem,
                                        ItemDisplayContext transformType, RenderType gunRenderType,
                                        Identifier gunTexture, int light, int overlay, int order,
                                        @Nullable FirstPersonHandSway handSway, boolean renderHandForCallback,
                                        boolean preserveProvidedRenderTypes, Consumer<Runnable> gunFrameState) {
        if (!transformType.firstPerson()) return null;
        GunBodyRenderer customRenderer = getBodyRenderer();
        if (customRenderer != null && !customRenderer.isAvailable()) return null;
        ItemStack attachmentItem = currentAttachmentItem.get(AttachmentType.SCOPE);
        IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
        if (scopePosPath == null || attachmentItem == null || attachmentItem.isEmpty() || iAttachment == null) {
            return null;
        }
        Optional<ClientAttachmentIndex> attachmentIndexOptional = TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachmentItem));
        if (attachmentIndexOptional.isEmpty()) {
            return null;
        }
        ClientAttachmentIndex attachmentIndex = attachmentIndexOptional.get();
        boolean meshAppearance = attachmentIndex.usesMeshRenderModel();
        // Mesh attachment appearance has its own early/OIT route, not this ordered solid pass.
        if (customRenderer != null && meshAppearance) return null;
        if (meshAppearance && attachmentIndex.getMeshRenderer() == null) return null;
        BedrockAttachmentModel attachmentModel = attachmentIndex.getAttachmentModel();
        Identifier attachmentTexture = attachmentIndex.getModelTexture();
        if (attachmentModel == null || attachmentTexture == null || (!attachmentIndex.isScope() && !attachmentIndex.isSight())) {
            ScopeRenderDebug.resolvedAttachment(attachmentItem, gunItem, transformType, attachmentIndex,
                    attachmentTexture, attachmentModel != null, false, "missing_scope_stencil_model_or_texture");
            return null;
        }
        if (!ScopeStencilFeatureRenderer.canStageIntegratedPass(attachmentModel, meshAppearance)) {
            ScopeRenderDebug.resolvedAttachment(attachmentItem, gunItem, transformType, attachmentIndex,
                    attachmentTexture, true, false, "integrated_scope_gun_missing_required_paths");
            return null;
        }

        RenderType attachmentRenderType = RenderTypes.entityCutout(attachmentTexture);
        int activeScopeViewIndex = resolveActiveScopeViewIndex(iAttachment, attachmentItem, attachmentIndex);
        ScopeRenderDebug.resolvedAttachment(attachmentItem, gunItem, transformType, attachmentIndex,
                attachmentTexture, true, true, "integrated_scope_gun_feature");

        OrderedSubmitNodeCollector orderedCollector = collector.order(order - 100_000);
        if (!(orderedCollector instanceof SubmitNodeCollection collection)) {
            ScopeRenderDebug.resolvedAttachment(attachmentItem, gunItem, transformType, attachmentIndex,
                    attachmentTexture, true, false, "scope_stencil_feature_collector_unavailable");
            return null;
        }

        PoseStack gunPose = copyPose(matrixStack);
        GunBodyRenderer.PreparedScopeBody customBody = customRenderer == null ? null
                : prepareCustomScopeBody(customRenderer, gunFrameState, handSway, gunPose, gunItem,
                        transformType, light, overlay);
        if (customRenderer != null && customBody == null) return null;
        Consumer<Runnable> attachmentFrameState = attachmentModel.captureRenderState();

        var scopeSubmit = new ScopeStencilFeatureRenderer.ScopeSubmit(
                this,
                attachmentModel,
                player,
                gunItem.copy(),
                attachmentItem.copy(),
                transformType,
                gunRenderType,
                attachmentRenderType,
                gunTexture,
                attachmentTexture,
                new Matrix4f(gunPose.last().pose()),
                new Matrix3f(gunPose.last().normal()),
                handSway,
                renderHandForCallback,
                meshAppearance,
                activeScopeViewIndex,
                light,
                overlay,
                preserveProvidedRenderTypes,
                new ScopeRenderExtras(collector, action -> gunFrameState.accept(() -> attachmentFrameState.accept(action))),
                customBody
        );
        return new ScopePass(collection, scopeSubmit);
    }

    @Nullable
    private GunBodyRenderer.PreparedScopeBody prepareCustomScopeBody(GunBodyRenderer renderer,
            Consumer<Runnable> frameState, @Nullable FirstPersonHandSway sway, PoseStack pose, ItemStack gun,
            ItemDisplayContext context, int light, int overlay) {
        try {
            GunBodyRenderer.PreparedScopeBody[] captured = {null};
            Runnable prepare = () -> captured[0] = renderer.prepareScopeBody(this, copyPose(pose), gun,
                    context, light, overlay);
            frameState.accept(() -> {
                if (sway == null) prepare.run();
                else sway.withTemporaryModelSway(getRootNode(), prepare);
            });
            return captured[0] != null && captured[0].isAvailable() ? captured[0] : null;
        } catch (RuntimeException exception) {
            bodyRenderer.compareAndSet(renderer, null);
            GunMod.LOGGER.warn("Disabling failed custom gun body scope preparation; declared mesh displays remain unavailable", exception);
            return null;
        }
    }

    private record ScopePass(SubmitNodeCollection collection, ScopeStencilFeatureRenderer.ScopeSubmit submit) {
        void submit(SubmitNodeCollector collector) {
            ScopeRenderExtras.withoutCapture(() -> {
                collection.solid.submit(submit);
                submit.submitIntegratedSemantics(collector);
            });
        }
    }

    /** One resolved frame plan: functional side effects are never retried after acceptance. */
    public final class PreparedRender {
        private final boolean valid;
        @Nullable private final ScopePass scope;
        private final SubmitNodeCollector collector;
        private final ItemDisplayContext transformType;
        private final int light;
        private final int overlay;
        private final Consumer<Runnable> frameState;
        @Nullable private final FirstPersonHandSway handSway;
        private final boolean renderHands;
        private boolean collected;
        private boolean collectionComplete;
        private boolean submitted;

        private PreparedRender(boolean valid, @Nullable ScopePass scope, SubmitNodeCollector collector,
                               ItemDisplayContext transformType, int light, int overlay, Consumer<Runnable> frameState,
                               @Nullable FirstPersonHandSway handSway, boolean renderHands) {
            this.valid = valid;
            this.scope = scope;
            this.collector = collector;
            this.transformType = transformType;
            this.light = light;
            this.overlay = overlay;
            this.frameState = frameState;
            this.handSway = handSway;
            this.renderHands = renderHands;
        }

        public void collectDeferredFunctionalRenderers(PoseStack pose) {
            if (collected) throw new IllegalStateException("Gun functional renderers already collected");
            collected = true;
            if (!valid) {
                collectionComplete = true;
                return;
            }
            Runnable collect = () -> RenderHelper.withSubmitNodeCollector(collector, () ->
                    RenderHelper.withDeferredFunctionalRendererCollection(() ->
                            renderPreparedToBuffer(copyPose(pose), transformType, RenderHelper.noopVertexConsumer(), light,
                                    overlay, scope == null ? null : scope.submit.extras())));
            frameState.accept(() -> {
                renderHand = renderHands;
                if (handSway == null) collect.run();
                else handSway.withTemporaryModelSway(getRootNode(), collect);
            });
            collectionComplete = true;
        }

        public boolean submitScope() {
            if (!collectionComplete || submitted) throw new IllegalStateException("Gun render plan must be collected and submitted once");
            submitted = true;
            if (scope == null) return false;
            scope.submit(collector);
            return true;
        }

        public void renderGunBodyToBuffer(PoseStack pose, VertexConsumer buffer) {
            if (!valid) return;
            frameState.accept(() -> {
                renderHand = renderHands;
                Runnable render = () -> RenderHelper.withDeferredRenderersSuppressed(() ->
                        BedrockGunModel.this.renderGunBodyToBuffer(pose, transformType, buffer, light, overlay));
                if (handSway == null) render.run();
                else handSway.withTemporaryModelSway(getRootNode(), render);
            });
        }
    }

    Consumer<Runnable> captureRenderState() {
        ScopeModelState parts = ScopeModelState.capture(this);
        ItemStack gun = currentGunItem == null ? null : currentGunItem.copy();
        EnumMap<AttachmentType, ItemStack> attachments = new EnumMap<>(AttachmentType.class);
        currentAttachmentItem.forEach((type, stack) -> attachments.put(type, stack.copy()));
        Set<String> adapters = Set.copyOf(adapterToRender);
        int magazine = currentExtendMagLevel;
        boolean mount = renderMount;
        boolean hands = renderHand;
        return action -> {
            ItemStack previousGun = currentGunItem;
            EnumMap<AttachmentType, ItemStack> previousAttachments = new EnumMap<>(currentAttachmentItem);
            Set<String> previousAdapters = new HashSet<>(adapterToRender);
            int previousMagazine = currentExtendMagLevel;
            boolean previousMount = renderMount;
            boolean previousHands = renderHand;
            List<IFunctionalRenderer> previousDelegates = delegateRenderers;
            currentGunItem = gun;
            currentAttachmentItem.clear();
            currentAttachmentItem.putAll(attachments);
            adapterToRender.clear();
            adapterToRender.addAll(adapters);
            currentExtendMagLevel = magazine;
            renderMount = mount;
            renderHand = hands;
            delegateRenderers = new ArrayList<>();
            try {
                parts.withState(action);
            } finally {
                currentGunItem = previousGun;
                currentAttachmentItem.clear();
                currentAttachmentItem.putAll(previousAttachments);
                adapterToRender.clear();
                adapterToRender.addAll(previousAdapters);
                currentExtendMagLevel = previousMagazine;
                renderMount = previousMount;
                renderHand = previousHands;
                delegateRenderers = previousDelegates;
            }
        };
    }

    private static int resolveActiveScopeViewIndex(IAttachment attachment, ItemStack attachmentItem,
                                                   ClientAttachmentIndex attachmentIndex) {
        int[] views = attachmentIndex.getViews();
        if (views.length == 0) {
            return -1;
        }
        int zoomNumber = attachment.getZoomNumber(attachmentItem);
        return views[Math.floorMod(zoomNumber, views.length)] - 1;
    }

    void renderGunBodyToBuffer(PoseStack matrixStack, ItemDisplayContext transformType, VertexConsumer buffer,
                               int light, int overlay) {
        super.renderToBuffer(matrixStack, transformType, buffer, light, overlay);
    }

    private static PoseStack copyPose(PoseStack source) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(source.last().pose());
        copy.last().normal().set(source.last().normal());
        return copy;
    }

    private boolean prepareRenderState(ItemStack gunItem) {
        IGun iGun = IGun.getIGunOrNull(gunItem);
        if (iGun == null) {
            return false;
        }
        currentGunItem = gunItem;
        currentExtendMagLevel = 0;
        adapterToRender.clear();
        // 更新配件物品的缓存，以供渲染使用
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) {
                continue;
            }
            ItemStack attachmentItem = iGun.getAttachment(gunItem, type);
            if (attachmentItem.isEmpty()) {
                attachmentItem = iGun.getBuiltinAttachment(gunItem, type);
            }
            currentAttachmentItem.put(type, attachmentItem);
            IAttachment attachment = IAttachment.getIAttachmentOrNull(attachmentItem);
            if (attachment != null) {
                TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(attachmentItem)).ifPresent(index -> {
                    // 读取扩容等级，为扩容弹匣渲染做准备
                    if (type == AttachmentType.EXTENDED_MAG) {
                        currentExtendMagLevel = index.getData().getExtendedMagLevel();
                    }
                    // 读取瞄具 Mount 的渲染需求
                    if (type == AttachmentType.SCOPE) {
                        renderMount = index.isShowMount();
                    }
                    // 添加需要渲染的转接口
                    if (index.getAdapterNodeName() != null) {
                        adapterToRender.add(index.getAdapterNodeName());
                    }
                });
            }
        }
        return true;
    }

    public void renderAccelerated(PoseStack matrixStack, ItemStack gunItem, ItemDisplayContext transformType,
                                  RenderType renderType, int light, int overlay) {
        render(matrixStack, gunItem, transformType, renderType, light, overlay);
    }

    @Nullable
    private IFunctionalRenderer ammoHiddenRender(BedrockPart bedrockPart, Predicate<IGun> predicate) {
        IGun iGun = IGun.getIGunOrNull(currentGunItem);
        if (iGun != null) {
            bedrockPart.visible = predicate.test(iGun);
        }
        return null;
    }

    @Nullable
    private IFunctionalRenderer scopeHiddenRender(BedrockPart bedrockPart, Predicate<ItemStack> predicate) {
        // 安装瞄具时可见
        ItemStack scopeItem = currentAttachmentItem.get(AttachmentType.SCOPE);
        bedrockPart.visible = predicate.test(scopeItem);
        return null;
    }

    @Nullable
    private IFunctionalRenderer extendedMagHiddenRender(BedrockPart bedrockPart, int level) {
        bedrockPart.visible = currentExtendMagLevel == level;
        return null;
    }

    @Override
    public AnimationListener supplyListeners(String nodeName, ObjectAnimationChannel.ChannelType type) {
        AnimationListener listener = super.supplyListeners(nodeName, type);
        if (listener == null) {
            return null;
        }
        if (nodeName.equals(MAG_ADDITIONAL_NODE)) {
            // 额外弹匣只有当动画中有它的关键帧的时候才渲染
            return new ModelAdditionalMagazineListener(listener, this);
        }
        return listener;
    }

    @Override
    public void cleanAnimationTransform() {
        super.cleanAnimationTransform();
        if (additionalMagazineNode != null) {
            additionalMagazineNode.visible = false;
        }
    }

    public EnumMap<AttachmentType, ItemStack> getCurrentAttachmentItem() {
        return currentAttachmentItem;
    }

    public ItemStack getCurrentGunItem() {
        return currentGunItem;
    }

    @Nullable
    public BedrockPart getAdditionalMagazineNode() {
        return additionalMagazineNode;
    }

    @Nullable
    public List<BedrockPart> getIronSightPath() {
        return ironSightPath;
    }

    @Nullable
    public List<BedrockPart> getIdleSightPath() {
        return idleSightPath;
    }

    @Nullable
    public List<BedrockPart> getThirdPersonHandOriginPath() {
        return thirdPersonHandOriginPath;
    }

    @Nullable
    public List<BedrockPart> getFixedOriginPath() {
        return fixedOriginPath;
    }

    @Nullable
    public List<BedrockPart> getGroundOriginPath() {
        return groundOriginPath;
    }

    @Nullable
    public List<BedrockPart> getMuzzleFlashPosPath() {
        return muzzleFlashPosPath;
    }

    @Nullable
    public List<BedrockPart> getLeftHandPosPath() {
        return leftHandPosPath;
    }

    @Nullable
    public List<BedrockPart> getRightHandPosPath() {
        return rightHandPosPath;
    }

    @Nullable
    public List<BedrockPart> getScopePosPath() {
        return scopePosPath;
    }

    @Nullable
    public List<BedrockPart> getRefitAttachmentViewPath(AttachmentType type) {
        return refitAttachmentViewPath.get(type);
    }

    @Nullable
    public ShellRender getShellRender(int index) {
        if (index < 0 || index >= shellRenderList.size()) {
            return null;
        }
        return shellRenderList.get(index);
    }

    @Nullable
    public BedrockPart getRootNode() {
        return root;
    }

    public boolean getRenderHand() {
        return renderHand;
    }

    public void setRenderHand(boolean renderHand) {
        this.renderHand = renderHand;
    }
}
