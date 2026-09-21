package com.tacz.guns.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tacz.guns.init.ModLootModifiers;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.pojo.data.loot.LootTableInjection;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public class LootTableInjectorModifier extends LootModifier {
    private static final Codec<List<Dynamic<?>>> EMPTY_LEGACY_CONDITIONS = Codec.PASSTHROUGH.listOf().validate(conditions ->
            conditions.isEmpty() ? DataResult.success(conditions) : DataResult.error(() ->
                    "TacZ legacy conditions must be migrated to condition with minecraft:all_of; refusing to ignore non-empty conditions"));
    public static final MapCodec<LootTableInjectorModifier> CODEC = RecordCodecBuilder.mapCodec(instance ->
            codecStart(instance)
                    .and(EMPTY_LEGACY_CONDITIONS.optionalFieldOf("conditions", List.of()).forGetter(modifier -> List.of()))
                    .apply(instance, (condition, priority, legacyConditions) -> new LootTableInjectorModifier(condition, priority)));

    public LootTableInjectorModifier(Optional<Holder<LootItemCondition>> condition, int priority) {
        super(condition, priority);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        CommonAssetsManager manager = CommonAssetsManager.getInstance();
        if (manager == null) {
            return generatedLoot;
        }

        Identifier lootTableId = context.getQueriedLootTableId();
        List<LootTableInjection> injections = manager.getLootTableInjections(lootTableId);
        if (injections.isEmpty()) {
            return generatedLoot;
        }

        for (LootTableInjection injection : injections) {
            for (ItemStack stack : injection.createStacks(context)) {
                generatedLoot.add(stack);
            }
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return ModLootModifiers.LOOT_TABLE_INJECTOR.get();
    }
}
