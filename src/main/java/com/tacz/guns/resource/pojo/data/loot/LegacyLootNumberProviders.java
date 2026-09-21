package com.tacz.guns.resource.pojo.data.loot;

import com.mojang.serialization.MapCodec;
import com.tacz.guns.GunMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.ValidationContext;
import net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LegacyLootNumberProviders {
    public static final DeferredRegister<MapCodec<? extends ContextIntProvider>> INTS =
            DeferredRegister.create(Registries.CONTEXT_INT_PROVIDER_TYPE, GunMod.MOD_ID);
    public static final DeferredRegister<MapCodec<? extends ContextFloatProvider>> FLOATS =
            DeferredRegister.create(Registries.CONTEXT_FLOAT_PROVIDER_TYPE, GunMod.MOD_ID);

    static {
        INTS.register("legacy_number", () -> IntProvider.MAP_CODEC);
        FLOATS.register("legacy_number", () -> FloatProvider.MAP_CODEC);
    }

    private LegacyLootNumberProviders() {
    }

    record IntProvider(LegacyLootNumber value) implements ContextIntProvider {
        static final MapCodec<IntProvider> MAP_CODEC = LegacyLootNumber.CODEC.fieldOf("value").xmap(IntProvider::new, IntProvider::value);

        @Override
        public int getInt(LootContext context) {
            return value.getInt(context);
        }

        @Override
        public int getIntUnsafe(LootContext context) {
            return value.getInt(context);
        }

        @Override
        public MapCodec<IntProvider> codec() {
            return MAP_CODEC;
        }

        @Override
        public void validate(ValidationContext context) {
            value.validate(context);
        }
    }

    record FloatProvider(LegacyLootNumber value) implements ContextFloatProvider {
        static final MapCodec<FloatProvider> MAP_CODEC = LegacyLootNumber.CODEC.fieldOf("value").xmap(FloatProvider::new, FloatProvider::value);

        @Override
        public float getFloat(LootContext context) {
            return value.getFloat(context);
        }

        @Override
        public float getFloatUnsafe(LootContext context) {
            return value.getFloat(context);
        }

        @Override
        public MapCodec<FloatProvider> codec() {
            return MAP_CODEC;
        }

        @Override
        public void validate(ValidationContext context) {
            value.validate(context);
        }
    }
}
