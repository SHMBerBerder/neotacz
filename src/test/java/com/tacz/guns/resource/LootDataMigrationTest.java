package com.tacz.guns.resource;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchBlock;
import net.minecraft.world.level.storage.loot.providers.nbt.NbtProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LootDataMigrationTest {
    private static final List<String> BLOCKS = List.of("workbench_a", "workbench_b", "workbench_c",
            "gun_smith_table", "target", "statue");

    @BeforeAll
    static void bootstrap() {
        DataResourceTestSupport.bootstrap();
    }

    @Test
    void nativeLootCodecRetainsEveryConditionAndModifier() throws Exception {
        var ops = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).createSerializationContext(JsonOps.INSTANCE);
        assertEquals("loot_table", Registries.elementsDirPath(Registries.LOOT_TABLE));
        for (String block : BLOCKS) {
            JsonObject original = DataResourceTestSupport.json("tacz/loot_table/blocks/" + block + ".json");
            assertNull(getClass().getResource("/data/tacz/loot_tables/blocks/" + block + ".json"));
            assertEquals("minecraft:block", original.get("type").getAsString());
            assertEquals(1, original.getAsJsonArray("pools").size());
            var pool = original.getAsJsonArray("pools").get(0).getAsJsonObject();
            assertEquals(1, pool.get("rolls").getAsInt());
            assertEquals(0, pool.get("bonus_rolls").getAsInt());
            assertEquals("minecraft:survives_explosion", pool.getAsJsonObject("condition").get("type").getAsString());
            assertEquals(1, pool.getAsJsonArray("entries").size());
            var entry = pool.getAsJsonArray("entries").get(0).getAsJsonObject();
            assertEquals("minecraft:item", entry.get("type").getAsString());
            assertEquals("tacz:" + block, entry.get("name").getAsString());
            var predicate = entry.getAsJsonObject("condition");
            assertEquals("minecraft:match_block", predicate.get("type").getAsString());
            assertEquals("tacz:" + block, predicate.get("blocks").getAsString());
            boolean bed = block.equals("workbench_b") || block.equals("gun_smith_table");
            if (block.equals("workbench_a")) {
                assertFalse(predicate.has("state"));
            } else {
                assertEquals(bed ? "foot" : "lower", predicate.getAsJsonObject("state").get(bed ? "part" : "half").getAsString());
            }

            // Match representative native states without claiming that fixture blocks prove mod registration.
            JsonObject fixture = original.deepCopy();
            var fixtureEntry = fixture.getAsJsonArray("pools").get(0).getAsJsonObject()
                    .getAsJsonArray("entries").get(0).getAsJsonObject();
            String replacement = block.equals("workbench_a") ? "minecraft:crafting_table"
                    : bed ? "minecraft:red_bed" : "minecraft:oak_door";
            fixtureEntry.addProperty("name", replacement);
            fixtureEntry.getAsJsonObject("condition").addProperty("blocks", replacement);
            var table = LootTable.DIRECT_CODEC.parse(ops, fixture).getOrThrow();
            var encoded = LootTable.DIRECT_CODEC.encodeStart(ops, table).getOrThrow().getAsJsonObject();
            var encodedPool = encoded.getAsJsonArray("pools").get(0).getAsJsonObject();
            var encodedEntry = encodedPool.getAsJsonArray("entries").get(0).getAsJsonObject();
            assertEquals(pool.get("condition"), encodedPool.get("condition"), block);
            assertEquals(fixtureEntry.get("condition"), encodedEntry.get("condition"), block);
            MatchBlock match = assertInstanceOf(MatchBlock.class, LootItemCondition.DIRECT_CODEC
                    .parse(ops, fixtureEntry.get("condition")).getOrThrow());
            if (bed) {
                assertTrue(match.predicate().matchesState(Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.FOOT)));
                assertFalse(match.predicate().matchesState(Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
            } else if (!block.equals("workbench_a")) {
                assertTrue(match.predicate().matchesState(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)));
                assertFalse(match.predicate().matchesState(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
            }
            assertFalse(match.predicate().matchesState(Blocks.STONE.defaultBlockState()));
            if (block.startsWith("workbench_")) {
                assertCopyData(pool.getAsJsonObject("modifier"));
                assertCopyData(encodedPool.getAsJsonObject("modifier"));
                assertInstanceOf(CopyCustomDataFunction.class, LootItemFunctions.DIRECT_CODEC.parse(ops, pool.get("modifier")).getOrThrow());
            } else {
                assertFalse(encodedPool.has("modifier"));
            }
            if (block.equals("target")) {
                assertEquals(entry.get("modifier"), encodedEntry.get("modifier"));
                assertEquals("minecraft:copy_name", encodedEntry.getAsJsonObject("modifier").get("type").getAsString());
                assertEquals("block_entity", encodedEntry.getAsJsonObject("modifier").get("source").getAsString());
            } else {
                assertFalse(encodedEntry.has("modifier"));
            }
        }
    }

    @Test
    void nativeCopyOperationPreservesTableIdentityWithoutCopyingUnrelatedBlockData() throws Exception {
        for (String block : List.of("workbench_a", "workbench_b", "workbench_c")) {
            var modifier = DataResourceTestSupport.json("tacz/loot_table/blocks/" + block + ".json")
                    .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonObject("modifier");
            assertCopyData(modifier);
            var operation = modifier.getAsJsonArray("ops").get(0).getAsJsonObject();
            CompoundTag source = new CompoundTag();
            source.putString("BlockId", "example:custom_table");
            source.putInt("Unrelated", 42);
            NbtProvider provider = new NbtProvider() {
                @Override
                public Tag get(LootContext context) {
                    return source;
                }

                @Override
                public MapCodec<? extends NbtProvider> codec() {
                    throw new UnsupportedOperationException("In-memory source for native operation test");
                }
            };
            var function = (CopyCustomDataFunction) CopyCustomDataFunction.copyData(provider)
                    .copy(operation.get("source").getAsString(), operation.get("target").getAsString()).build();
            var item = new ItemStack(Items.CRAFTING_TABLE);
            CustomData.update(DataComponents.CUSTOM_DATA, item, tag -> tag.putInt("Existing", 9));
            function.run(item, null);
            var saved = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            assertEquals("example:custom_table", saved.getString("BlockId").orElseThrow());
            assertEquals(9, saved.getInt("Existing").orElseThrow());
            assertFalse(saved.contains("Unrelated"));
        }
    }

    private static void assertCopyData(JsonObject modifier) {
        assertNotNull(modifier, "The target codec must not silently discard old functions arrays");
        assertEquals("minecraft:copy_custom_data", modifier.get("type").getAsString());
        var source = modifier.get("source");
        if (source.isJsonPrimitive()) {
            assertEquals("block_entity", source.getAsString());
        } else {
            assertEquals("minecraft:context", Identifier.parse(source.getAsJsonObject().get("type").getAsString()).toString());
            assertEquals("block_entity", source.getAsJsonObject().get("target").getAsString());
        }
        assertEquals(1, modifier.getAsJsonArray("ops").size());
        var operation = modifier.getAsJsonArray("ops").get(0).getAsJsonObject();
        assertEquals("replace", operation.get("op").getAsString());
        assertEquals("BlockId", operation.get("source").getAsString());
        assertEquals("BlockId", operation.get("target").getAsString());
    }
}
