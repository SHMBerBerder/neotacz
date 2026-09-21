package com.tacz.guns.resource;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagFile;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TagAndRegistryDataMigrationTest {
    private static final List<String> BLOCK_TAGS = List.of("bullet_ignore", "interact_key/blacklist", "interact_key/whitelist");
    private static final List<String> ENTITY_TAGS = List.of("pretend_melee_damage_on", "use_magic_damage_on", "use_void_damage_on",
            "interact_key/blacklist", "interact_key/whitelist");
    private static final Set<String> MOD_BLOCKS = Set.of("gun_smith_table", "workbench_a", "workbench_b", "workbench_c", "target", "statue");

    @BeforeAll
    static void bootstrap() {
        DataResourceTestSupport.bootstrap();
    }

    @Test
    void blockAndEntityTagsUseNativeDirectoriesAndExistingReferences() throws Exception {
        assertEquals("tags/block", Registries.tagsDirPath(Registries.BLOCK));
        assertEquals("tags/entity_type", Registries.tagsDirPath(Registries.ENTITY_TYPE));
        for (String id : BLOCK_TAGS) {
            var json = DataResourceTestSupport.json("tacz/tags/block/" + id + ".json");
            var tag = TagFile.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertFalse(tag.replace());
            assertNull(getClass().getResource("/data/tacz/tags/blocks/" + id + ".json"));
            for (var entry : tag.entries()) {
                assertTrue(entry.verifyIfPresent(block -> block.getNamespace().equals("tacz")
                                ? MOD_BLOCKS.contains(block.getPath()) : BuiltInRegistries.BLOCK.containsKey(block),
                        nested -> DataResourceTestSupport.hasResource(nested.getNamespace() + "/tags/block/" + nested.getPath() + ".json")),
                        id + ": " + entry);
            }
        }
        for (String id : ENTITY_TAGS) {
            var json = DataResourceTestSupport.json("tacz/tags/entity_type/" + id + ".json");
            var tag = TagFile.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertFalse(tag.replace());
            assertNull(getClass().getResource("/data/tacz/tags/entity_types/" + id + ".json"));
            for (var entry : tag.entries()) {
                assertTrue(entry.verifyIfPresent(BuiltInRegistries.ENTITY_TYPE::containsKey,
                        nested -> DataResourceTestSupport.hasResource(nested.getNamespace() + "/tags/entity_type/" + nested.getPath() + ".json")),
                        id + ": " + entry);
            }
        }
        assertEquals(List.of("minecraft:iron_bars", "#minecraft:fences", "#minecraft:fence_gates", "#minecraft:leaves"),
                values("tacz/tags/block/bullet_ignore.json"));
        assertEquals(List.of("minecraft:enderman"), values("tacz/tags/entity_type/use_magic_damage_on.json"));
        for (String id : List.of("pretend_melee_damage_on", "use_void_damage_on", "interact_key/blacklist")) {
            assertTrue(values("tacz/tags/entity_type/" + id + ".json").isEmpty());
        }
        assertTrue(values("tacz/tags/block/interact_key/blacklist.json").isEmpty());
    }

    @Test
    void interactionWhitelistRetainsEveryBoatAndExistingNonBoatTarget() throws Exception {
        List<String> expected = new ArrayList<>(List.of("minecraft:camel", "minecraft:minecart", "minecraft:chest_minecart",
                "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:interaction", "#minecraft:boat"));
        // 1.20.1's two boat entity IDs split into wood-specific entities; chest boats have no vanilla tag.
        Set<String> chestBoats = BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                .filter(id -> id.getNamespace().equals("minecraft"))
                .filter(id -> id.getPath().endsWith("_chest_boat") || id.getPath().equals("bamboo_chest_raft"))
                .map(Identifier::toString).collect(Collectors.toSet());
        assertEquals(11, chestBoats.size(), "Review new native boat types instead of silently omitting them");
        expected.addAll(chestBoats);
        expected.addAll(List.of("minecraft:mule", "minecraft:villager", "minecraft:wandering_trader", "minecraft:horse"));
        List<String> actual = values("tacz/tags/entity_type/interact_key/whitelist.json");
        assertEquals(expected.size(), actual.size());
        assertEquals(Set.copyOf(expected), Set.copyOf(actual));
        var ordinaryBoats = values("minecraft/tags/entity_type/boat.json");
        assertTrue(ordinaryBoats.contains("minecraft:oak_boat"));
        assertTrue(ordinaryBoats.contains("minecraft:bamboo_raft"));
        ordinaryBoats.forEach(id -> assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(id)), id));
    }

    @Test
    void damageAndPaintingDefinitionsKeepTheirNativeMeaning() throws Exception {
        List<String> damageTypes = List.of("bullet", "bullet_ignore_armor", "bullet_void", "bullet_void_ignore_armor");
        for (String id : damageTypes) {
            var damage = DamageType.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                    DataResourceTestSupport.json("tacz/damage_type/" + id + ".json")).getOrThrow();
            assertEquals("tacz.bullet", damage.msgId());
            assertEquals(DamageScaling.NEVER, damage.scaling());
            assertEquals(0.05F, damage.exhaustion());
        }
        assertEquals(damageTypes.stream().map(id -> "tacz:" + id).toList(), values("tacz/tags/damage_type/bullets.json"));
        assertEquals(List.of("tacz:bullet_ignore_armor", "tacz:bullet_void_ignore_armor"), values("minecraft/tags/damage_type/bypasses_armor.json"));
        assertEquals(List.of("tacz:bullet_void", "tacz:bullet_void_ignore_armor"), values("minecraft/tags/damage_type/bypasses_invulnerability.json"));
        var painting = PaintingVariant.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                DataResourceTestSupport.json("tacz/painting_variant/blood_strike_1.json")).getOrThrow();
        assertEquals(2, painting.width());
        assertEquals(2, painting.height());
        assertEquals(Identifier.parse("tacz:blood_strike_1"), painting.assetId());
        assertTrue(painting.title().isPresent());
        assertTrue(painting.author().isPresent());
        assertEquals(List.of("tacz:blood_strike_1"), values("minecraft/tags/painting_variant/placeable.json"));
    }

    private static List<String> values(String path) throws Exception {
        return DataResourceTestSupport.json(path).getAsJsonArray("values").asList().stream().map(JsonElement::getAsString).toList();
    }
}
