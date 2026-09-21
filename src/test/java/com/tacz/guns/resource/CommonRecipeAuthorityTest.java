package com.tacz.guns.resource;

import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.recipe.ClientGunSmithRecipeRepository;
import com.tacz.guns.crafting.GunSmithTableRecipe;
import com.tacz.guns.crafting.result.GunSmithTableResult;
import com.tacz.guns.resource.network.CommonNetworkCache;
import com.tacz.guns.resource.network.DataType;
import com.tacz.guns.resource.pojo.data.recipe.TableRecipe;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommonRecipeAuthorityTest {
    @BeforeAll
    static void bootstrap() {
        DataResourceTestSupport.bootstrap();
    }

    @AfterEach
    void clearNetworkCache() {
        CommonNetworkCache.INSTANCE.clear();
    }

    @Test
    void mergesDisjointSourcesAndUsesNativeResultForMatchingIds() {
        Identifier customOnly = id("custom_only");
        Identifier nativeOnly = id("native_only");
        Identifier shared = id("shared");
        var assets = assets(Map.of(nativeOnly, recipe(nativeOnly, new ItemStack(Items.ARROW)),
                shared, recipe(shared, new ItemStack(Items.IRON_INGOT))));
        var merged = assets.selectRecipeNetworkCache(Map.of(customOnly, "custom-only", shared, "old-custom-result"));
        assertEquals(3, merged.size());
        assertEquals("custom-only", merged.get(customOnly));
        assertTrue(result(merged.get(nativeOnly)).is(Items.ARROW));
        assertTrue(result(merged.get(shared)).is(Items.IRON_INGOT));
    }

    @Test
    void anyNativeIdSuppressesCustomEvenForNonTaczEmptyOrBrokenNativeRecipes() {
        Identifier vanillaId = id("vanilla");
        Identifier emptyId = id("empty");
        Identifier brokenId = id("broken");
        var vanilla = ShapelessRecipe.MAP_CODEC.codec().parse(DataResourceTestSupport.recipeOps(),
                JsonParser.parseString("{\"ingredients\":[\"minecraft:stick\"],\"result\":{\"id\":\"minecraft:arrow\"}}")).getOrThrow();
        var broken = new GunSmithTableRecipe(brokenId,
                new GunSmithTableResult(new ItemStack(Items.ARROW), id("misc")), List.of()) {
            @Override
            public void init() {
                throw new IllegalStateException("Broken native recipe fixture");
            }
        };
        var assets = assets(Map.of(vanillaId, vanilla, emptyId, recipe(emptyId, ItemStack.EMPTY), brokenId, broken));
        assertTrue(assets.selectRecipeNetworkCache(Map.of(vanillaId, "custom", emptyId, "custom", brokenId, "custom")).isEmpty());
    }

    @Test
    void completeEmptySnapshotDoesNotResurrectNativeRecipesAndClearRestoresFallback() throws Exception {
        Identifier nativeId = id("native");
        var assets = assets(Map.of(nativeId, recipe(nativeId, new ItemStack(Items.ARROW))));
        var instance = CommonAssetsManager.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, assets);
        try {
            CommonNetworkCache.INSTANCE.fromNetwork(Map.of(DataType.RECIPES, Map.of()));
            assertTrue(CommonNetworkCache.INSTANCE.hasRecipeSnapshot());
            assertTrue(TimelessAPI.getRecipe(nativeId).isEmpty());
            assertTrue(TimelessAPI.getAllRecipes().isEmpty());
            var sources = ClientGunSmithRecipeRepository.getSources();
            assertEquals(1, sources.size());
            assertEquals(ClientGunSmithRecipeRepository.SourceKind.NETWORK_CACHE, sources.getFirst().kind());
            assertTrue(sources.getFirst().recipes().isEmpty());
            assertTrue(ClientGunSmithRecipeRepository.getFirstAvailableRecipeMap().isEmpty());

            CommonNetworkCache.INSTANCE.clear();
            assertFalse(CommonNetworkCache.INSTANCE.hasRecipeSnapshot());
            assertTrue(TimelessAPI.getRecipe(nativeId).isPresent());
            assertEquals(1, TimelessAPI.getAllRecipes().size());
        } finally {
            instance.set(null, previous);
        }
    }

    @Test
    void missingRecipeSectionIsNotASnapshotAndLaterSnapshotReplacesPreviousRecipes() {
        CommonNetworkCache.INSTANCE.fromNetwork(Map.of());
        assertFalse(CommonNetworkCache.INSTANCE.hasRecipeSnapshot());
        Identifier first = id("first");
        Identifier second = id("second");
        CommonNetworkCache.INSTANCE.fromNetwork(Map.of(DataType.RECIPES,
                assets(Map.of(first, recipe(first, new ItemStack(Items.ARROW)))).selectRecipeNetworkCache(Map.of())));
        assertNotNull(CommonNetworkCache.INSTANCE.getRecipe(first));
        CommonNetworkCache.INSTANCE.fromNetwork(Map.of(DataType.RECIPES,
                assets(Map.of(second, recipe(second, new ItemStack(Items.STICK)))).selectRecipeNetworkCache(Map.of())));
        assertTrue(CommonNetworkCache.INSTANCE.hasRecipeSnapshot());
        assertNull(CommonNetworkCache.INSTANCE.getRecipe(first));
        assertNotNull(CommonNetworkCache.INSTANCE.getRecipe(second));
    }

    private static ItemStack result(String json) {
        var result = CommonAssetsManager.GSON.fromJson(json, TableRecipe.class).getResult();
        result.init();
        return result.getResult();
    }

    private static GunSmithTableRecipe recipe(Identifier id, ItemStack output) {
        return new GunSmithTableRecipe(id, new GunSmithTableResult(output, id("misc")), List.of());
    }

    private static CommonAssetsManager assets(Map<Identifier, ? extends Recipe<?>> recipes) {
        var registry = new MappedRegistry<Recipe<?>>(Registries.RECIPE, Lifecycle.stable());
        recipes.forEach((id, recipe) -> registry.register(ResourceKey.create(Registries.RECIPE, id), recipe, RegistrationInfo.BUILT_IN));
        List<Registry<?>> registries = new ArrayList<>(BuiltInRegistries.REGISTRY.stream().toList());
        registries.add(registry);
        var assets = new CommonAssetsManager();
        assets.recipeManager = new RecipeManager(new RegistryAccess.ImmutableRegistryAccess(registries).freeze());
        return assets;
    }

    private static Identifier id(String path) {
        return Identifier.parse("tacz:test/" + path);
    }
}
