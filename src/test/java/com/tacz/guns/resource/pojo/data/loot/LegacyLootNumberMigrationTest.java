package com.tacz.guns.resource.pojo.data.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProviders;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class LegacyLootNumberMigrationTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void legacyIntegerConstantsRetainFloatRoundingIncludingNegativeHalvesAndSaturation() {
        for (String value : List.of("0.5", "-0.5", "-1.5", "16777217", "3.4028235E38", "-3.4028235E38")) {
            int expected = Math.round(Float.parseFloat(value));
            for (String source : List.of(value, "{\"type\":\"minecraft:constant\",\"value\":" + value + "}")) {
                var normalized = normalizedNumber(source, true);
                var decoded = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, normalized).getOrThrow().value();
                assertEquals(expected, decoded.getInt(null), source);
                var encoded = ContextIntProviders.CODEC.encodeStart(JsonOps.INSTANCE,
                        net.minecraft.core.Holder.direct(decoded)).getOrThrow();
                assertEquals(expected, ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().value().getInt(null));
            }
        }
    }

    @Test
    void uniformAndBinomialKeepSeededOutputsAndRandomConsumptionInBothNumericDomains() throws Exception {
        String intUniform = "{\"min\":-1.5,\"max\":4.5}";
        var ints = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(intUniform, true)).getOrThrow().value();
        var intContext = randomContext(918273L);
        var expectedInts = RandomSource.create(918273L);
        for (int i = 0; i < 128; i++) {
            assertEquals(Mth.nextInt(expectedInts, Math.round(-1.5F), Math.round(4.5F)), ints.getInt(intContext));
        }
        assertEquals(expectedInts.nextLong(), intContext.getRandom().nextLong());

        String floatUniform = "{\"min\":0.1,\"max\":0.8}";
        var floats = ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(floatUniform, false)).getOrThrow().value();
        var floatContext = randomContext(918273L);
        var expectedFloats = RandomSource.create(918273L);
        for (int i = 0; i < 128; i++) {
            assertEquals(Mth.nextFloat(expectedFloats, 0.1F, 0.8F), floats.getFloat(floatContext));
        }
        assertEquals(expectedFloats.nextLong(), floatContext.getRandom().nextLong());

        String binomial = "{\"type\":\"minecraft:binomial\",\"n\":{\"min\":2.5,\"max\":5.5},\"p\":{" +
                "\"min\":0.1,\"max\":0.8}}";
        var intBinomial = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(binomial, true)).getOrThrow().value();
        var floatBinomial = ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(binomial, false)).getOrThrow().value();
        var intBinomialContext = randomContext(987654L);
        var floatBinomialContext = randomContext(987654L);
        var expectedRandom = RandomSource.create(987654L);
        for (int i = 0; i < 128; i++) {
            int n = Mth.nextInt(expectedRandom, Math.round(2.5F), Math.round(5.5F));
            float p = Mth.nextFloat(expectedRandom, 0.1F, 0.8F);
            int expected = 0;
            for (int roll = 0; roll < n; roll++) {
                if (expectedRandom.nextFloat() < p) {
                    expected++;
                }
            }
            assertEquals(expected, intBinomial.getInt(intBinomialContext));
            assertEquals((float) expected, floatBinomial.getFloat(floatBinomialContext));
        }
        long next = expectedRandom.nextLong();
        assertEquals(next, intBinomialContext.getRandom().nextLong());
        assertEquals(next, floatBinomialContext.getRandom().nextLong());
    }

    @Test
    void floatSumAndScoreUseNativeProvidersWithoutDroppingOrderOrScale() throws Exception {
        var sum = normalizedNumber("{\"type\":\"minecraft:sum\",\"summands\":[16777216,1,-16777216,-0.5]}", false);
        assertEquals("tacz:legacy_number", sum.getAsJsonObject().get("type").getAsString());
        var decodedSum = ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, sum).getOrThrow().value();
        float expected = 0;
        for (float summand : new float[]{16777216F, 1F, -16777216F, -0.5F}) {
            expected += summand;
        }
        assertEquals(expected, decodedSum.getFloat(null));
        for (String scale : List.of("", ",\"scale\":-0.25")) {
            var score = normalizedNumber("{\"type\":\"minecraft:score\",\"target\":\"this\",\"score\":\"ammo\"" + scale + "}", false).getAsJsonObject();
            assertEquals("tacz:legacy_number", score.get("type").getAsString());
            var decoded = assertInstanceOf(LegacyLootNumberProviders.FloatProvider.class,
                    ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, score).getOrThrow().value());
            var node = assertInstanceOf(LegacyLootNumber.Score.class, decoded.value());
            assertEquals(scale.isEmpty() ? 1F : -0.25F, node.scale());
            assertEquals("ammo", node.score());
            assertEquals(0F, decoded.getFloat(randomContext(1)), "Missing target returns zero before consulting a level");
        }
    }

    @Test
    void dynamicIntegerProvidersNowDecodeAndRetainOldContextAndRoundingSemantics() throws Exception {
        var sum = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE,
                normalizedNumber("{\"type\":\"minecraft:sum\",\"summands\":[-0.25]}", true)).getOrThrow().value();
        assertEquals(-1, sum.getInt(null));
        var enchantment = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE,
                normalizedNumber("{\"type\":\"minecraft:enchantment_level\",\"amount\":1.5}", true)).getOrThrow().value();
        var absent = randomContext(3);
        var error = assertThrows(NoSuchElementException.class, () -> enchantment.getInt(absent));
        assertEquals(LootContextParams.ENCHANTMENT_LEVEL.name().toString(), error.getMessage());
        var present = context(3, ContextMap.builder().set(LootContextParams.ENCHANTMENT_LEVEL, 2).build());
        assertEquals(2, enchantment.getInt(present));
        var environment = normalizedNumber("{\"type\":\"minecraft:environment_attribute\",\"attribute\":\"minecraft:visual/fog_start_distance\"}", true);
        assertInstanceOf(LegacyLootNumber.Environment.class, assertInstanceOf(LegacyLootNumberProviders.IntProvider.class,
                ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, environment).getOrThrow().value()).value());
        var scaledNativeDiscriminator = JsonParser.parseString("""
                {"loot_table":"minecraft:empty","pools":[{"rolls":1,"entries":[],
                  "modifier":{"type":"minecraft:set_count","count":{"type":"minecraft:score",
                    "target":"this","score":"ammo","scale":2}}}]}
                """).getAsJsonObject();
        var normalized = LootTableInjection.normalizeLegacyLootTable(scaledNativeDiscriminator);
        var count = normalized.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonObject("modifier").get("count");
        assertEquals(0, ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, count).getOrThrow().value().getInt(absent));
        assertDoesNotThrow(() -> LootTableInjection.fromJson(Identifier.parse("tacz:legacy_numbers"), scaledNativeDiscriminator));
    }

    @Test
    void nativeExpressionsAndStorageRemainIntactAndNormalizationIsIdempotent() {
        var nativeSource = JsonParser.parseString("""
                {"loot_table":"minecraft:empty","pools":[{"rolls":1,"entries":[
                  {"type":"minecraft:item","name":"minecraft:stick","functions":[{"function":"minecraft:set_nbt","tag":"{}"}]},
                  {"type":"minecraft:item","name":"minecraft:stick","modifier":{"type":"minecraft:set_count",
                    "count":{"type":"minecraft:from_float","input":0.75}}}]}]}
                """).getAsJsonObject();
        var expectedNative = nativeSource.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(1).deepCopy();
        var normalized = LootTableInjection.normalizeLegacyLootTable(nativeSource);
        assertEquals(expectedNative, normalized.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(1));
        assertEquals(normalized, LootTableInjection.normalizeLegacyLootTable(normalized));
        String storage = "{\"type\":\"minecraft:storage\",\"storage\":\"tacz:ammo\",\"path\":\"count\"}";
        assertEquals(JsonParser.parseString(storage), normalizedNumber(storage, true).getAsJsonObject().get("value"));
        assertDoesNotThrow(() -> ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(storage, true)).getOrThrow());
        assertThrows(JsonParseException.class, () -> LootTableInjection.normalizeLegacyLootTable(
                JsonParser.parseString("{\"pools\":[{\"rolls\":1.5,\"entries\":[]}]}").getAsJsonObject()));
    }

    @Test
    void nonFiniteValuesAndIntermediateResultsAreNotSanitizedByNativeGetters() throws Exception {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE}) {
            var encoded = normalizedNumber(new JsonPrimitive(value), false);
            var number = ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().value();
            assertEquals(value, number.getFloat(null));
            var ints = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(new JsonPrimitive(value), true)).getOrThrow().value();
            assertEquals(Math.round(value), ints.getInt(null));
            var roundTrip = ContextFloatProviders.CODEC.encodeStart(JsonOps.INSTANCE, net.minecraft.core.Holder.direct(number)).getOrThrow();
            assertEquals(value, ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, roundTrip).getOrThrow().value().getFloat(null));
        }
        var tree = JsonParser.parseString("{\"type\":\"minecraft:binomial\",\"n\":4,\"p\":0}").getAsJsonObject();
        for (float probability : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            tree.addProperty("p", probability);
            var number = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(tree, true)).getOrThrow().value();
            var context = randomContext(77);
            var random = RandomSource.create(77);
            int expected = 0;
            for (int i = 0; i < 4; i++) {
                if (random.nextFloat() < probability) {
                    expected++;
                }
            }
            assertEquals(expected, number.getInt(context));
            assertEquals(random.nextLong(), context.getRandom().nextLong());
        }
        var score = JsonParser.parseString("{\"type\":\"minecraft:score\",\"target\":\"this\",\"score\":\"ammo\"}").getAsJsonObject();
        score.addProperty("scale", Float.NaN);
        assertEquals(0F, ContextFloatProviders.CODEC.parse(JsonOps.INSTANCE, normalizedNumber(score, false)).getOrThrow().value().getFloat(randomContext(2)));
        var fallback = ContextIntProviders.CODEC.parse(JsonOps.INSTANCE,
                normalizedNumber("{\"type\":\"missing:old_type\",\"min\":4,\"max\":4}", true)).getOrThrow().value();
        assertEquals(4, fallback.getInt(randomContext(7)), "26.2's typed decode also fell back to a valid uniform shape");
        assertThrows(IllegalStateException.class, () -> ContextIntProviders.CODEC.parse(JsonOps.INSTANCE,
                normalizedNumber("{\"type\":\"missing:old_type\"}", true)).getOrThrow());
    }

    private static JsonElement normalizedNumber(String number, boolean integer) {
        return normalizedNumber(JsonParser.parseString(number), integer);
    }

    private static JsonElement normalizedNumber(JsonElement number, boolean integer) {
        var source = table("0", integer);
        source.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("functions").get(0).getAsJsonObject()
                .add(integer ? "count" : "damage", number);
        var normalized = LootTableInjection.normalizeLegacyLootTable(source);
        assertEquals(normalized, LootTableInjection.normalizeLegacyLootTable(normalized));
        return normalized.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonObject("modifier")
                .get(integer ? "count" : "damage");
    }

    private static JsonObject table(String number, boolean integer) {
        String type = integer ? "set_count" : "set_damage";
        String field = integer ? "count" : "damage";
        return JsonParser.parseString("{\"loot_table\":\"minecraft:empty\",\"pools\":[{\"rolls\":1,\"entries\":[]," +
                "\"functions\":[{\"function\":\"minecraft:" + type + "\",\"" + field + "\":" + number + "}]}]}").getAsJsonObject();
    }

    static LootContext randomContext(long seed) throws Exception {
        return context(seed, ContextMap.EMPTY);
    }

    private static LootContext context(long seed, ContextMap parameters) throws Exception {
        var constructor = LootContext.class.getDeclaredConstructor(LootParams.class, RandomSource.class, HolderGetter.Provider.class);
        assertTrue(constructor.trySetAccessible(), "Native RNG fixture needs access to the real constructor, never Unsafe or skipped assertions");
        // These tests use only RNG/parameter reads or score's absent-target branch, never a level operation.
        return constructor.newInstance(new LootParams(null, parameters, Map.of(), 0), RandomSource.create(seed),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
