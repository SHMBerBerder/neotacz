package com.tacz.guns.resource.pojo.data.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.predicates.IntValueCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class LegacyLootConsumerMigrationTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void everyOldFunctionNumberConsumerReachesTheNativeCodecWithItsLegacyProvider() {
        // Complete NumberProviders/IntRange consumer inventory from the 26.2 loot-functions package.
        for (String source : List.of(
                "{\"function\":\"minecraft:set_count\",\"count\":1.5}",
                "{\"function\":\"minecraft:set_damage\",\"damage\":0.75}",
                "{\"function\":\"minecraft:enchant_with_levels\",\"levels\":2.5}",
                "{\"function\":\"minecraft:enchanted_count_increase\",\"enchantment\":\"minecraft:looting\",\"count\":1.5}",
                "{\"function\":\"minecraft:set_ominous_bottle_amplifier\",\"amplifier\":2.5}",
                "{\"function\":\"minecraft:set_random_dyes\",\"number_of_dyes\":2.5}",
                "{\"function\":\"minecraft:set_attributes\",\"modifiers\":[{\"id\":\"tacz:test\",\"attribute\":\"minecraft:luck\",\"operation\":\"add_value\",\"amount\":1.75,\"slot\":\"mainhand\"}]}",
                "{\"function\":\"minecraft:set_stew_effect\",\"effects\":[{\"type\":\"minecraft:poison\",\"duration\":1.5}]}",
                "{\"function\":\"minecraft:set_enchantments\",\"enchantments\":{\"minecraft:unbreaking\":1.5}}",
                "{\"function\":\"minecraft:set_custom_model_data\",\"floats\":{\"mode\":\"append\",\"values\":[1.5]},\"colors\":{\"mode\":\"append\",\"values\":[16777217]}}",
                "{\"function\":\"minecraft:limit_count\",\"limit\":{\"min\":1.5,\"max\":4.5}}")) {
            var normalized = normalizedFunction(source);
            var function = LootItemFunctions.DIRECT_CODEC.parse(ops(), normalized).getOrThrow();
            var encoded = LootItemFunctions.DIRECT_CODEC.encodeStart(ops(), function).getOrThrow();
            assertTrue(encoded.toString().contains("tacz:legacy_number"), source);
            assertDoesNotThrow(() -> LootItemFunctions.DIRECT_CODEC.parse(ops(), encoded).getOrThrow(), source);
        }
    }

    @Test
    void realComponentOutputsKeepCountsLevelsDurationsAttributesAndModelData() throws Exception {
        var context = LegacyLootNumberMigrationTest.randomContext(4);
        var stack = new ItemStack(Items.STICK);
        function("{\"function\":\"minecraft:set_count\",\"count\":1.5}").apply(stack, context);
        assertEquals(2, stack.getCount());
        function("{\"function\":\"minecraft:set_attributes\",\"modifiers\":[{\"id\":\"tacz:test\",\"attribute\":\"minecraft:luck\",\"operation\":\"add_value\",\"amount\":1.75,\"slot\":\"mainhand\"}]}")
                .apply(stack, context);
        assertEquals(1.75, stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().getFirst().modifier().amount());

        var sword = new ItemStack(Items.DIAMOND_SWORD);
        function("{\"function\":\"minecraft:set_damage\",\"damage\":0.75}").apply(sword, context);
        assertEquals(Mth.floor(sword.getMaxDamage() * 0.25F), sword.getDamageValue());
        function("{\"function\":\"minecraft:set_enchantments\",\"enchantments\":{\"minecraft:unbreaking\":1.5}}").apply(sword, context);
        var unbreaking = MinecraftTestEnvironment.registries().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse("minecraft:unbreaking")));
        assertEquals(2, sword.get(DataComponents.ENCHANTMENTS).getLevel(unbreaking));
        var bottle = new ItemStack(Items.OMINOUS_BOTTLE);
        function("{\"function\":\"minecraft:set_ominous_bottle_amplifier\",\"amplifier\":4.5}").apply(bottle, context);
        assertEquals(4, bottle.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER).value());
        var stew = new ItemStack(Items.SUSPICIOUS_STEW);
        function("{\"function\":\"minecraft:set_stew_effect\",\"effects\":[{\"type\":\"minecraft:poison\",\"duration\":1.5}]}").apply(stew, context);
        assertEquals(40, stew.get(DataComponents.SUSPICIOUS_STEW_EFFECTS).effects().getFirst().duration());

        function("""
                {"function":"minecraft:set_custom_model_data",
                  "floats":{"mode":"append","values":[-1.5]},
                  "flags":{"mode":"append","values":[true]},
                  "strings":{"mode":"append","values":["unchanged"]},
                  "colors":{"mode":"append","values":[[1,0.5,0.25],16777217]}}
                """).apply(stack, context);
        var modelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        assertEquals(List.of(-1.5F), modelData.floats());
        assertEquals(List.of(true), modelData.flags());
        assertEquals(List.of("unchanged"), modelData.strings());
        int rgb = ExtraCodecs.RGB_COLOR_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[1,0.5,0.25]")).getOrThrow();
        assertEquals(List.of(Math.round((float) rgb), Math.round((float) 16777217)), modelData.colors());
    }

    @Test
    void oldRangePointsKeepFloatPrecisionAndMinFailureDoesNotEvaluateMax() throws Exception {
        var condition = assertInstanceOf(IntValueCheck.class, condition("{\"condition\":\"minecraft:value_check\",\"value\":0,\"range\":16777217}"));
        assertTrue(condition.range().test(null, 16777216));
        assertFalse(condition.range().test(null, 16777217));
        var stack = new ItemStack(Items.STICK);
        stack.setCount(16777220);
        function("{\"function\":\"minecraft:limit_count\",\"limit\":16777217}").apply(stack, null);
        assertEquals(16777216, stack.getCount());

        String range = "\"range\":{\"min\":10,\"max\":{\"type\":\"minecraft:enchantment_level\",\"amount\":1}}";
        var context = LegacyLootNumberMigrationTest.randomContext(19);
        assertFalse(condition("{\"condition\":\"minecraft:value_check\",\"value\":5," + range + "}").test(context));
        assertThrows(NoSuchElementException.class, () -> condition("{\"condition\":\"minecraft:value_check\",\"value\":15," + range + "}").test(context));
    }

    @Test
    void oldEmptyValueCheckRangeStillEvaluatesOnceButNativeEmptyRangeStaysLazy() throws Exception {
        var condition = condition("{\"condition\":\"minecraft:value_check\",\"value\":{\"min\":1,\"max\":7},\"range\":{}}");
        var context = LegacyLootNumberMigrationTest.randomContext(81);
        var random = RandomSource.create(81);
        Mth.nextInt(random, 1, 7);
        assertTrue(condition.test(context));
        assertEquals(random.nextLong(), context.getRandom().nextLong());
        var missing = condition("{\"condition\":\"minecraft:value_check\",\"value\":{\"type\":\"minecraft:enchantment_level\",\"amount\":1},\"range\":{}}");
        assertThrows(NoSuchElementException.class, () -> missing.test(context));
        var nativeCondition = JsonParser.parseString("""
                {"type":"minecraft:int_value_check","value":{"type":"tacz:legacy_number",
                  "value":{"type":"minecraft:enchantment_level","amount":1}},"test":{}}
                """);
        assertTrue(LootItemCondition.DIRECT_CODEC.parse(ops(), nativeCondition).getOrThrow().test(context));
    }

    @Test
    void everyOldPredicateNumberConsumerAndPoolDomainSurvivesCodecRoundTrip() {
        String clock = MinecraftTestEnvironment.registries().lookupOrThrow(Registries.WORLD_CLOCK).listElementIds().findFirst().orElseThrow().identifier().toString();
        for (String source : List.of(
                "{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}",
                "{\"condition\":\"minecraft:entity_scores\",\"entity\":\"this\",\"scores\":{\"ammo\":{\"min\":1.5,\"max\":5.5}}}",
                "{\"condition\":\"minecraft:time_check\",\"clock\":\"" + clock + "\",\"value\":{\"min\":1.5}}",
                "{\"condition\":\"minecraft:value_check\",\"value\":1.5,\"range\":{\"max\":3.5}}")) {
            var parsed = condition(source);
            assertTrue(LootItemCondition.DIRECT_CODEC.encodeStart(ops(), parsed).getOrThrow().toString().contains("tacz:legacy_number"), source);
        }
        var pool = LootTableInjection.normalizeLegacyLootTable(JsonParser.parseString("""
                {"pools":[{"rolls":1.5,"bonus_rolls":0.25,"conditions":[],"entries":[]}]}
                """).getAsJsonObject()).getAsJsonArray("pools").get(0).getAsJsonObject();
        assertEquals(2, net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders.CODEC
                .parse(ops(), pool.get("rolls")).getOrThrow().value().getInt(null));
        assertEquals(0.25F, net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProviders.CODEC
                .parse(ops(), pool.get("bonus_rolls")).getOrThrow().value().getFloat(null));
    }

    private static LootItemConditionalFunction function(String source) {
        return assertInstanceOf(LootItemConditionalFunction.class,
                LootItemFunctions.DIRECT_CODEC.parse(ops(), normalizedFunction(source)).getOrThrow());
    }

    private static JsonElement normalizedFunction(String source) {
        var table = JsonParser.parseString("{\"pools\":[{\"rolls\":1,\"entries\":[],\"functions\":[" + source + "]}]}").getAsJsonObject();
        var normalized = LootTableInjection.normalizeLegacyLootTable(table);
        assertEquals(normalized, LootTableInjection.normalizeLegacyLootTable(normalized));
        return normalized.getAsJsonArray("pools").get(0).getAsJsonObject().get("modifier");
    }

    private static LootItemCondition condition(String source) {
        var table = JsonParser.parseString("{\"pools\":[{\"rolls\":1,\"entries\":[],\"conditions\":[" + source + "]}]}").getAsJsonObject();
        var normalized = LootTableInjection.normalizeLegacyLootTable(table);
        assertEquals(normalized, LootTableInjection.normalizeLegacyLootTable(normalized));
        return LootItemCondition.DIRECT_CODEC.parse(ops(), normalized.getAsJsonArray("pools").get(0).getAsJsonObject().get("condition")).getOrThrow();
    }

    private static RegistryOps<JsonElement> ops() {
        return MinecraftTestEnvironment.registries().createSerializationContext(JsonOps.INSTANCE);
    }
}
