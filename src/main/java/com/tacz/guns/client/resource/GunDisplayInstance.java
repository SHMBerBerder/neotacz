package com.tacz.guns.client.resource;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.Animations;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.gltf.AnimationStructure;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.client.animation.statemachine.LuaStateMachineFactory;
import com.tacz.guns.api.client.other.GunModelTypeManager;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimationFile;
import com.tacz.guns.client.resource.pojo.display.LaserConfig;
import com.tacz.guns.client.resource.pojo.display.ammo.AmmoParticle;
import com.tacz.guns.client.resource.pojo.display.gun.*;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.sound.GunSoundPreload;
import com.tacz.guns.config.client.ResourceConfig;
import com.tacz.guns.sound.SoundManager;
import com.tacz.guns.util.ColorHex;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * 经过处理和校验的枪械显示数据
 */
public class GunDisplayInstance {
    static final int LOAD_MODEL = 1, LOAD_LOD = 2, LOAD_RUNTIME = 4;

    private static final class ParticleLookupHolder {
        // Displays without ammo particles do not need the built-in particle registry.
        private static final HolderLookup.Provider VALUE = HolderLookup.Provider.create(
                Stream.of((HolderLookup.RegistryLookup<?>) BuiltInRegistries.PARTICLE_TYPE));
    }

    private final Identifier displayId;
    private final GunDisplay display;
    private final boolean forceAsync;
    private final Object guiPendingIdentity = new Object();
    private final Object loadLock = new Object();
    // Scheduling must never wait for a decoder or animation builder holding loadLock.
    private final Object warmUpLock = new Object();
    private volatile boolean invalidated;

    // 加载标志位
    private volatile boolean modelLoaded = false;
    private volatile boolean lodLoaded = false;
    private volatile boolean animationLoaded = false;

    // 错误标志位
    private volatile boolean modelLoadFailed = false;
    private volatile boolean lodLoadFailed = false;
    private volatile boolean animationLoadFailed = false;
    private volatile Exception gltfBodyLoadFailure;

    // 异步加载任务
    private volatile CompletableFuture<Void> modelWarmUpTask = null;
    private volatile CompletableFuture<Void> lodWarmUpTask = null;
    private volatile CompletableFuture<Void> animationWarmUpTask = null;

    private String thirdPersonAnimation = "empty";
    private BedrockGunModel gunModel;
    private @Nullable Pair<BedrockGunModel, Identifier> lodModel;

    private LuaAnimationStateMachine<GunAnimationStateContext> animationStateMachine;
    private @Nullable LuaTable stateMachineParam;

    private @Nullable Identifier playerAnimator3rd = Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "rifle_default.player_animation");
    private boolean is3rdFixedHand = false;
    private Map<String, Identifier> sounds = Maps.newHashMap();
    private List<Identifier> preloadSounds = Lists.newArrayList();
    private GunTransform transform = GunTransform.getDefault();

    private Identifier modelTexture;
    private Identifier slotTexture = MissingTextureAtlasSprite.getLocation();
    private Identifier hudTexture = MissingTextureAtlasSprite.getLocation();
    private @Nullable Identifier hudEmptyTexture;

    private @Nullable ShellEjection shellEjection;
    private @Nullable MuzzleFlash muzzleFlash;
    private LayerGunShow offhandShow = new LayerGunShow();
    private @Nullable Int2ObjectArrayMap<LayerGunShow> hotbarShow;
    private float ironZoom;
    private float zoomModelFov;
    private boolean showCrosshair = false;
    private @Nullable AmmoParticle particle;
    private float @Nullable [] tracerColor = null;
    private EnumMap<FireMode, ControllableData> controllableData = new EnumMap<>(FireMode.class);
    private AmmoCountStyle ammoCountStyle = AmmoCountStyle.NORMAL;
    private DamageStyle damageStyle = DamageStyle.PER_PROJECTILE;
    private @Nullable LaserConfig laserConfig;
    private boolean enableTransparency;

    GunDisplayInstance(Identifier displayId, GunDisplay display) {
        this.displayId = displayId;
        this.display = Objects.requireNonNull(display, "display");
        this.forceAsync = false;
        initBase(display);
        if (!ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.get()) {
            ensureAnimationLoaded();
        }
    }

    public static GunDisplayInstance create(Identifier displayId, GunDisplay display)  throws IllegalArgumentException {
        return new GunDisplayInstance(displayId, display);
    }

    private GunDisplayInstance(GunDisplayInstance source) {
        // Quality-only rebuilding preserves validated metadata without repeating sound/particle side effects.
        displayId = source.displayId;
        display = source.display;
        forceAsync = true;
        thirdPersonAnimation = source.thirdPersonAnimation;
        playerAnimator3rd = source.playerAnimator3rd;
        is3rdFixedHand = source.is3rdFixedHand;
        sounds = new HashMap<>(source.sounds);
        preloadSounds = new ArrayList<>(source.preloadSounds);
        transform = source.transform;
        slotTexture = source.slotTexture;
        hudTexture = source.hudTexture;
        hudEmptyTexture = source.hudEmptyTexture;
        shellEjection = source.shellEjection;
        muzzleFlash = source.muzzleFlash;
        offhandShow = source.offhandShow;
        hotbarShow = source.hotbarShow == null ? null : new Int2ObjectArrayMap<>(source.hotbarShow);
        ironZoom = source.ironZoom;
        zoomModelFov = source.zoomModelFov;
        showCrosshair = source.showCrosshair;
        particle = source.particle;
        tracerColor = source.tracerColor == null ? null : source.tracerColor.clone();
        controllableData = source.controllableData == null ? null : source.controllableData.clone();
        ammoCountStyle = source.ammoCountStyle;
        damageStyle = source.damageStyle;
        laserConfig = source.laserConfig;
        enableTransparency = source.enableTransparency;
    }

    GunDisplayInstance deferredCopy() {
        return new GunDisplayInstance(this);
    }

    int requestedLoads() {
        synchronized (warmUpLock) {
            if (invalidated) return 0;
            int requested = modelLoaded || modelLoadFailed || modelWarmUpTask != null ? LOAD_MODEL : 0;
            if (lodLoaded || lodLoadFailed || lodWarmUpTask != null) requested |= LOAD_LOD;
            if (animationLoaded || animationLoadFailed || animationWarmUpTask != null) requested |= LOAD_MODEL | LOAD_RUNTIME;
            return invalidated ? 0 : requested;
        }
    }

    List<CompletableFuture<Void>> warmUpForReload(int requests) {
        if ((requests & ~(LOAD_MODEL | LOAD_LOD | LOAD_RUNTIME)) != 0) {
            throw new IllegalArgumentException("Unknown gun asset load request");
        }
        if ((requests & LOAD_RUNTIME) != 0) requests |= LOAD_MODEL;
        List<CompletableFuture<Void>> tasks = new ArrayList<>(3);
        synchronized (warmUpLock) {
            if ((requests & LOAD_MODEL) != 0) tasks.add(reloadCompletion(LOAD_MODEL, scheduleModelWarmUpLocked()));
            if ((requests & LOAD_LOD) != 0) tasks.add(reloadCompletion(LOAD_LOD, scheduleLodWarmUpLocked()));
            if ((requests & LOAD_RUNTIME) != 0) tasks.add(reloadCompletion(LOAD_RUNTIME, scheduleAnimationWarmUpLocked()));
        }
        return List.copyOf(tasks);
    }

    private CompletableFuture<Void> reloadCompletion(int stage, CompletableFuture<Void> task) {
        // The observer must neither cancel shared work nor mistake a skipped/failed task for readiness.
        return task.thenRun(() -> {
            if (invalidated) throw new CancellationException("Gun display was invalidated: " + displayId);
            boolean ready = switch (stage) {
                case LOAD_MODEL -> modelLoaded;
                case LOAD_LOD -> lodLoaded;
                case LOAD_RUNTIME -> animationLoaded;
                default -> false;
            };
            if (!ready) throw new IllegalStateException("Gun asset stage " + stage + " is not ready: " + displayId);
            if (stage == LOAD_MODEL && gltfBodyLoadFailure != null) throw new CompletionException(gltfBodyLoadFailure);
            if (invalidated) throw new CancellationException("Gun display was invalidated: " + displayId);
        });
    }

    // 后台预热
    public void invalidate() {
        // Never wait for loadLock here: a background decode may hold it while the render thread applies settings.
        invalidated = true;
        if (modelWarmUpTask != null) modelWarmUpTask.cancel(false);
        if (lodWarmUpTask != null) lodWarmUpTask.cancel(false);
        if (animationWarmUpTask != null) animationWarmUpTask.cancel(false);
    }

    public void warmUpModel() {
        if (invalidated) return;
        if (!forceAsync && !ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.get()) {
            ensureModelLoaded();
            return;
        }
        if (modelLoaded || modelLoadFailed || modelWarmUpTask != null) {
            return;
        }
        synchronized (warmUpLock) {
            if (invalidated || modelLoaded || modelLoadFailed || modelWarmUpTask != null) {
                return;
            }
            scheduleModelWarmUpLocked();
        }
    }

    public void warmUpLod() {
        if (invalidated) return;
        if (!forceAsync && !ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.get()) {
            ensureLodLoaded();
            return;
        }
        if (lodLoaded || lodLoadFailed || lodWarmUpTask != null) {
            return;
        }
        synchronized (warmUpLock) {
            if (invalidated || lodLoaded || lodLoadFailed || lodWarmUpTask != null) {
                return;
            }
            scheduleLodWarmUpLocked();
        }
    }

    public void warmUpRuntime() {
        if (invalidated) return;
        if (!forceAsync && !ResourceConfig.ENABLE_LAZY_CLIENT_ASSET_LOAD.get()) {
            ensureAnimationLoaded();
            return;
        }
        if (animationLoaded || animationLoadFailed || modelLoadFailed || animationWarmUpTask != null) {
            return;
        }
        synchronized (warmUpLock) {
            if (invalidated || animationLoaded || animationLoadFailed || modelLoadFailed || animationWarmUpTask != null) {
                return;
            }
            scheduleAnimationWarmUpLocked();
        }
    }

    // Synchronous loading is reserved for explicitly disabled lazy loading.
    private void ensureModelLoaded() {
        if (invalidated) return;
        if (modelLoaded || modelLoadFailed) {
            return;
        }
        CompletableFuture<Void> task = modelWarmUpTask;
        if (task == null) {
            try {
                loadModelIfNecessary();
            } catch (Throwable throwable) {
                handleModelLoadFailure(throwable);
            }
            return;
        }
        try {
            task.join();
        } catch (CompletionException | CancellationException exception) {
            handleModelLoadFailure(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private void ensureLodLoaded() {
        if (invalidated) return;
        if (lodLoaded || lodLoadFailed) {
            return;
        }
        CompletableFuture<Void> task = lodWarmUpTask;
        if (task == null) {
            try {
                loadLodIfNecessary();
            } catch (Throwable throwable) {
                handleLodLoadFailure(throwable);
            }
            return;
        }
        try {
            task.join();
        } catch (CompletionException | CancellationException exception) {
            handleLodLoadFailure(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private void ensureAnimationLoaded() {
        if (invalidated) return;
        if (animationLoaded || animationLoadFailed) {
            return;
        }
        ensureModelLoaded();
        if (!modelLoaded) {
            if (!invalidated && modelLoadFailed) animationLoadFailed = true;
            return;
        }
        CompletableFuture<Void> task = animationWarmUpTask;
        if (task == null) {
            try {
                loadAnimationAfterModelReady();
            } catch (Throwable throwable) {
                handleAnimationLoadFailure(throwable);
            }
            return;
        }
        try {
            task.join();
        } catch (CompletionException | CancellationException exception) {
            handleAnimationLoadFailure(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    // 加载任务
    private void loadModelIfNecessary() {
        if (modelLoaded || modelLoadFailed || invalidated) {
            return;
        }
        synchronized (loadLock) {
            if (modelLoaded || modelLoadFailed || invalidated) {
                return;
            }
            checkTextureAndModel(display);
            if (invalidated) return;
            checkTextShow(display);
            if (invalidated) return;
            modelLoaded = true;
        }
    }

    private void loadLodIfNecessary() {
        if (lodLoaded || lodLoadFailed || invalidated) {
            return;
        }
        synchronized (loadLock) {
            if (lodLoaded || lodLoadFailed || invalidated) {
                return;
            }
            checkLod(display);
            if (invalidated) return;
            lodLoaded = true;
        }
    }

    private CompletableFuture<Void> scheduleModelWarmUpLocked() {
        if (invalidated || modelLoaded || modelLoadFailed) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> task = modelWarmUpTask;
        if (task == null) {
            task = CompletableFuture.runAsync(this::loadModelIfNecessary, ClientAssetLoadDispatcher.executor());
            modelWarmUpTask = task;
            task.whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    handleModelLoadFailure(throwable);
                }
            });
        }
        // An already-failed task may clear its field during whenComplete registration.
        return task;
    }

    private CompletableFuture<Void> scheduleLodWarmUpLocked() {
        if (invalidated || lodLoaded || lodLoadFailed) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> task = lodWarmUpTask;
        if (task == null) {
            task = CompletableFuture.runAsync(this::loadLodIfNecessary, ClientAssetLoadDispatcher.executor());
            lodWarmUpTask = task;
            task.whenComplete((unused, throwable) -> {
                if (throwable != null) handleLodLoadFailure(throwable);
            });
        }
        return task;
    }

    private CompletableFuture<Void> scheduleAnimationWarmUpLocked() {
        if (invalidated || animationLoaded || animationLoadFailed || modelLoadFailed) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> task = animationWarmUpTask;
        if (task == null) {
            // 动画任务尝试挂到模型加载后面
            CompletableFuture<Void> modelTask = scheduleModelWarmUpLocked();
            task = modelTask.thenRunAsync(this::loadAnimationAfterModelReady, ClientAssetLoadDispatcher.executor());
            animationWarmUpTask = task;
            task.whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    handleAnimationLoadFailure(throwable);
                }
            });
        }
        return task;
    }

    private void loadAnimationAfterModelReady() {
        if (animationLoaded || animationLoadFailed || invalidated) {
            return;
        }
        synchronized (loadLock) {
            if (animationLoaded || animationLoadFailed || invalidated) {
                return;
            }
            if (!modelLoaded) {
                throw new IllegalStateException("Gun animation requires model to be loaded first");
            }
            checkAnimation(display);
            if (invalidated) return;
            animationLoaded = true;
        }
    }

    private void handleModelLoadFailure(Throwable throwable) {
        if (invalidated) return;
        boolean shouldLog = false;
        synchronized (warmUpLock) {
            if (!invalidated && !modelLoaded) {
                shouldLog = !modelLoadFailed;
                modelWarmUpTask = null;
                modelLoadFailed = true;
            }
        }
        if (shouldLog) {
            GunMod.LOGGER.warn("Failed to load gun model {}", displayId, throwable);
        }
    }

    private void handleLodLoadFailure(Throwable throwable) {
        if (invalidated) return;
        boolean shouldLog = false;
        synchronized (warmUpLock) {
            if (!invalidated && !lodLoaded) {
                shouldLog = !lodLoadFailed;
                lodWarmUpTask = null;
                lodLoadFailed = true;
            }
        }
        if (shouldLog) {
            GunMod.LOGGER.warn("Failed to load gun lod {}", displayId, throwable);
        }
    }

    private void handleAnimationLoadFailure(Throwable throwable) {
        if (invalidated) return;
        boolean shouldLog = false;
        synchronized (warmUpLock) {
            if (!invalidated && !animationLoaded) {
                shouldLog = !animationLoadFailed;
                animationWarmUpTask = null;
                animationLoadFailed = true;
            }
        }
        if (shouldLog) {
            GunMod.LOGGER.warn("Failed to load gun animation runtime {}", displayId, throwable);
        }
    }

    // 基础数据直接读
    private void initBase(GunDisplay display) {
        checkSlotTexture(display);
        checkHUDTexture(display);
        checkSounds(display);
        checkTransform(display);
        checkShellEjection(display);
        checkGunAmmo(display);
        checkMuzzleFlash(display);
        checkLayerGunShow(display);
        checkIronZoom(display);
        checkZoomModelFov(display);
        thirdPersonAnimation = StringUtils.defaultIfBlank(display.getThirdPersonAnimation(), "empty");
        if (display.getPlayerAnimator3rd() != null) {
            playerAnimator3rd = display.getPlayerAnimator3rd();
            is3rdFixedHand = display.is3rdFixedHand();
        }
        showCrosshair = display.isShowCrosshair();
        controllableData = display.getControllableData();
        ammoCountStyle = display.getAmmoCountStyle();
        damageStyle = display.getDamageStyle();
        laserConfig = display.getLaserConfig();
        enableTransparency = display.enablesTransparency();
    }

    private void checkIronZoom(GunDisplay display) {
        ironZoom = display.getIronZoom();
        if (ironZoom < 1) {
            ironZoom = 1;
        }
    }

    private void checkZoomModelFov(GunDisplay display) {
        zoomModelFov = display.getZoomModelFov();
        if (zoomModelFov > 70) {
            zoomModelFov = 70;
        }
    }

    private void checkTextShow(GunDisplay display) {
        Map<String, TextShow> textShowMap = Maps.newHashMap();
        display.getTextShows().forEach((key, value) -> {
            if (StringUtils.isNoneBlank(key)) {
                int color = ColorHex.colorTextToRbgInt(value.getColorText());
                value.setColorInt(color);
                textShowMap.put(key, value);
            }
        });
        gunModel.setTextShowList(textShowMap);
    }

    private void checkTextureAndModel(GunDisplay display) {
        //获取模型类型
        String modelType = display.getModelType();
        BiFunction<BedrockModelPOJO, BedrockVersion, ? extends BedrockGunModel> constructor = GunModelTypeManager.getModelInstanceConstructor(modelType);
        // 检查模型
        Identifier modelLocation = display.getModelLocation();
        Preconditions.checkArgument(modelLocation != null, "display object missing model field");
        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelLocation);
        Preconditions.checkArgument(modelPOJO != null, "there is no corresponding model file");
        // 检查默认材质是否存在
        Identifier textureLocation = display.getModelTexture();
        Preconditions.checkArgument(textureLocation != null, "missing default texture");
        modelTexture = textureLocation;
        // 先判断是不是 1.10.0 版本基岩版模型文件
        if (BedrockVersion.isLegacyVersion(modelPOJO) && modelPOJO.getGeometryModelLegacy() != null) {
            gunModel = constructor.apply(modelPOJO, BedrockVersion.LEGACY);
        }
        // 判定是不是 1.12.0 版本基岩版模型文件
        if (BedrockVersion.isNewVersion(modelPOJO) && modelPOJO.getGeometryModelNew() != null) {
            gunModel = constructor.apply(modelPOJO, BedrockVersion.NEW);
        }
        Preconditions.checkArgument(gunModel != null, "there is no model data in the model file");
        tryAttachGltfBody(display.getRenderModel(), textShowNodeNames(display));
    }

    private void tryAttachGltfBody(
            @Nullable GunRenderModelConfig config,
            Set<String> additionalProtectedSources
    ) {
        if (config == null) {
            return;
        }
        try {
            config.validate();
            if (!config.isGltf()) {
                return;
            }

            var renderer = GltfRenderModelLoader.load(config, gunModel, additionalProtectedSources, () -> invalidated);
            if (!invalidated && renderer != null) gunModel.setBodyRenderer(renderer);
        } catch (Exception exception) {
            if (invalidated) return;
            gltfBodyLoadFailure = exception;
            GunMod.LOGGER.warn(
                    "Failed to load declared mesh gun body for {}; placeholder body will not be rendered",
                    displayId,
                    exception
            );
        }
    }

    private static Set<String> textShowNodeNames(GunDisplay display) {
        Map<String, TextShow> textShows = display.getTextShows();
        if (textShows == null || textShows.isEmpty()) {
            return Set.of();
        }
        Set<String> nodeNames = new HashSet<>();
        for (String nodeName : textShows.keySet()) {
            if (StringUtils.isNoneBlank(nodeName)) {
                nodeNames.add(nodeName);
            }
        }
        return Set.copyOf(nodeNames);
    }

    private void checkLod(GunDisplay display) {
        if (usesMeshRenderModel()) return;
        GunLod gunLod = display.getGunLod();
        if (gunLod != null) {
            Identifier texture = gunLod.getModelTexture();
            if (gunLod.getModelLocation() == null) {
                return;
            }
            if (texture == null) {
                return;
            }
            BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(gunLod.getModelLocation());
            if (modelPOJO == null) {
                return;
            }
            // 先判断是不是 1.10.0 版本基岩版模型文件
            if (BedrockVersion.isLegacyVersion(modelPOJO) && modelPOJO.getGeometryModelLegacy() != null) {
                BedrockGunModel model = new BedrockGunModel(modelPOJO, BedrockVersion.LEGACY);
                lodModel = Pair.of(model, texture);
            }
            // 判定是不是 1.12.0 版本基岩版模型文件
            if (BedrockVersion.isNewVersion(modelPOJO) && modelPOJO.getGeometryModelNew() != null) {
                BedrockGunModel model = new BedrockGunModel(modelPOJO, BedrockVersion.NEW);
                lodModel = Pair.of(model, texture);
            }
        }
    }

    private void checkAnimation(GunDisplay display) {
        Identifier location = display.getAnimationLocation();
        AnimationController controller;
        if (location == null) {
            controller = new AnimationController(Lists.newArrayList(), gunModel);
        } else {
            AnimationStructure gltfAnimations = ClientAssetsManager.INSTANCE.getGltfAnimation(location);
            BedrockAnimationFile bedrockAnimationFile = ClientAssetsManager.INSTANCE.getBedrockAnimations(location);
            if (bedrockAnimationFile != null) {
                // 用 bedrock 动画资源创建动画控制器
                controller = Animations.createControllerFromBedrock(bedrockAnimationFile, gunModel);
            } else if (gltfAnimations != null) {
                // 用 gltf 动画资源创建动画控制器
                controller = Animations.createControllerFromGltf(gltfAnimations, gunModel);
            } else {
                throw new IllegalArgumentException("animation not found: " + location);
            }
            // 将默认动画填入动画控制器
            Identifier defaultAnimation = display.getDefaultAnimation();
            if (defaultAnimation != null) {
                BedrockAnimationFile animationFile = ClientAssetsManager.INSTANCE.getBedrockAnimations(defaultAnimation);
                if (animationFile == null) {
                    throw new IllegalArgumentException("animation not found: " + defaultAnimation);
                }
                List<ObjectAnimation> animations = Animations.createAnimationFromBedrock(animationFile);
                for (ObjectAnimation animation : animations) {
                    controller.providePrototypeIfAbsent(animation.name, () -> new ObjectAnimation(animation));
                }
            } else {
                DefaultAnimationType defaultAnimationType = display.getDefaultAnimationType();
                if (defaultAnimationType != null) {
                    switch (defaultAnimationType) {
                        case RIFLE -> {
                            for (ObjectAnimation animation : InternalAssetLoader.getDefaultRifleAnimations()) {
                                controller.providePrototypeIfAbsent(animation.name, () -> new ObjectAnimation(animation));
                            }
                        }
                        case PISTOL -> {
                            for (ObjectAnimation animation : InternalAssetLoader.getDefaultPistolAnimations()) {
                                controller.providePrototypeIfAbsent(animation.name, () -> new ObjectAnimation(animation));
                            }
                        }
                    }
                }
            }
        }
        // 初始化动画状态机，将动画控制器封装进去。
        Identifier stateMachineLocation = display.getStateMachineLocation();
        if (stateMachineLocation == null) {
            // 如果没指定状态机，则使用默认状态机
            stateMachineLocation = Identifier.fromNamespaceAndPath("tacz", "default_state_machine");
        }
        LuaTable script = ClientAssetsManager.INSTANCE.getScript(stateMachineLocation);
        if (script != null) {
            animationStateMachine = new LuaStateMachineFactory<GunAnimationStateContext>()
                    .setController(controller)
                    .setLuaScripts(script)
                    .build();
        } else {
            throw new IllegalArgumentException("statemachine not found: " + stateMachineLocation);
        }
        // 加载状态机参数
        Map<String, Object> params = display.getStateMachineParam();
        if (params != null) {
            stateMachineParam = new LuaTable();
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                stateMachineParam.set(entry.getKey(), CoerceJavaToLua.coerce(entry.getValue()));
            }
        }
    }

    private void checkSounds(GunDisplay display) {
        sounds = Maps.newHashMap();
        preloadSounds = Lists.newArrayList();
        Map<String, Identifier> soundMaps = display.getSounds();
        if (soundMaps == null || soundMaps.isEmpty()) {
            return;
        }
        // 部分音效为默认音效，不存在则需要添加默认音效
        soundMaps.putIfAbsent(SoundManager.DRY_FIRE_SOUND, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, SoundManager.DRY_FIRE_SOUND));
        soundMaps.putIfAbsent(SoundManager.FIRE_SELECT, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, SoundManager.FIRE_SELECT));
        soundMaps.putIfAbsent(SoundManager.HEAD_HIT_SOUND, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, SoundManager.HEAD_HIT_SOUND));
        soundMaps.putIfAbsent(SoundManager.FLESH_HIT_SOUND, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, SoundManager.FLESH_HIT_SOUND));
        soundMaps.putIfAbsent(SoundManager.KILL_SOUND, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, SoundManager.KILL_SOUND));
        soundMaps.putIfAbsent(SoundManager.MELEE_BAYONET, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "melee_bayonet/melee_bayonet_01"));
        soundMaps.putIfAbsent(SoundManager.MELEE_STOCK, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "melee_stock/melee_stock_01"));
        soundMaps.putIfAbsent(SoundManager.MELEE_PUSH, Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "melee_stock/melee_stock_02"));
        sounds.putAll(soundMaps);

        Set<Identifier> preloadSet = new LinkedHashSet<>();
        for (String name : GunSoundPreload.DEFAULT_PRELOAD_NAMES) {
            addPreloadSound(preloadSet, name);
        }
        List<String> configuredPreloadSounds = display.getPreloadSounds();
        if (configuredPreloadSounds != null) {
            for (String name : configuredPreloadSounds) {
                addPreloadSound(preloadSet, name);
            }
        }
        preloadSounds.addAll(preloadSet);
    }

    private void addPreloadSound(Set<Identifier> preloadSet, String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        Identifier soundId = sounds.get(name);
        if (soundId != null) {
            preloadSet.add(soundId);
        }
    }

    private void checkTransform(GunDisplay display) {
        GunTransform readTransform = display.getTransform();
        if (readTransform == null || readTransform.getScale() == null) {
            transform = GunTransform.getDefault();
        } else {
            transform = display.getTransform();
        }
    }

    private void checkSlotTexture(GunDisplay display) {
        // 加载 GUI 内枪械图标
        slotTexture = Objects.requireNonNullElseGet(display.getSlotTextureLocation(), MissingTextureAtlasSprite::getLocation);
    }

    private void checkHUDTexture(GunDisplay display) {
        hudTexture = Objects.requireNonNullElseGet(display.getHudTextureLocation(), MissingTextureAtlasSprite::getLocation);
        hudEmptyTexture = display.getHudEmptyTextureLocation();
    }

    private void checkShellEjection(GunDisplay display) {
        shellEjection = display.getShellEjection();
    }

    private void checkGunAmmo(GunDisplay display) {
        GunAmmo displayGunAmmo = display.getGunAmmo();
        if (displayGunAmmo == null) {
            return;
        }
        String tracerColorText = displayGunAmmo.getTracerColor();
        if (StringUtils.isNoneBlank(tracerColorText)) {
            tracerColor = ColorHex.colorTextToRbgFloatArray(tracerColorText);
        }
        AmmoParticle particle = displayGunAmmo.getParticle();
        if (particle != null) {
            try {
                String name = particle.getName();
                if (StringUtils.isNoneBlank()) {
                    particle.setParticleOptions(ParticleArgument.readParticle(new StringReader(name), ParticleLookupHolder.VALUE));
                    Preconditions.checkArgument(particle.getCount() > 0, "particle count must be greater than 0");
                    Preconditions.checkArgument(particle.getLifeTime() > 0, "particle life time must be greater than 0");
                    this.particle = particle;
                }
            } catch (CommandSyntaxException e) {
                e.fillInStackTrace();
            }
        }
    }

    private void checkMuzzleFlash(GunDisplay display) {
        muzzleFlash = display.getMuzzleFlash();
        if (muzzleFlash != null && muzzleFlash.getTexture() == null) {
            muzzleFlash = null;
        }
    }

    private void checkLayerGunShow(GunDisplay display) {
        offhandShow = display.getOffhandShow();
        if (offhandShow == null) {
            offhandShow = new LayerGunShow();
        }
        Map<String, LayerGunShow> show = display.getHotbarShow();
        if (show == null || show.isEmpty()) {
            return;
        }
        hotbarShow = new Int2ObjectArrayMap<>();
        for (String key : show.keySet()) {
            try {
                hotbarShow.put(Integer.parseInt(key), show.get(key));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("index number is error: " + key);
            }
        }
    }

    public @Nullable BedrockGunModel getGunModel() {
        if (invalidated) return null;
        warmUpModel();
        // The volatile ready flag publishes every field written by the background loader.
        return readyGunModel();
    }

    public boolean usesMeshRenderModel() {
        return display.getRenderModel() != null && display.getRenderModel().isGltf();
    }

    private @Nullable BedrockGunModel readyGunModel() {
        if (!modelLoaded || invalidated) return null;
        return usesMeshRenderModel() && (gltfBodyLoadFailure != null || !gunModel.hasCustomBodyRenderer())
                ? null : gunModel;
    }

    /** Atlas identity never owns the model; only the current extraction snapshot may hold it. */
    public record GuiModelSnapshot(Object pendingIdentity, @Nullable BedrockGunModel model,
                                   Identifier slotTexture, boolean meshRequested) { }

    public GuiModelSnapshot guiModelSnapshot() {
        boolean mesh = usesMeshRenderModel();
        if (mesh && !invalidated) {
            // GUI extraction must not synchronously decode even when ordinary lazy loading is disabled.
            synchronized (warmUpLock) { scheduleModelWarmUpLocked(); }
        }
        return new GuiModelSnapshot(guiPendingIdentity, mesh ? readyGunModel() : null,
                mesh ? MissingTextureAtlasSprite.getLocation() : slotTexture, mesh);
    }

    @Nullable
    public Pair<BedrockGunModel, Identifier> getLodModel() {
        if (invalidated || usesMeshRenderModel()) return null;
        warmUpLod();
        return lodLoaded && !invalidated ? lodModel : null;
    }

    public @Nullable LuaAnimationStateMachine<GunAnimationStateContext> getAnimationStateMachine() {
        if (invalidated) return null;
        warmUpRuntime();
        return animationLoaded && !invalidated ? animationStateMachine : null;
    }

    public @Nullable LuaTable getStateMachineParam() {
        if (invalidated) return null;
        warmUpRuntime();
        return animationLoaded && !invalidated ? stateMachineParam : null;
    }

    @Nullable
    public Identifier getSounds(String name) {
        return sounds.get(name);
    }

    public List<Identifier> getPreloadSounds() {
        return preloadSounds;
    }

    public GunTransform getTransform() {
        return transform;
    }

    public Identifier getSlotTexture() {
        return slotTexture;
    }

    public Identifier getHUDTexture() {
        return hudTexture;
    }

    @Nullable
    public Identifier getHudEmptyTexture() {
        return hudEmptyTexture;
    }

    public Identifier getModelTexture() {
        if (invalidated) return MissingTextureAtlasSprite.getLocation();
        warmUpModel();
        return modelLoaded && !invalidated && modelTexture != null
                ? modelTexture : MissingTextureAtlasSprite.getLocation();
    }

    public String getThirdPersonAnimation() {
        return thirdPersonAnimation;
    }

    @Nullable
    public ShellEjection getShellEjection() {
        return shellEjection;
    }

    public float @Nullable [] getTracerColor() {
        return tracerColor;
    }

    @Nullable
    public AmmoParticle getParticle() {
        return particle;
    }

    @Nullable
    public MuzzleFlash getMuzzleFlash() {
        return muzzleFlash;
    }

    public LayerGunShow getOffhandShow() {
        return offhandShow;
    }

    @Nullable
    public Int2ObjectArrayMap<LayerGunShow> getHotbarShow() {
        return hotbarShow;
    }

    public float getIronZoom() {
        return ironZoom;
    }

    public float getZoomModelFov() {
        return zoomModelFov;
    }

    public boolean isShowCrosshair() {
        return showCrosshair;
    }

    public @Nullable Identifier getPlayerAnimator3rd() {
        return playerAnimator3rd;
    }

    public boolean is3rdFixedHand() {
        return is3rdFixedHand;
    }

    public EnumMap<FireMode, ControllableData> getControllableData() {
        return controllableData;
    }

    public AmmoCountStyle getAmmoCountStyle() {
        return ammoCountStyle;
    }

    public DamageStyle getDamageStyle() {
        return damageStyle;
    }

    public @Nullable LaserConfig getLaserConfig() {
        return laserConfig;
    }

    public boolean enablesTransparency() {
        return enableTransparency;
    }
}
