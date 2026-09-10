package com.tacz.guns.client.resource;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonRenderHandler;
import com.tacz.guns.GunMod;
import com.tacz.guns.client.model.gltf.quality.GltfRenderQuality;
import com.tacz.guns.client.model.gltf.render.GltfGunBodyRenderer;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.config.ClientConfig;
import com.tacz.guns.config.client.VideoConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One atomic quality snapshot for UI changes and external config reloads. */
@EventBusSubscriber(value = Dist.CLIENT, modid = GunMod.MOD_ID)
public final class ClientVideoSettings {
    private static volatile GltfRenderQuality activeQuality = VideoConfig.DEFAULT.quality();
    private static LoadingOperation loading;

    enum Change { UNCHANGED, SAVE_ONLY, RELOAD }

    private ClientVideoSettings() { }

    public static GltfRenderQuality quality() {
        return activeQuality;
    }

    static Change change(VideoConfig.Settings initial, VideoConfig.Settings draft, GltfRenderQuality active) {
        // An untouched screen must not overwrite a configuration changed externally since it opened.
        if (draft.equals(initial)) return Change.UNCHANGED;
        return draft.quality().equals(active) ? Change.SAVE_ONLY : Change.RELOAD;
    }

    public static void applyWithLoading(VideoConfig.Settings initial, VideoConfig.Settings draft, Screen returnTo) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> applyWithLoading(initial, draft, returnTo));
            return;
        }
        if (loading != null || client.gui.overlay() != null) return;
        Change change = change(initial, draft, activeQuality);
        if (change == Change.UNCHANGED) {
            client.gui.setScreen(returnTo);
        } else if (change == Change.SAVE_ONLY) {
            try {
                save(draft);
                client.gui.setScreen(returnTo);
            } catch (RuntimeException failure) {
                showFailure(client, returnTo, failure);
            }
        } else {
            LoadingOperation operation = new LoadingOperation(client, draft, returnTo);
            loading = operation;
            client.gui.setScreen(operation.waiting);
            client.gui.setOverlay(operation.overlay);
        }
    }

    public static void apply(VideoConfig.Settings settings) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> apply(settings));
            return;
        }
        save(settings);
        applyConfigured();
    }

    private static void save(VideoConfig.Settings settings) {
        VideoConfig.Settings previous = VideoConfig.settings();
        VideoConfig.set(settings);
        try {
            ClientConfig.SPEC.save();
        } catch (RuntimeException failure) {
            VideoConfig.set(previous);
            throw failure;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LoadingOperation operation = loading;
        if (operation != null && !operation.ownsScreen()) {
            // Disconnects, another screen or another overlay own navigation now; never resurrect the old settings.
            operation.reload.cancel();
            loading = null;
            if (operation.client.gui.overlay() == operation.overlay) operation.client.gui.setOverlay(null);
            if (operation.client.gui.screen() == operation.waiting) operation.client.gui.setScreen(null);
        }
    }

    @SubscribeEvent
    public static void onLoading(ModConfigEvent.Loading event) {
        if (isClientConfig(event)) {
            activeQuality = VideoConfig.settings().quality();
        }
    }

    @SubscribeEvent
    public static void onReloading(ModConfigEvent.Reloading event) {
        if (isClientConfig(event)) {
            // SPEC.save posts Reloading synchronously; enqueue even on the client thread to avoid reentrant publication.
            Minecraft.getInstance().schedule(ClientVideoSettings::applyConfigured);
        }
    }

    private static boolean isClientConfig(ModConfigEvent event) {
        return event.getConfig().getType() == ModConfig.Type.CLIENT
                && GunMod.MOD_ID.equals(event.getConfig().getModId());
    }

    private static void applyConfigured() {
        GltfRenderQuality next = VideoConfig.settings().quality();
        if (next.equals(activeQuality)) return;
        var manager = ClientAssetsManager.INSTANCE.getGltfModelManager();
        if (manager != null) {
            var staged = ClientIndexManager.stageQualityReload();
            publish(next, staged, manager, false);
            staged.warmUp();
        } else {
            activeQuality = next;
        }
    }

    private static void publish(GltfRenderQuality next, ClientIndexManager.QualityReload staged,
                                GltfModelManager manager, boolean strict) {
        publish(next, staged, manager, strict, FirstPersonRenderHandler::reset);
    }

    static void publish(GltfRenderQuality next, ClientIndexManager.QualityReload staged,
                        GltfModelManager manager, boolean strict, Runnable resetFirstPerson) {
        // Metadata staging is all-or-nothing. Publish the complete table before fallible GPU disposal.
        staged.publish();
        activeQuality = next;
        resetFirstPerson.run();
        if (strict) manager.clearCacheForQuality();
        else manager.clearCache();
        GunMod.LOGGER.info("NeoTaCZ video quality applied: {}, resource generation {}", next, manager.getGeneration());
    }

    private static void showFailure(Minecraft client, Screen returnTo, Throwable failure) {
        GunMod.LOGGER.error("NeoTaCZ video quality resource loading failed", failure);
        client.gui.setScreen(new AlertScreen(() -> client.gui.setScreen(returnTo),
                Component.translatable("gui.tacz.video.load_failed"),
                Component.translatable("gui.tacz.video.load_failed.detail"), CommonComponents.GUI_BACK, false));
    }

    static VideoQualityReload.Target attachmentTarget(net.minecraft.resources.Identifier id,
            com.tacz.guns.client.resource.index.ClientAttachmentIndex attachment) {
        return new VideoQualityReload.Target(attachment.warmUpMeshForReload(), () -> {
            var mesh = attachment.getMeshRenderer();
            if (mesh == null) throw new IllegalStateException("Prepared attachment mesh is no longer available: " + id);
            return mesh.prepareResourceUploads();
        });
    }

    private static final class LoadingOperation {
        private final Minecraft client;
        private final VideoConfig.Settings settings;
        private final Screen returnTo;
        private final Screen waiting = new GenericMessageScreen(CommonComponents.EMPTY);
        private final Object level;
        private final GltfModelManager manager;
        private final VideoQualityReload reload;
        private final LoadingOverlay overlay;
        private long generation;

        private LoadingOperation(Minecraft client, VideoConfig.Settings settings, Screen returnTo) {
            this.client = client;
            this.settings = settings;
            this.returnTo = returnTo;
            this.level = client.level;
            this.manager = ClientAssetsManager.INSTANCE.getGltfModelManager();
            this.generation = manager == null ? -1 : manager.getGeneration();
            this.reload = new VideoQualityReload(this::begin, () -> ownsScreen()
                    && (manager == null || manager.getGeneration() == generation));
            this.overlay = new LoadingOverlay(client, reload, this::finish, false) {
                @Override
                public void tick() {
                    LoadingOperation.this.reload.tick();
                    super.tick();
                }
            };
        }

        private boolean ownsScreen() {
            return loading == this && client.level == level
                    && client.gui.screen() == waiting && client.gui.overlay() == overlay;
        }

        private List<VideoQualityReload.Target> begin() {
            if (manager == null) throw new IllegalStateException("glTF resource manager is not ready");
            var staged = ClientIndexManager.stageQualityReload();
            save(settings);
            publish(settings.quality(), staged, manager, true);
            generation = manager.getGeneration();
            List<VideoQualityReload.Target> targets = new ArrayList<>();
            staged.warmUp().forEach((id, requests) -> {
                GunDisplayInstance display = ClientIndexManager.GUN_DISPLAY.get(id);
                var preparation = display.warmUpForReload(requests);
                targets.add(new VideoQualityReload.Target(preparation, () -> {
                    if ((requests & GunDisplayInstance.LOAD_MODEL) == 0) return List.of();
                    var model = display.getGunModel();
                    if (model == null) throw new IllegalStateException("Prepared gun model is no longer available: " + id);
                    return model.getBodyRenderer() instanceof GltfGunBodyRenderer mesh
                            ? mesh.prepareResourceUploads() : List.of();
                }));
            });
            staged.requestedAttachments().forEach((id, attachment) -> targets.add(attachmentTarget(id, attachment)));
            GunMod.LOGGER.info("NeoTaCZ video quality loading {} requested displays", targets.size());
            return targets;
        }

        private void finish(Optional<Throwable> failure) {
            if (!ownsScreen()) return;
            // Claim completion once; native LoadingOverlay may invoke an error callback if navigation throws.
            loading = null;
            if (failure.isPresent()) showFailure(client, returnTo, failure.get());
            else {
                client.gui.setScreen(returnTo);
                GunMod.LOGGER.info("NeoTaCZ video quality loading complete, resource generation {}", generation);
            }
        }
    }
}
