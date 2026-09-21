package com.tacz.guns.resource.pojo.data.loot;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootContextUser;
import net.minecraft.world.level.storage.loot.Validatable;
import net.minecraft.world.level.storage.loot.ValidationContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.StoredNumberAccess;
import net.minecraft.world.level.storage.loot.providers.number.floats.EnvironmentAttributeValue;
import net.minecraft.world.level.storage.loot.providers.score.ScoreboardNameProvider;
import net.minecraft.world.level.storage.loot.providers.score.ScoreboardNameProviders;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/** The eight number-provider nodes accepted by the 26.2 gunpack injection codec. */
sealed interface LegacyLootNumber extends LootContextUser {
    Codec<LegacyLootNumber> CODEC = Codec.lazyInitialized(() -> {
        Codec<LegacyLootNumber> typed = Kind.CODEC.dispatch(LegacyLootNumber::kind, Kind::codec);
        // 26.2 also tries the uniform shape after a typed decode fails, including unknown types with valid bounds.
        return Codec.either(Constant.INLINE_CODEC, Codec.withAlternative(typed, Uniform.MAP_CODEC.codec()))
                .xmap(Either::unwrap, value -> value instanceof Constant constant ? Either.left(constant) : Either.right(value));
    });

    float getFloat(LootContext context);

    default int getInt(LootContext context) {
        return Math.round(getFloat(context));
    }

    Kind kind();

    enum Kind {
        CONSTANT, UNIFORM, BINOMIAL, SCORE, STORAGE, SUM, ENCHANTMENT_LEVEL, ENVIRONMENT_ATTRIBUTE;

        static final Codec<Kind> CODEC = Identifier.CODEC.comapFlatMap(id -> Arrays.stream(values())
                .filter(kind -> kind.id().equals(id)).findFirst().map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "Unknown legacy loot number provider: " + id)), Kind::id);

        Identifier id() {
            return Identifier.withDefaultNamespace(name().toLowerCase(java.util.Locale.ROOT));
        }

        MapCodec<? extends LegacyLootNumber> codec() {
            return switch (this) {
                case CONSTANT -> Constant.MAP_CODEC;
                case UNIFORM -> Uniform.MAP_CODEC;
                case BINOMIAL -> Binomial.MAP_CODEC;
                case SCORE -> Score.MAP_CODEC;
                case STORAGE -> Storage.MAP_CODEC;
                case SUM -> Sum.MAP_CODEC;
                case ENCHANTMENT_LEVEL -> EnchantmentLevel.MAP_CODEC;
                case ENVIRONMENT_ATTRIBUTE -> Environment.MAP_CODEC;
            };
        }
    }

    record Constant(float value) implements LegacyLootNumber {
        static final MapCodec<Constant> MAP_CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(Codec.FLOAT.fieldOf("value").forGetter(Constant::value)).apply(i, Constant::new));
        static final Codec<Constant> INLINE_CODEC = Codec.FLOAT.xmap(Constant::new, Constant::value);

        @Override
        public float getFloat(LootContext context) {
            return value;
        }

        @Override
        public Kind kind() {
            return Kind.CONSTANT;
        }
    }

    record Uniform(LegacyLootNumber min, LegacyLootNumber max) implements LegacyLootNumber {
        static final MapCodec<Uniform> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                CODEC.fieldOf("min").forGetter(Uniform::min), CODEC.fieldOf("max").forGetter(Uniform::max)).apply(i, Uniform::new));

        @Override
        public int getInt(LootContext context) {
            return Mth.nextInt(context.getRandom(), min.getInt(context), max.getInt(context));
        }

        @Override
        public float getFloat(LootContext context) {
            return Mth.nextFloat(context.getRandom(), min.getFloat(context), max.getFloat(context));
        }

        @Override
        public void validate(ValidationContext context) {
            LegacyLootNumber.super.validate(context);
            Validatable.validate(context, "min", min);
            Validatable.validate(context, "max", max);
        }

        @Override
        public Kind kind() {
            return Kind.UNIFORM;
        }
    }

    record Binomial(LegacyLootNumber n, LegacyLootNumber p) implements LegacyLootNumber {
        static final MapCodec<Binomial> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                CODEC.fieldOf("n").forGetter(Binomial::n), CODEC.fieldOf("p").forGetter(Binomial::p)).apply(i, Binomial::new));

        @Override
        public int getInt(LootContext context) {
            int trials = n.getInt(context);
            float probability = p.getFloat(context);
            var random = context.getRandom();
            int result = 0;
            for (int i = 0; i < trials; i++) {
                if (random.nextFloat() < probability) {
                    result++;
                }
            }
            return result;
        }

        @Override
        public float getFloat(LootContext context) {
            return getInt(context);
        }

        @Override
        public void validate(ValidationContext context) {
            LegacyLootNumber.super.validate(context);
            Validatable.validate(context, "n", n);
            Validatable.validate(context, "p", p);
        }

        @Override
        public Kind kind() {
            return Kind.BINOMIAL;
        }
    }

    record Score(ScoreboardNameProvider target, String score, float scale) implements LegacyLootNumber {
        static final MapCodec<Score> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ScoreboardNameProviders.CODEC.fieldOf("target").forGetter(Score::target),
                Codec.STRING.fieldOf("score").forGetter(Score::score),
                Codec.FLOAT.optionalFieldOf("scale", 1F).forGetter(Score::scale)).apply(i, Score::new));

        @Override
        public float getFloat(LootContext context) {
            var holder = target.getScoreHolder(context);
            if (holder == null) {
                return 0F;
            }
            var scoreboard = context.getLevel().getScoreboard();
            var objective = scoreboard.getObjective(score);
            if (objective == null) {
                return 0F;
            }
            var value = scoreboard.getPlayerScoreInfo(holder, objective);
            return value == null ? 0F : value.value() * scale;
        }

        @Override
        public void validate(ValidationContext context) {
            LegacyLootNumber.super.validate(context);
            Validatable.validate(context, "target", target);
        }

        @Override
        public Kind kind() {
            return Kind.SCORE;
        }
    }

    record Storage(StoredNumberAccess access) implements LegacyLootNumber {
        static final MapCodec<Storage> MAP_CODEC = StoredNumberAccess.MAP_CODEC.xmap(Storage::new, Storage::access);

        @Override
        public int getInt(LootContext context) {
            Number value = access.getNumericTag(context);
            return value == null ? 0 : value.intValue();
        }

        @Override
        public float getFloat(LootContext context) {
            Number value = access.getNumericTag(context);
            return value == null ? 0F : value.floatValue();
        }

        @Override
        public Kind kind() {
            return Kind.STORAGE;
        }
    }

    record Sum(List<LegacyLootNumber> summands) implements LegacyLootNumber {
        static final MapCodec<Sum> MAP_CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(CODEC.listOf().fieldOf("summands").forGetter(Sum::summands)).apply(i, Sum::new));

        @Override
        public int getInt(LootContext context) {
            return (int) Math.floor(getFloat(context));
        }

        @Override
        public float getFloat(LootContext context) {
            float value = 0F;
            for (LegacyLootNumber summand : summands) {
                value += summand.getFloat(context);
            }
            return value;
        }

        @Override
        public void validate(ValidationContext context) {
            LegacyLootNumber.super.validate(context);
            Validatable.validate(context, "summands", summands);
        }

        @Override
        public Kind kind() {
            return Kind.SUM;
        }
    }

    record EnchantmentLevel(LevelBasedValue amount) implements LegacyLootNumber {
        static final MapCodec<EnchantmentLevel> MAP_CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(LevelBasedValue.CODEC.fieldOf("amount").forGetter(EnchantmentLevel::amount)).apply(i, EnchantmentLevel::new));

        @Override
        public float getFloat(LootContext context) {
            Integer level = context.getOptional(LootContextParams.ENCHANTMENT_LEVEL);
            if (level == null) {
                throw new NoSuchElementException(LootContextParams.ENCHANTMENT_LEVEL.name().toString());
            }
            return amount.calculate(level);
        }

        @Override
        public Kind kind() {
            return Kind.ENCHANTMENT_LEVEL;
        }
    }

    record Environment(EnvironmentAttributeValue delegate) implements LegacyLootNumber {
        static final MapCodec<Environment> MAP_CODEC = EnvironmentAttributeValue.MAP_CODEC.xmap(Environment::new, Environment::delegate);

        @Override
        public float getFloat(LootContext context) {
            return delegate.getFloatUnsafe(context);
        }

        @Override
        public void validate(ValidationContext context) {
            delegate.validate(context);
        }

        @Override
        public Kind kind() {
            return Kind.ENVIRONMENT_ATTRIBUTE;
        }
    }
}
