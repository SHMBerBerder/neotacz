package com.tacz.guns.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootTableInjectorModifierTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void acceptsAbsentAndEmptyLegacyConditions() {
        assertTrue(parse("{}").result().isPresent());
        assertTrue(parse("{\"conditions\":[]}").result().isPresent());
    }

    @Test
    void rejectsNonemptyLegacyConditionsInsteadOfMakingLootUnconditional() {
        assertTrue(parse("{\"conditions\":[{\"condition\":\"minecraft:survives_explosion\"}]}").error().isPresent());
        assertTrue(parse("{\"conditions\":{}}").error().isPresent());
    }

    @Test
    void preservesNativeConditionAndPriority() {
        String source = "{\"condition\":{\"type\":\"minecraft:any_of\",\"terms\":[]},\"priority\":17}";
        LootTableInjectorModifier modifier = parse(source).getOrThrow();
        assertEquals(17, modifier.priority());
        JsonElement encoded = LootTableInjectorModifier.CODEC.codec().encodeStart(JsonOps.INSTANCE, modifier).getOrThrow();
        assertEquals(JsonParser.parseString(source), encoded);
        assertFalse(encoded.getAsJsonObject().has("conditions"));
    }

    @Test
    void nativeConditionDoesNotAllowAnInvalidLegacyListToBeIgnored() {
        String nativeCondition = "\"condition\":{\"type\":\"minecraft:any_of\",\"terms\":[]}";
        assertTrue(parse("{" + nativeCondition + ",\"conditions\":[]}").result().isPresent());
        assertTrue(parse("{" + nativeCondition + ",\"conditions\":[{}]}").error().isPresent());
    }

    private static DataResult<LootTableInjectorModifier> parse(String json) {
        return LootTableInjectorModifier.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }
}
