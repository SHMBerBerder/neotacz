package com.tacz.guns.resource.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.tacz.guns.crafting.GunSmithTableSerializer;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.resource.network.CommonNetworkCache;
import com.tacz.guns.resource.network.DataType;
import com.tacz.guns.resource.serialize.ItemStackJsonHelper;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import com.tacz.guns.util.ItemStackData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomWorkbenchRecipeMigrationTest {
    private static final Identifier RECIPE_ID = Identifier.parse("tacz:misc/blood_strike_1");
    private static final Identifier VARIANT_ID = Identifier.parse("tacz:blood_strike_1");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @AfterEach
    void clearNetworkRecipes() {
        CommonNetworkCache.INSTANCE.clear();
    }

    @Test
    void existingPaintingRecipeLoadsForServerAndClientWithItsOriginalMaterials() throws Exception {
        var manager = loadPainting();
        var registries = paintingRegistries("painting.tacz.blood_strike_1.title");
        assertNull(manager.getInitializedRecipe(RECIPE_ID));
        manager.initializeRecipes(registries);
        var recipe = manager.getInitializedRecipe(RECIPE_ID);
        assertNotNull(recipe);
        assertEquals(Identifier.parse("tacz:misc"), recipe.getTab());
        assertEquals(8, recipe.getInputs().get(0).getCount());
        assertTrue(recipe.getInputs().get(0).getIngredient().test(new ItemStack(Items.STICK)));
        assertEquals(1, recipe.getInputs().get(1).getCount());
        assertTrue(recipe.getInputs().get(1).getIngredient().test(new ItemStack(Items.WOOL.white())));
        assertPainting(recipe.getResultItem(registries));

        CommonNetworkCache.INSTANCE.fromNetwork(Map.of(DataType.RECIPES, manager.getNetworkCache()), registries);
        var clientRecipe = CommonNetworkCache.INSTANCE.getRecipe(RECIPE_ID);
        assertNotNull(clientRecipe);
        assertPainting(clientRecipe.getOutput());
        assertTrue(ItemStack.matches(recipe.getOutput(), clientRecipe.getOutput()));
    }

    @Test
    void nativeItemAndRecipeCodecsPreserveAllPaintingComponents() throws Exception {
        var registries = paintingRegistries("painting.tacz.blood_strike_1.title");
        var manager = loadPainting();
        manager.initializeRecipes(registries);
        var recipe = manager.getInitializedRecipe(RECIPE_ID);
        assertNotNull(recipe);
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var encoded = ItemStack.CODEC.encodeStart(ops, recipe.getOutput()).getOrThrow();
        assertPainting(ItemStack.CODEC.parse(ops, encoded).getOrThrow());

        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.OTHER);
        try {
            GunSmithTableSerializer.STREAM_CODEC.encode(buffer, recipe);
            var decoded = GunSmithTableSerializer.STREAM_CODEC.decode(buffer);
            assertPainting(decoded.getOutput());
            assertEquals(recipe.getTab(), decoded.getTab());
            assertEquals(8, decoded.getInputs().getFirst().getCount());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void nativePaintingTooltipHasTitleAuthorAndDimensions() throws Exception {
        var manager = loadPainting();
        manager.initializeRecipes(paintingRegistries("painting.tacz.blood_strike_1.title"));
        ItemStack stack = manager.getInitializedRecipe(RECIPE_ID).getOutput();
        List<Component> tooltip = new ArrayList<>();
        stack.getItem().appendHoverText(stack, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT,
                tooltip::add, TooltipFlag.NORMAL);
        assertEquals(List.of(Component.translatable("painting.tacz.blood_strike_1.title"),
                Component.translatable("painting.tacz.blood_strike_1.author"),
                Component.translatable("painting.dimensions", 2, 2)), tooltip);
    }

    @Test
    void customTemplateDoesNotReadUnboundDefaultComponentsBeforeMaterialization() throws Exception {
        var parsed = ItemStackJsonHelper.getItemStackTemplate(paintingItem(), true);
        var isolated = new MappedRegistry<Item>(Registries.ITEM, Lifecycle.stable());
        var holder = isolated.register(ResourceKey.create(Registries.ITEM, Identifier.parse("tacz:test_painting")),
                Items.PAINTING, RegistrationInfo.BUILT_IN);
        assertFalse(holder.areComponentsBound());
        var template = new ItemStackTemplate(holder, parsed.count(), parsed.components());
        var result = new GunSmithTableResult(template, Identifier.parse("tacz:misc"));
        assertTrue(result.getResult().isEmpty());
        assertFalse(holder.areComponentsBound());
        holder.bindComponents(Items.PAINTING.builtInRegistryHolder().components());
        result.init();
        ItemStackJsonHelper.resolvePaintingVariant(result.getResult(), paintingRegistries("title"));
        assertPainting(result.getResult());
    }

    @Test
    void providerChangesRebindTheVariantAndMissingRegistriesDoNotKeepOldHolders() throws Exception {
        ItemStack stack = ItemStackJsonHelper.getItemStack(paintingItem(), true);
        ItemStackJsonHelper.resolvePaintingVariant(stack, paintingRegistries("first.title"));
        var first = stack.get(DataComponents.PAINTING_VARIANT);
        assertNotNull(first);
        ItemStackJsonHelper.resolvePaintingVariant(stack, paintingRegistries("second.title"));
        var second = stack.get(DataComponents.PAINTING_VARIANT);
        assertNotNull(second);
        assertNotSame(first, second);
        assertEquals(Component.translatable("second.title"), second.value().title().orElseThrow());
        ItemStackJsonHelper.resolvePaintingVariant(stack, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        assertNull(stack.get(DataComponents.PAINTING_VARIANT));
    }

    @Test
    void customDataAndReadNbtSwitchRemainIntactWithoutInventingOtherEntityTypes() throws Exception {
        JsonObject item = paintingItem();
        item.getAsJsonObject("nbt").addProperty("CustomExtension", "retained");
        ItemStack stack = ItemStackJsonHelper.getItemStack(item, true);
        assertEquals("retained", ItemStackData.copyCustomData(stack).getStringOr("CustomExtension", ""));
        assertNotNull(stack.get(DataComponents.ENTITY_DATA));
        ItemStack ignored = ItemStackJsonHelper.getItemStack(item, false);
        assertNull(ignored.get(DataComponents.CUSTOM_NAME));
        assertNull(ignored.get(DataComponents.ENTITY_DATA));
        assertTrue(ItemStackData.copyCustomData(ignored).isEmpty());
        item.addProperty("item", "minecraft:stick");
        assertNull(ItemStackJsonHelper.getItemStack(item, true).get(DataComponents.ENTITY_DATA));
        item.addProperty("item", "minecraft:air");
        assertTrue(ItemStackJsonHelper.getItemStack(item, false, false).isEmpty());
    }

    @Test
    void malformedNamesAndEntityTagsAreRejectedAndReloadClearsOldRecipes() throws Exception {
        JsonObject item = paintingItem();
        item.getAsJsonObject("nbt").getAsJsonObject("display").addProperty("Name", "{");
        assertThrows(JsonSyntaxException.class, () -> ItemStackJsonHelper.getItemStack(item, true));
        JsonObject badEntity = paintingItem();
        badEntity.getAsJsonObject("nbt").addProperty("EntityTag", "wrong type");
        assertThrows(JsonSyntaxException.class, () -> ItemStackJsonHelper.getItemStack(badEntity, true));
        JsonObject badVariant = paintingItem();
        badVariant.getAsJsonObject("nbt").getAsJsonObject("EntityTag").addProperty("variant", 42);
        assertThrows(JsonSyntaxException.class, () -> ItemStackJsonHelper.getItemStack(badVariant, true));

        var manager = loadPainting();
        manager.initializeRecipes(paintingRegistries("title"));
        assertNotNull(manager.getInitializedRecipe(RECIPE_ID));
        manager.load(new GunSmithTableRecipeDataManager.PreparedData(Map.of(), Map.of()));
        assertNull(manager.getInitializedRecipe(RECIPE_ID));
        assertTrue(manager.getNetworkCache().isEmpty());
        CommonNetworkCache.INSTANCE.fromNetwork(Map.of(), paintingRegistries("title"));
        assertNull(CommonNetworkCache.INSTANCE.getRecipe(RECIPE_ID));
    }

    @Test
    void malformedRecipesAreIsolatedAndEmptyResultsCannotBeCrafted() throws Exception {
        JsonObject malformed = json("recipes/misc/blood_strike_1.json");
        malformed.add("materials", JsonParser.parseString("[]"));
        malformed.getAsJsonObject("result").getAsJsonObject("item").getAsJsonObject("nbt")
                .getAsJsonObject("display").addProperty("Name", "{");
        JsonObject empty = json("recipes/misc/blood_strike_1.json");
        empty.add("materials", JsonParser.parseString("[]"));
        empty.getAsJsonObject("result").getAsJsonObject("item").addProperty("count", 0);
        Identifier emptyId = Identifier.parse("tacz:test/empty");
        var manager = new TestManager();
        manager.load(new GunSmithTableRecipeDataManager.PreparedData(Map.of(RECIPE_ID, malformed, emptyId, empty), Map.of()));
        manager.initializeRecipes(paintingRegistries("title"));
        assertFalse(manager.getNetworkCache().containsKey(RECIPE_ID));
        assertNull(manager.getInitializedRecipe(RECIPE_ID));
        assertNull(manager.getInitializedRecipe(emptyId));
        assertNull(manager.getInitializedRecipe(Identifier.parse("tacz:missing")));
    }

    private static void assertPainting(ItemStack stack) {
        assertTrue(stack.is(Items.PAINTING));
        assertEquals(1, stack.getCount());
        assertEquals(Component.translatable("item.tacz.painting.blood_strike_1"), stack.get(DataComponents.CUSTOM_NAME));
        var entity = stack.get(DataComponents.ENTITY_DATA);
        assertNotNull(entity);
        assertSame(EntityTypes.PAINTING, entity.type());
        assertEquals(VARIANT_ID.toString(), entity.copyTagWithoutId().getStringOr("variant", ""));
        var variant = stack.get(DataComponents.PAINTING_VARIANT);
        assertNotNull(variant);
        assertEquals(VARIANT_ID, variant.unwrapKey().orElseThrow().identifier());
    }

    private static TestManager loadPainting() throws Exception {
        var manager = new TestManager();
        manager.load(new GunSmithTableRecipeDataManager.PreparedData(Map.of(RECIPE_ID,
                json("recipes/misc/blood_strike_1.json")), Map.of(
                Identifier.parse("c:rods/wooden"), List.of(JsonParser.parseString("{\"values\":[\"minecraft:stick\"]}")),
                Identifier.parse("minecraft:wool"), List.of(JsonParser.parseString("{\"values\":[\"minecraft:white_wool\"]}")))));
        return manager;
    }

    private static JsonObject paintingItem() throws Exception {
        return json("recipes/misc/blood_strike_1.json").getAsJsonObject("result").getAsJsonObject("item");
    }

    private static JsonObject json(String path) throws Exception {
        try (var stream = CustomWorkbenchRecipeMigrationTest.class.getResourceAsStream("/data/tacz/" + path)) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static RegistryAccess.Frozen paintingRegistries(String title) throws Exception {
        var variants = new MappedRegistry<PaintingVariant>(Registries.PAINTING_VARIANT, Lifecycle.stable());
        var json = json("painting_variant/blood_strike_1.json");
        json.getAsJsonObject("title").addProperty("translate", title);
        var variant = PaintingVariant.DIRECT_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        variants.register(ResourceKey.create(Registries.PAINTING_VARIANT, VARIANT_ID), variant, RegistrationInfo.BUILT_IN);
        List<Registry<?>> registries = new ArrayList<>(BuiltInRegistries.REGISTRY.stream().toList());
        registries.add(variants);
        return new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }

    private static final class TestManager extends GunSmithTableRecipeDataManager {
        void load(PreparedData data) {
            apply(data, null, null);
        }
    }
}
