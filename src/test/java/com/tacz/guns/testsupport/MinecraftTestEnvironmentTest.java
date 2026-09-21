package com.tacz.guns.testsupport;

import com.tacz.guns.init.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftTestEnvironmentTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    @Test
    void bindsRealVanillaAndModItemDefaults() {
        assertEquals(64, new ItemStack(Items.STICK).getMaxStackSize());
        assertEquals(1561, new ItemStack(Items.DIAMOND_SWORD).getMaxDamage());
        assertEquals(1, new ItemStack(ModItems.MODERN_KINETIC_GUN.get()).getMaxStackSize());
        assertTrue(BuiltInRegistries.ITEM.listElements().allMatch(Holder.Reference::areComponentsBound));
    }

    @Test
    void defaultToolComponentsUseTheLoadedBlockTags() {
        var pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        var tool = pickaxe.get(DataComponents.TOOL);
        assertNotNull(tool);
        assertTrue(Blocks.STONE.builtInRegistryHolder().is(BlockTags.MINEABLE_WITH_PICKAXE));
        assertTrue(tool.isCorrectForDrops(Blocks.STONE.defaultBlockState()));
        assertTrue(tool.getMiningSpeed(Blocks.STONE.defaultBlockState()) > tool.defaultMiningSpeed());
    }

    @Test
    void repeatedBootstrapDoesNotReloadOrReplaceBoundDefaults() {
        var components = Items.STICK.components();
        MinecraftTestEnvironment.bootstrap();
        assertSame(components, Items.STICK.components());
    }

    @Test
    void exposesTheLoadedWorldAndReloadableRegistries() {
        var registries = MinecraftTestEnvironment.registries();
        assertTrue(registries.lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.UNBREAKING).isPresent());
        assertTrue(registries.lookupOrThrow(Registries.LOOT_TABLE).get(ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.parse("minecraft:chests/spawn_bonus_chest"))).isPresent());
        assertSame(registries, MinecraftTestEnvironment.registries());
    }
}
