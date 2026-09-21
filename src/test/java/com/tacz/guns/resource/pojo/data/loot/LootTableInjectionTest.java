package com.tacz.guns.resource.pojo.data.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.tacz.guns.resource.manager.LootInjectionManager;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.functions.SetCustomDataFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.neoforged.neoforge.common.conditions.ICondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LootTableInjectionTest {
    private static final Identifier FILE_ID = Identifier.parse("tacz:spawn_bonus_chest_taurus943");
    private static final Identifier TABLE_ID = Identifier.parse("minecraft:chests/spawn_bonus_chest");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void bundledLegacyBonusKeepsGunIdentityAmmoIdentityAndNativeCountBounds() throws Exception {
        JsonObject original = bundledBonus();
        assertEquals("tacz:modern_kinetic_gun", entry(original, 0).get("name").getAsString());
        assertEquals("tacz:ammo", entry(original, 1).get("name").getAsString());
        JsonObject untouched = original.deepCopy();
        JsonObject normalized = LootTableInjection.normalizeLegacyLootTable(original);
        assertEquals(untouched, original);
        assertEquals(normalized, LootTableInjection.normalizeLegacyLootTable(normalized));
        assertEquals(2, normalized.getAsJsonArray("pools").size());
        for (JsonElement pool : normalized.getAsJsonArray("pools")) {
            assertEquals(1, pool.getAsJsonObject().get("rolls").getAsInt());
        }
        assertFalse(entry(normalized, 0).has("functions"));
        var gunFunction = assertInstanceOf(SetCustomDataFunction.class, LootItemFunctions.DIRECT_CODEC
                .parse(ops(), entry(normalized, 0).get("modifier")).getOrThrow());
        var gun = new ItemStack(Items.STICK);
        CustomData.update(DataComponents.CUSTOM_DATA, gun, tag -> tag.putInt("Existing", 7));
        gunFunction.run(gun, null);
        var gunData = gun.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        assertEquals("tacz:taurus943", gunData.getString("GunId").orElseThrow());
        assertEquals("SEMI", gunData.getString("GunFireMode").orElseThrow());
        assertEquals(7, gunData.getInt("Existing").orElseThrow());

        var ammoModifiers = entry(normalized, 1).getAsJsonArray("modifier");
        assertEquals(2, ammoModifiers.size());
        var ammoFunction = assertInstanceOf(SetCustomDataFunction.class, LootItemFunctions.DIRECT_CODEC
                .parse(ops(), ammoModifiers.get(0)).getOrThrow());
        var ammo = new ItemStack(Items.ARROW);
        ammoFunction.run(ammo, null);
        assertEquals("tacz:22wmr", ammo.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getString("AmmoId").orElseThrow());
        assertEquals("minecraft:set_count", ammoModifiers.get(1).getAsJsonObject().get("type").getAsString());
        var countProvider = assertInstanceOf(LegacyLootNumberProviders.IntProvider.class, ContextIntProviders.CODEC.parse(ops(),
                ammoModifiers.get(1).getAsJsonObject().get("count")).getOrThrow().value());
        var count = assertInstanceOf(LegacyLootNumber.Uniform.class, countProvider.value());
        assertEquals(38, count.min().getInt(null));
        assertEquals(45, count.max().getInt(null));

        // Fixture identities exercise the native codec, not TACZ registration or a live chest roll.
        JsonObject fixture = fixtureBonus(original);
        var injection = LootTableInjection.fromJson(FILE_ID, fixture, registries());
        assertEquals(List.of(TABLE_ID), injection.lootTables());
        var encoded = LootTable.DIRECT_CODEC.encodeStart(ops(), injection.lootTable()).getOrThrow().getAsJsonObject();
        assertEquals(2, encoded.getAsJsonArray("pools").size());
        assertNotNull(entry(encoded, 0).get("modifier"));
        assertEquals(2, entry(encoded, 1).getAsJsonArray("modifier").size());
    }

    @Test
    void structuralContextsKeepAndConditionsAndOrderedModifiersWithoutWalkingCustomData() {
        JsonObject original = json("""
                {"loot_table":"minecraft:chests/spawn_bonus_chest","pools":[{
                  "rolls":1,"conditions":[{"condition":"minecraft:survives_explosion"},
                    {"condition":"minecraft:inverted","term":{"condition":"minecraft:alternative","terms":[
                      {"condition":"minecraft:block_state_property","block":"minecraft:oak_door","properties":{"half":"lower"}}
                    ]}}],"entries":[{"type":"minecraft:alternatives","children":[{
                      "type":"minecraft:item","name":"minecraft:stick","functions":[
                        {"function":"minecraft:set_nbt","tag":{"function":"minecraft:set_nbt","functions":[1],
                          "condition":"opaque","conditions":[2],"type":"opaque","nested":{"min":1,"max":2}}},
                        {"function":"minecraft:set_components","components":{"minecraft:custom_data":{"functions":[3]}}}
                      ]}]}]}]}
                """);
        var normalized = LootTableInjection.normalizeLegacyLootTable(original);
        var pool = normalized.getAsJsonArray("pools").get(0).getAsJsonObject();
        var all = pool.getAsJsonObject("condition");
        assertEquals("minecraft:all_of", all.get("type").getAsString());
        assertEquals(2, all.getAsJsonArray("terms").size());
        var any = all.getAsJsonArray("terms").get(1).getAsJsonObject().getAsJsonObject("term");
        assertEquals("minecraft:any_of", any.get("type").getAsString());
        var match = any.getAsJsonArray("terms").get(0).getAsJsonObject();
        assertEquals("minecraft:match_block", match.get("type").getAsString());
        assertEquals("minecraft:oak_door", match.get("blocks").getAsString());
        assertEquals("lower", match.getAsJsonObject("state").get("half").getAsString());
        var originalFunctions = entry(original, 0).getAsJsonArray("children").get(0).getAsJsonObject().getAsJsonArray("functions");
        var modifiers = entry(normalized, 0).getAsJsonArray("children").get(0).getAsJsonObject().getAsJsonArray("modifier");
        assertEquals("minecraft:set_custom_data", modifiers.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(originalFunctions.get(0).getAsJsonObject().get("tag"), modifiers.get(0).getAsJsonObject().get("tag"));
        assertEquals(originalFunctions.get(1).getAsJsonObject().get("components"), modifiers.get(1).getAsJsonObject().get("components"));
        assertEquals(normalized, LootTableInjection.normalizeLegacyLootTable(normalized));
        assertDoesNotThrow(() -> LootTableInjection.fromJson(FILE_ID, original, registries()));
    }

    @Test
    void conflictingSchemasAreRejectedRatherThanSilentlyDroppingOneSide() {
        for (String body : List.of(
                "\"functions\":[],\"modifier\":[]",
                "\"conditions\":[],\"condition\":{\"type\":\"minecraft:survives_explosion\"}",
                "\"functions\":[{\"function\":\"minecraft:set_nbt\",\"type\":\"minecraft:set_custom_data\",\"tag\":\"{}\"}]")) {
            var source = json("{\"loot_table\":\"minecraft:empty\",\"pools\":[{\"rolls\":1,\"entries\":[]," + body + "}]}");
            var exception = assertThrows(JsonParseException.class, () -> LootTableInjection.fromJson(FILE_ID, source, registries()));
            assertTrue(exception.getMessage().contains(FILE_ID.toString()));
            assertTrue(exception.getMessage().contains("Conflicting loot fields"));
        }
        var unknown = json("""
                {"loot_table":"minecraft:empty","pools":[{"rolls":1,"entries":[],
                  "functions":[{"function":"missing:modifier"}]}]}
                """);
        assertThrows(JsonParseException.class, () -> LootTableInjection.fromJson(FILE_ID, unknown, registries()));
    }

    @Test
    void unknownFunctionPrivateFieldsAreNotInterpretedAsVanillaSchema() {
        var source = json("""
                {"loot_table":"minecraft:empty","pools":[{"rolls":1,"entries":[],"functions":[{
                  "function":"example:opaque","count":{"min":1,"max":2},"damage":{"min":3,"max":4},
                  "levels":{"min":5,"max":6},"terms":[{"condition":"private"}],
                  "modifier":{"function":"private"},"functions":[{"function":"private"}],
                  "on_pass":{"function":"private"},"on_fail":{"function":"private"}
                }]}]}
                """);
        var expected = source.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("functions").get(0).getAsJsonObject().deepCopy();
        expected.add("type", expected.remove("function"));
        var normalized = LootTableInjection.normalizeLegacyLootTable(source);
        assertEquals(expected, normalized.getAsJsonArray("pools").get(0).getAsJsonObject().get("modifier"));
    }

    @Test
    void reloadUsesFreshRegistryProviderAndClearsStaleOrInvalidInjections() throws Exception {
        var manager = new TestManager();
        var fixture = fixtureBonus(bundledBonus());
        fixture.getAsJsonArray("pools").get(0).getAsJsonObject().add("conditions", JsonParser.parseString(
                "[{\"condition\":\"minecraft:reference\",\"name\":\"tacz:test_predicate\"}]"));
        var firstLookups = new AtomicInteger();
        manager.load(Map.of(FILE_ID, List.of(fixture)), provider(false, firstLookups));
        assertEquals(1, manager.getInjections(TABLE_ID).size());
        assertTrue(firstLookups.get() > 0);

        var secondLookups = new AtomicInteger();
        manager.load(Map.of(FILE_ID, List.of(fixture)), provider(true, secondLookups));
        assertTrue(manager.getInjections(TABLE_ID).isEmpty());
        assertTrue(secondLookups.get() > 0, "The second reload must not use the first registry view or BuiltIns");

        var invalid = fixture.deepCopy();
        entry(invalid, 0).add("modifier", new com.google.gson.JsonArray());
        manager.load(Map.of(FILE_ID, List.of(invalid), Identifier.parse("tacz:valid"), List.of(fixture)), provider(false, new AtomicInteger()));
        assertEquals(1, manager.getInjections(TABLE_ID).size(), "One malformed contribution must not discard an independent valid file");
        manager.load(Map.of(), registries());
        assertTrue(manager.getInjections(TABLE_ID).isEmpty());
    }

    private static HolderLookup.Provider provider(boolean rejectPredicate, AtomicInteger predicateLookups) {
        var predicates = new MappedRegistry<LootItemCondition>(Registries.PREDICATE, Lifecycle.stable());
        if (!rejectPredicate) {
            predicates.register(ResourceKey.create(Registries.PREDICATE, Identifier.parse("tacz:test_predicate")),
                    LootItemCondition.DIRECT_CODEC.parse(ops(), json("{\"type\":\"minecraft:survives_explosion\"}")).getOrThrow(),
                    RegistrationInfo.BUILT_IN);
        }
        predicates.freeze();
        var delegate = HolderLookup.Provider.create(Stream.concat(registries().listRegistries(), Stream.of(predicates)));
        return new HolderLookup.Provider() {
            @Override
            public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
                return delegate.listRegistryKeys();
            }

            @Override
            public <T> Optional<? extends HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                var lookup = delegate.lookup(key);
                if (key.equals(Registries.PREDICATE)) {
                    predicateLookups.incrementAndGet();
                }
                return lookup;
            }
        };
    }

    private static JsonObject fixtureBonus(JsonObject original) {
        var fixture = original.deepCopy();
        entry(fixture, 0).addProperty("name", "minecraft:stick");
        entry(fixture, 1).addProperty("name", "minecraft:arrow");
        return fixture;
    }

    private static JsonObject bundledBonus() throws Exception {
        String path = "/assets/tacz/custom/tacz_default_gun/data/tacz/tacz_loot_injectors/spawn_bonus_chest_taurus943.json";
        try (var stream = LootTableInjectionTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject entry(JsonObject table, int pool) {
        return table.getAsJsonArray("pools").get(pool).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
    }

    private static JsonObject json(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static HolderLookup.Provider registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static net.minecraft.resources.RegistryOps<JsonElement> ops() {
        return registries().createSerializationContext(JsonOps.INSTANCE);
    }

    private static final class TestManager extends LootInjectionManager {
        void load(Map<Identifier, List<JsonElement>> resources, HolderLookup.Provider provider) {
            injectContext(ICondition.IContext.EMPTY, provider);
            apply(resources, null, null);
        }
    }
}
