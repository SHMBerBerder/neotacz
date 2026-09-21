package com.tacz.guns.resource.serialize;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.CustomData;
import com.mojang.serialization.JsonOps;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

public final class ItemStackJsonHelper {
    private static final Gson GSON = new Gson();

    private ItemStackJsonHelper() {
    }

    public static CompoundTag getNbt(JsonElement element) {
        try {
            if (element.isJsonObject()) {
                return TagParser.parseCompoundFully(GSON.toJson(element));
            }
            return TagParser.parseCompoundFully(GsonHelper.convertToString(element, "nbt"));
        } catch (CommandSyntaxException exception) {
            throw new JsonSyntaxException("Invalid item nbt: " + element, exception);
        }
    }

    public static ItemStack getItemStack(JsonObject object, boolean readNbt) {
        return getItemStack(object, readNbt, true);
    }

    public static ItemStack getItemStack(JsonObject object, boolean readNbt, boolean disallowAir) {
        Item item = getItem(object, disallowAir);
        return new ItemStack(item.builtInRegistryHolder(), GsonHelper.getAsInt(object, "count", 1),
                getComponents(object, item, readNbt));
    }

    public static ItemStackTemplate getItemStackTemplate(JsonObject object, boolean readNbt) {
        Item item = getItem(object, true);
        int count = GsonHelper.getAsInt(object, "count", 1);
        if (count <= 0) {
            throw new JsonSyntaxException("Custom recipe item count must be positive");
        }
        return new ItemStackTemplate(item, count, getComponents(object, item, readNbt));
    }

    private static Item getItem(JsonObject object, boolean disallowAir) {
        Identifier itemId = Identifier.parse(GsonHelper.getAsString(object, "item"));
        Item item = BuiltInRegistries.ITEM.getOptional(itemId)
                .orElseThrow(() -> new JsonSyntaxException("Unknown item '" + itemId + "'"));
        if (disallowAir && item == Items.AIR) {
            throw new JsonParseException("Item must not be minecraft:air");
        }
        return item;
    }

    private static DataComponentPatch getComponents(JsonObject object, Item item, boolean readNbt) {
        var components = DataComponentPatch.builder();
        if (readNbt && object.has("nbt")) {
            CompoundTag nbt = getNbt(object.get("nbt"));
            components.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
            if (nbt.contains("display")) {
                CompoundTag display = nbt.getCompound("display")
                        .orElseThrow(() -> new JsonSyntaxException("Expected item display compound"));
                if (display.contains("Name")) {
                    String name = display.getString("Name")
                            .orElseThrow(() -> new JsonSyntaxException("Expected item display.Name string"));
                    components.set(DataComponents.CUSTOM_NAME, ComponentSerialization.CODEC
                            .parse(JsonOps.INSTANCE, JsonParser.parseString(name)).getOrThrow(JsonSyntaxException::new));
                }
            }
            if (item == Items.PAINTING && nbt.contains("EntityTag")) {
                CompoundTag entityTag = nbt.getCompound("EntityTag")
                        .orElseThrow(() -> new JsonSyntaxException("Expected painting EntityTag compound"));
                paintingVariantId(entityTag);
                components.set(DataComponents.ENTITY_DATA, TypedEntityData.of(EntityTypes.PAINTING, entityTag.copy()));
            }
        }
        return components.build();
    }

    public static void resolvePaintingVariant(ItemStack stack, HolderLookup.Provider registries) {
        var entityData = stack.get(DataComponents.ENTITY_DATA);
        if (!stack.is(Items.PAINTING) || entityData == null || entityData.type() != EntityTypes.PAINTING) {
            return;
        }
        CompoundTag entityTag = entityData.copyTagWithoutId();
        Identifier variantId = paintingVariantId(entityTag);
        if (variantId == null) {
            return;
        }
        // Never retain a holder from a previous datapack registry after a reload.
        stack.remove(DataComponents.PAINTING_VARIANT);
        registries.lookup(Registries.PAINTING_VARIANT)
                .flatMap(registry -> registry.get(ResourceKey.create(Registries.PAINTING_VARIANT, variantId)))
                .ifPresent(variant -> stack.set(DataComponents.PAINTING_VARIANT, variant));
    }

    private static Identifier paintingVariantId(CompoundTag entityTag) {
        if (!entityTag.contains("variant")) {
            return null;
        }
        String variantName = entityTag.getString("variant")
                .orElseThrow(() -> new JsonSyntaxException("Expected painting EntityTag.variant string"));
        Identifier variantId = Identifier.tryParse(variantName);
        if (variantId == null) {
            throw new JsonSyntaxException("Invalid painting variant: " + variantName);
        }
        return variantId;
    }
}
