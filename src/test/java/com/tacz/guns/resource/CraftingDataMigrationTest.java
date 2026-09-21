package com.tacz.guns.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CraftingDataMigrationTest {
    private record Expected(String id, String result, List<String> pattern, Map<String, String> key,
                            String customData) {
    }

    // These costs and output identities are the 1.20.1/26.2 contract, not new balancing choices.
    private static final List<Expected> SHAPED = List.of(
            new Expected("ammo_workbench", "workbench_a", List.of("CC", "IB"),
                    Map.of("C", "#c:ingots/copper", "I", "#c:ingots/iron", "B", "tacz:ammo_box"),
                    "{\"BlockId\":\"tacz:ammo_workbench\"}"),
            new Expected("attachment_workbench", "workbench_c", List.of("GCG", "G G", "IBI"),
                    Map.of("G", "#c:glass_blocks", "I", "#c:ingots/iron", "C", "minecraft:redstone_torch", "B", "minecraft:chest"),
                    "{\"BlockId\":\"tacz:attachment_workbench\"}"),
            new Expected("iron_ammo_box", "ammo_box", List.of("PIP", "P P", "PPP"),
                    Map.of("I", "#c:ingots/iron", "P", "#minecraft:planks"), "{\"Level\":0}"),
            new Expected("gold_ammo_box", "ammo_box", List.of("PIP", "P P", "PPP"),
                    Map.of("I", "#c:ingots/gold", "P", "#minecraft:planks"), "{\"Level\":1}"),
            new Expected("diamond_ammo_box", "ammo_box", List.of("PIP", "P P", "PPP"),
                    Map.of("I", "#c:gems/diamond", "P", "#minecraft:planks"), "{\"Level\":2}"),
            new Expected("gun_smith_table", "gun_smith_table", List.of("LLL", "IBI", "I I"),
                    Map.of("L", "#minecraft:logs", "I", "#c:ingots/iron", "B", "minecraft:iron_block"), null),
            new Expected("target", "target", List.of("III", "IRI", " P "),
                    Map.of("I", "#c:ingots/iron", "R", "minecraft:redstone", "P", "#minecraft:planks"), null),
            new Expected("statue", "statue", List.of(" _ ", "QCQ", " C "),
                    Map.of("_", "minecraft:quartz_slab", "Q", "minecraft:quartz_stairs", "C", "minecraft:chiseled_quartz_block"), null),
            new Expected("target_minecart", "target_minecart", List.of("A", "B"),
                    Map.of("A", "tacz:target", "B", "minecraft:minecart"), null)
    );

    @BeforeAll
    static void bootstrap() {
        DataResourceTestSupport.bootstrap();
    }

    @Test
    void shapedRecipesKeepEveryCostOutputAndCustomDataThroughTargetCodec() throws Exception {
        var ops = DataResourceTestSupport.recipeOps();
        for (Expected expected : SHAPED) {
            String path = "tacz/recipe/" + expected.id() + ".json";
            JsonObject original = DataResourceTestSupport.json(path);
            assertEquals("minecraft:crafting_shaped", original.get("type").getAsString(), path);
            assertEquals(expected.pattern(), original.getAsJsonArray("pattern").asList().stream().map(JsonElement::getAsString).toList(), path);
            assertEquals(expected.key(), original.getAsJsonObject("key").entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAsString())), path);
            JsonObject output = original.getAsJsonObject("result");
            assertEquals("tacz:" + expected.result(), output.get("id").getAsString(), path);
            assertEquals(1, output.has("count") ? output.get("count").getAsInt() : 1, path);
            assertFalse(output.has("nbt"), path);
            assertNull(getClass().getResource("/data/tacz/recipes/" + expected.id() + ".json"), path);

            // Vanilla fixture items isolate native codecs from NeoForge's mod-registration lifecycle.
            // The original TACZ identities are asserted above; runtime registration is a separate gate.
            JsonObject fixture = original.deepCopy();
            fixture.getAsJsonObject("result").addProperty("id", "minecraft:chest");
            fixture.getAsJsonObject("key").entrySet().forEach(entry -> {
                if (entry.getValue().getAsString().startsWith("tacz:")) {
                    entry.setValue(new com.google.gson.JsonPrimitive("minecraft:stone"));
                }
            });
            var recipe = assertInstanceOf(ShapedRecipe.class, Recipe.DIRECT_CODEC.parse(ops, fixture).getOrThrow());
            ItemStack result = recipe.assemble(CraftingInput.EMPTY);
            assertTrue(result.is(Items.CHEST));
            assertEquals(1, result.getCount());
            CustomData custom = result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
            if (expected.customData() == null) {
                assertTrue(custom.isEmpty(), path);
            } else {
                JsonElement expectedJson = com.google.gson.JsonParser.parseString(expected.customData());
                var encoded = CustomData.CODEC.encodeStart(JsonOps.INSTANCE, custom).getOrThrow();
                assertEquals(expectedJson, encoded, path);
            }
            JsonObject roundTrip = Recipe.DIRECT_CODEC.encodeStart(ops, recipe).getOrThrow().getAsJsonObject();
            assertEquals(fixture.get("pattern"), roundTrip.get("pattern"), path);
            assertEquals(fixture.get("key"), roundTrip.get("key"), path);
            assertEquals(fixture.getAsJsonObject("result").get("components"),
                    roundTrip.getAsJsonObject("result").get("components"), path);
        }
    }

    @Test
    void gunpowderKeepsRepeatedIngredientsAndYield() throws Exception {
        var json = DataResourceTestSupport.json("tacz/recipe/gunpowder.json");
        assertEquals(List.of("minecraft:flint", "minecraft:sugar", "minecraft:sugar",
                        "minecraft:charcoal", "minecraft:charcoal", "minecraft:charcoal"),
                json.getAsJsonArray("ingredients").asList().stream().map(JsonElement::getAsString).toList());
        var ops = DataResourceTestSupport.recipeOps();
        var recipe = assertInstanceOf(ShapelessRecipe.class, Recipe.DIRECT_CODEC.parse(ops, json).getOrThrow());
        var output = recipe.assemble(CraftingInput.EMPTY);
        assertTrue(output.is(Items.GUNPOWDER));
        assertEquals(3, output.getCount());
        assertEquals(json.get("ingredients"), Recipe.DIRECT_CODEC.encodeStart(ops, recipe)
                .getOrThrow().getAsJsonObject().get("ingredients"));
        assertNull(getClass().getResource("/data/tacz/recipes/gunpowder.json"));
    }

    @Test
    void registryResourcePathsRetainStableRecipeIds() {
        assertEquals("recipe", Registries.elementsDirPath(Registries.RECIPE));
        var converter = FileToIdConverter.json(Registries.elementsDirPath(Registries.RECIPE));
        for (Expected expected : SHAPED) {
            assertEquals(Identifier.parse("tacz:" + expected.id()), converter.fileToId(
                    Identifier.parse("tacz:recipe/" + expected.id() + ".json")));
        }
    }
}
