package com.tacz.guns.client.resource;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonRenderHandler;
import com.google.common.collect.Maps;
import com.tacz.guns.GunMod;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.resource.index.ClientAmmoIndex;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.pojo.AmmoIndexPOJO;
import com.tacz.guns.resource.pojo.AttachmentIndexPOJO;
import com.tacz.guns.resource.pojo.BlockIndexPOJO;
import com.tacz.guns.resource.pojo.GunIndexPOJO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Function;

public class ClientIndexManager {
    private static final int HOTBAR_SLOT_COUNT = 9;

    public static volatile Map<Identifier, GunDisplayInstance> GUN_DISPLAY = Maps.newHashMap();
    public static final Map<Identifier, ClientGunIndex> GUN_INDEX = Maps.newHashMap();
    public static final Map<Identifier, ClientAmmoIndex> AMMO_INDEX = Maps.newHashMap();
    public static volatile Map<Identifier, ClientAttachmentIndex> ATTACHMENT_INDEX = Maps.newHashMap();
    public static final Map<Identifier, ClientBlockIndex> BLOCK_INDEX = Maps.newHashMap();

    public static void clear() {
        GUN_DISPLAY.values().forEach(GunDisplayInstance::invalidate);
        GUN_DISPLAY = Maps.newHashMap();
        GUN_INDEX.clear();
        AMMO_INDEX.clear();
        ATTACHMENT_INDEX.values().forEach(ClientAttachmentIndex::invalidate);
        ATTACHMENT_INDEX = Maps.newHashMap();
        BLOCK_INDEX.clear();
    }

    public static void reload() {
        clear();

        loadGunDisplay();
        loadGunIndex();
        loadAmmoIndex();
        loadAttachmentIndex();
        loadBlockIndex();
        warmUpInventoryModels();

        resetHeldGun();
    }

    private static void resetHeldGun() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && IGun.mainHandHoldGun(player)) {
            AttachmentPropertyManager.postChangeEvent(player, player.getMainHandItem());

            // 自动切一次枪，以便刷新状态机
            IClientPlayerGunOperator.fromLocalPlayer(player).draw(ItemStack.EMPTY);
            FirstPersonRenderHandler.reset();
        }
    }

    static QualityReload stageQualityReload() {
        return stageQualityReload(GunDisplayInstance::deferredCopy);
    }

    static QualityReload stageQualityReload(Function<GunDisplayInstance, GunDisplayInstance> copy) {
        Objects.requireNonNull(copy, "copy");
        Map<Identifier, GunDisplayInstance> previous = GUN_DISPLAY;
        Map<Identifier, GunDisplayInstance> replacements = new HashMap<>();
        Map<Identifier, Integer> requests = new HashMap<>();
        previous.forEach((id, instance) -> {
            int requested = instance.requestedLoads();
            replacements.put(id, Objects.requireNonNull(copy.apply(instance), "deferred display"));
            if (requested != 0) requests.put(id, requested);
        });
        Map<Identifier, ClientAttachmentIndex> oldAttachments = ATTACHMENT_INDEX;
        Map<Identifier, ClientAttachmentIndex> newAttachments = new HashMap<>();
        Set<Identifier> attachmentRequests = new java.util.HashSet<>();
        oldAttachments.forEach((id, instance) -> {
            newAttachments.put(id, instance.usesMeshRenderModel() ? instance.deferredCopy() : instance);
            if (instance.requestedMeshLoad()) attachmentRequests.add(id);
        });
        boolean hasMesh = oldAttachments.values().stream().anyMatch(ClientAttachmentIndex::usesMeshRenderModel);
        return new QualityReload(previous, replacements, requests,
                oldAttachments, hasMesh ? newAttachments : oldAttachments, Set.copyOf(attachmentRequests));
    }

    static final class QualityReload {
        private Map<Identifier, GunDisplayInstance> previous;
        private final Map<Identifier, GunDisplayInstance> replacements;
        private final Map<Identifier, Integer> requests;
        private Map<Identifier, ClientAttachmentIndex> previousAttachments;
        private final Map<Identifier, ClientAttachmentIndex> replacementAttachments;
        private final Set<Identifier> attachmentRequests;
        private boolean published;

        private QualityReload(Map<Identifier, GunDisplayInstance> previous,
                              Map<Identifier, GunDisplayInstance> replacements,
                              Map<Identifier, Integer> requests,
                              Map<Identifier, ClientAttachmentIndex> previousAttachments,
                              Map<Identifier, ClientAttachmentIndex> replacementAttachments,
                              Set<Identifier> attachmentRequests) {
            this.previous = previous;
            this.replacements = replacements;
            this.requests = requests;
            this.previousAttachments = previousAttachments;
            this.replacementAttachments = replacementAttachments;
            this.attachmentRequests = attachmentRequests;
        }

        // The quality coordinator owns the client-thread precondition and GPU cleanup.
        void publish() {
            if (published || GUN_DISPLAY != previous || ATTACHMENT_INDEX != previousAttachments) {
                throw new IllegalStateException("The staged display tables are no longer current");
            }
            Map<Identifier, GunDisplayInstance> retired = previous;
            Map<Identifier, ClientAttachmentIndex> retiredAttachments = previousAttachments;
            GUN_DISPLAY = replacements;
            ATTACHMENT_INDEX = replacementAttachments;
            published = true;
            previous = null; // The reload operation must not retain old encoded models during new 8K loading.
            previousAttachments = null;
            retired.values().forEach(GunDisplayInstance::invalidate);
            retiredAttachments.forEach((id, instance) -> {
                if (replacementAttachments.get(id) != instance) instance.invalidate();
            });
        }

        Map<Identifier, Integer> warmUp() {
            return warmUp(ClientIndexManager::warmUpInventoryModels, ClientIndexManager::resetHeldGun);
        }

        Map<Identifier, Integer> warmUp(Runnable inventoryWarmup, Runnable heldGunReset) {
            if (!published || GUN_DISPLAY != replacements || ATTACHMENT_INDEX != replacementAttachments) {
                throw new IllegalStateException("Quality warmup requires its published gun display table");
            }
            requests.forEach((id, requested) -> replacements.get(id).warmUpForReload(requested));
            attachmentRequests.forEach(id -> replacementAttachments.get(id).warmUpMeshForReload());
            inventoryWarmup.run();
            heldGunReset.run();
            Map<Identifier, Integer> targets = new HashMap<>();
            replacements.forEach((id, instance) -> {
                int requested = instance.requestedLoads();
                if (requested != 0) targets.put(id, requested);
            });
            return Map.copyOf(targets);
        }

        Map<Identifier, ClientAttachmentIndex> requestedAttachments() {
            if (!published || ATTACHMENT_INDEX != replacementAttachments) {
                throw new IllegalStateException("Quality warmup requires its published attachment table");
            }
            Map<Identifier, ClientAttachmentIndex> targets = new HashMap<>();
            replacementAttachments.forEach((id, instance) -> {
                if (instance.requestedMeshLoad()) targets.put(id, instance);
            });
            return Map.copyOf(targets);
        }
    }

    public static void loadGunIndex() {
        TimelessAPI.getAllCommonGunIndex().forEach(index -> {
            Identifier id = index.getKey();
            GunIndexPOJO pojo = index.getValue().getPojo();
            try {
                GUN_INDEX.put(id, ClientGunIndex.getInstance(pojo));
            } catch (IllegalArgumentException exception) {
                GunMod.LOGGER.warn("{} index file read fail!", id, exception);
            }
        });
    }

    public static void loadGunDisplay() {
        ClientAssetsManager.INSTANCE.getGunDisplays().forEach(entry -> {
            Identifier displayId = entry.getKey();
            try {
                GUN_DISPLAY.put(displayId, GunDisplayInstance.create(displayId, entry.getValue()));
            } catch (IllegalArgumentException exception) {
                GunMod.LOGGER.warn("{} display file read fail!", displayId, exception);
            }
        });
    }

    public static GunDisplayInstance getOrCreateGunDisplay(Identifier displayId) {
        GunDisplayInstance instance = GUN_DISPLAY.get(displayId);
        if (instance != null) {
            return instance;
        }
        GunMod.LOGGER.warn("{} display instance is missing from cache", displayId);
        return null;
    }

    public static void loadAmmoIndex() {
        TimelessAPI.getAllCommonAmmoIndex().forEach(index -> {
            Identifier id = index.getKey();
            AmmoIndexPOJO pojo = index.getValue().getPojo();
            try {
                AMMO_INDEX.put(id, ClientAmmoIndex.getInstance(pojo));
            } catch (IllegalArgumentException exception) {
                GunMod.LOGGER.warn("{} index file read fail!", id, exception);
            }
        });
    }

    public static void loadAttachmentIndex() {
        TimelessAPI.getAllCommonAttachmentIndex().forEach(index -> {
            Identifier id = index.getKey();
            AttachmentIndexPOJO pojo = index.getValue().getPojo();
            try {
                ATTACHMENT_INDEX.put(id, ClientAttachmentIndex.getInstance(id, pojo));
            } catch (IllegalArgumentException exception) {
                GunMod.LOGGER.warn("{} index file read fail!", id, exception);
            }
        });
    }

    public static void loadBlockIndex() {
        TimelessAPI.getAllCommonBlockIndex().forEach(index -> {
            Identifier id = index.getKey();
            BlockIndexPOJO pojo = index.getValue().getPojo();
            try {
                BLOCK_INDEX.put(id, ClientBlockIndex.getInstance(pojo));
            } catch (IllegalArgumentException exception) {
                GunMod.LOGGER.warn("{} index file read fail!", id, exception);
            }
        });
    }

    public static Set<Map.Entry<Identifier, ClientGunIndex>> getAllGuns() {
        return GUN_INDEX.entrySet();
    }

    public static Set<Map.Entry<Identifier, ClientAmmoIndex>> getAllAmmo() {
        return AMMO_INDEX.entrySet();
    }

    public static Set<Map.Entry<Identifier, ClientAttachmentIndex>> getAllAttachments() {
        return ATTACHMENT_INDEX.entrySet();
    }

    public static Set<Map.Entry<Identifier, ClientBlockIndex>> getAllBlocks() {
        return BLOCK_INDEX.entrySet();
    }

    public static void warmUpInventoryModels() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        warmUpItemForUse(player.getMainHandItem());
        warmUpItemForUse(player.getOffhandItem());
        warmUpHotbarModels(player);
        warmUpBackpackModels(player);
    }

    public static void warmUpEquippedAndHotbarModels() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        warmUpItemForUse(player.getMainHandItem());
        warmUpItemForUse(player.getOffhandItem());
        warmUpHotbarModels(player);
    }

    public static void warmUpBackpackModels() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        warmUpBackpackModels(player);
    }

    private static void warmUpHotbarModels(LocalPlayer player) {
        var items = player.getInventory().getNonEquipmentItems();
        int hotbarSize = Math.min(HOTBAR_SLOT_COUNT, items.size());
        for (int i = 0; i < hotbarSize; i++) {
            warmUpItemModel(items.get(i));
        }
    }

    private static void warmUpBackpackModels(LocalPlayer player) {
        var items = player.getInventory().getNonEquipmentItems();
        for (int i = Math.min(HOTBAR_SLOT_COUNT, items.size()); i < items.size(); i++) {
            warmUpItemModel(items.get(i));
        }
    }

    public static void warmUpItem(ItemStack stack) {
        warmUpItemForUse(stack);
    }

    public static void warmUpItemModel(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (stack.getItem() instanceof IGun gun) {
            TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
                display.warmUpLod();
                display.warmUpModel();
                display.warmUpRuntime();
            });
            warmUpGunAttachments(gun, stack);
            return;
        }
        IAttachment attachment = IAttachment.getIAttachmentOrNull(stack);
        if (attachment != null) {
            TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(stack)).ifPresent(ClientAttachmentIndex::warmUp);
            return;
        }
        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
        if (ammo != null) {
            TimelessAPI.getClientAmmoIndex(ammo.getAmmoId(stack)).ifPresent(ClientAmmoIndex::warmUp);
        }
    }

    public static void warmUpItemForUse(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (stack.getItem() instanceof IGun gun) {
            TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
                display.warmUpLod();
                display.warmUpModel();
                display.warmUpRuntime();
            });
            warmUpGunAttachments(gun, stack);
            return;
        }
        warmUpItemModel(stack);
    }

    private static void warmUpGunAttachments(IGun gun, ItemStack stack) {
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue;
            ItemStack attachment = gun.getAttachment(stack, type);
            if (attachment.isEmpty()) attachment = gun.getBuiltinAttachment(stack, type);
            if (!attachment.isEmpty()) warmUpItemModel(attachment);
        }
    }
}
