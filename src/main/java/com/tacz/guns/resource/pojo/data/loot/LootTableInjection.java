package com.tacz.guns.resource.pojo.data.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

public record LootTableInjection(List<Identifier> lootTables, LootTable lootTable) {
    public static LootTableInjection fromJson(Identifier fileId, JsonElement element) {
        // Standalone callers have only static registries; reloads must pass their current registry view.
        return fromJson(fileId, element, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    public static LootTableInjection fromJson(Identifier fileId, JsonElement element, HolderLookup.Provider registries) {
        JsonObject object = GsonHelper.convertToJsonObject(element, "loot injection");
        List<Identifier> lootTables = readLootTables(fileId, object);
        if (!object.has("pools")) {
            throw new JsonParseException("Loot injection " + fileId + " must define pools");
        }
        try {
            JsonObject lootTableJson = normalizeLegacyLootTable(object);
            LootTable lootTable = LootTable.DIRECT_CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), lootTableJson)
                    .getOrThrow(JsonParseException::new);
            lootTable.setLootTableId(fileId);
            return new LootTableInjection(lootTables, lootTable);
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new JsonParseException("Invalid loot injection " + fileId + ": " + exception.getMessage(), exception);
        }
    }

    private static List<Identifier> readLootTables(Identifier fileId, JsonObject object) {
        List<Identifier> lootTables = new ArrayList<>();
        if (object.has("loot_tables")) {
            for (JsonElement table : GsonHelper.getAsJsonArray(object, "loot_tables")) {
                lootTables.add(Identifier.parse(GsonHelper.convertToString(table, "loot table")));
            }
        } else if (object.has("loot_table")) {
            lootTables.add(Identifier.parse(GsonHelper.getAsString(object, "loot_table")));
        } else {
            throw new JsonParseException("Loot injection " + fileId + " must define loot_table or loot_tables");
        }
        return List.copyOf(lootTables);
    }

    static JsonObject normalizeLegacyLootTable(JsonObject source) {
        JsonObject table = source.deepCopy();
        normalizeTable(table);
        return table;
    }

    private static void normalizeTable(JsonObject table) {
        normalizeModifier(table);
        if (table.has("pools")) {
            for (JsonElement value : GsonHelper.getAsJsonArray(table, "pools")) {
                JsonObject pool = GsonHelper.convertToJsonObject(value, "loot pool");
                boolean legacy = pool.has("conditions") || pool.has("functions");
                normalizeConditionField(pool);
                normalizeModifier(pool);
                normalizeNumberField(pool, "rolls", true, legacy);
                normalizeNumberField(pool, "bonus_rolls", false, legacy);
                normalizeEntries(pool, "entries");
            }
        }
    }

    private static void normalizeEntries(JsonObject parent, String field) {
        if (!parent.has(field)) {
            return;
        }
        for (JsonElement value : GsonHelper.getAsJsonArray(parent, field)) {
            JsonObject entry = GsonHelper.convertToJsonObject(value, "loot entry");
            normalizeConditionField(entry);
            normalizeModifier(entry);
            normalizeEntries(entry, "children");
            if ("minecraft:loot_table".equals(type(entry))) {
                moveField(entry, "name", "value");
                if (entry.has("value") && entry.get("value").isJsonObject()) {
                    normalizeTable(entry.getAsJsonObject("value"));
                }
            }
        }
    }

    private static void normalizeConditionField(JsonObject owner) {
        if (owner.has("conditions")) {
            rejectConflict(owner, "conditions", "condition");
            JsonArray conditions = map(GsonHelper.getAsJsonArray(owner, "conditions"), LootTableInjection::normalizeCondition);
            owner.remove("conditions");
            if (conditions.size() == 1) {
                owner.add("condition", conditions.get(0));
            } else if (!conditions.isEmpty()) {
                JsonObject allOf = new JsonObject();
                allOf.addProperty("type", "minecraft:all_of");
                allOf.add("terms", conditions);
                owner.add("condition", allOf);
            }
        } else if (owner.has("condition")) {
            owner.add("condition", normalizeCondition(owner.get("condition")));
        }
    }

    private static JsonElement normalizeCondition(JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value;
        }
        JsonObject condition = GsonHelper.convertToJsonObject(value, "loot condition");
        boolean legacy = condition.has("condition");
        moveField(condition, "condition", "type");
        switch (type(condition)) {
            case "minecraft:block_state_property" -> {
                condition.addProperty("type", "minecraft:match_block");
                moveField(condition, "block", "blocks");
                moveField(condition, "properties", "state");
            }
            case "minecraft:alternative" -> condition.addProperty("type", "minecraft:any_of");
            case "minecraft:value_check" -> {
                condition.addProperty("type", "minecraft:int_value_check");
                moveField(condition, "range", "test");
                normalizeNumberField(condition, "value", true, true);
                if (condition.has("test")) {
                    JsonObject range = normalizeRange(condition.get("test"), true, "value_check.range");
                    if (!range.has("min") && !range.has("max")) {
                        range.addProperty("min", Integer.MIN_VALUE);
                    }
                    condition.add("test", range);
                }
            }
            case "minecraft:reference" -> {
                return new JsonPrimitive(GsonHelper.getAsString(condition, "name"));
            }
            default -> { }
        }
        String conditionType = type(condition);
        if (("minecraft:all_of".equals(conditionType) || "minecraft:any_of".equals(conditionType)) && condition.has("terms")) {
            condition.add("terms", map(GsonHelper.getAsJsonArray(condition, "terms"), LootTableInjection::normalizeCondition));
        }
        if ("minecraft:inverted".equals(conditionType) && condition.has("term")) {
            condition.add("term", normalizeCondition(condition.get("term")));
        }
        if ("minecraft:random_chance".equals(type(condition))) {
            normalizeNumberField(condition, "chance", false, legacy);
        }
        if (legacy && "minecraft:entity_scores".equals(type(condition)) && condition.has("scores")) {
            var scores = GsonHelper.getAsJsonObject(condition, "scores");
            for (var entry : scores.entrySet()) {
                entry.setValue(normalizeRange(entry.getValue(), true, "entity_scores.scores." + entry.getKey()));
            }
        }
        if (legacy && "minecraft:time_check".equals(type(condition)) && condition.has("value")) {
            condition.add("value", normalizeRange(condition.get("value"), true, "time_check.value"));
        }
        return condition;
    }

    private static void normalizeModifier(JsonObject owner) {
        if (owner.has("functions")) {
            rejectConflict(owner, "functions", "modifier");
            JsonArray functions = map(GsonHelper.getAsJsonArray(owner, "functions"), LootTableInjection::normalizeFunction);
            owner.remove("functions");
            if (!functions.isEmpty()) {
                owner.add("modifier", functions.size() == 1 ? functions.get(0) : functions);
            }
        } else if (owner.has("modifier")) {
            owner.add("modifier", normalizeFunction(owner.get("modifier")));
        }
    }

    private static JsonElement normalizeFunction(JsonElement value) {
        if (value.isJsonArray()) {
            return map(value.getAsJsonArray(), LootTableInjection::normalizeFunction);
        }
        if (value.isJsonPrimitive()) {
            return value;
        }
        JsonObject function = GsonHelper.convertToJsonObject(value, "loot modifier");
        boolean legacy = function.has("function");
        moveField(function, "function", "type");
        normalizeConditionField(function);
        switch (type(function)) {
            case "minecraft:set_nbt" -> function.addProperty("type", "minecraft:set_custom_data");
            case "minecraft:copy_nbt" -> function.addProperty("type", "minecraft:copy_custom_data");
            case "minecraft:reference" -> {
                JsonElement reference = new JsonPrimitive(GsonHelper.getAsString(function, "name"));
                if (!function.has("condition")) {
                    return reference;
                }
                JsonArray references = new JsonArray();
                references.add(reference);
                function.remove("name");
                function.addProperty("type", "minecraft:sequence");
                function.add("functions", references);
            }
            case "minecraft:filtered" -> moveField(function, "modifier", "on_pass");
            default -> { }
        }
        String functionType = type(function);
        if ("minecraft:sequence".equals(functionType) && function.has("functions")) {
            // Sequence owns this field; it is not a legacy modifier list on a loot entry.
            function.add("functions", map(GsonHelper.getAsJsonArray(function, "functions"), LootTableInjection::normalizeFunction));
        }
        List<String> nestedModifiers = switch (functionType) {
            case "minecraft:filtered" -> List.of("on_pass", "on_fail");
            case "minecraft:modify_contents" -> List.of("modifier");
            default -> List.of();
        };
        for (String field : nestedModifiers) {
            if (function.has(field)) {
                function.add(field, normalizeFunction(function.get(field)));
            }
        }
        if ("minecraft:set_contents".equals(type(function))) {
            normalizeEntries(function, "entries");
        }
        switch (functionType) {
            case "minecraft:set_count" -> normalizeNumberField(function, "count", true, legacy);
            case "minecraft:set_damage" -> normalizeNumberField(function, "damage", false, legacy);
            case "minecraft:enchant_with_levels" -> normalizeNumberField(function, "levels", true, legacy);
            case "minecraft:enchanted_count_increase" -> normalizeNumberField(function, "count", false, legacy);
            case "minecraft:set_ominous_bottle_amplifier" -> normalizeNumberField(function, "amplifier", true, legacy);
            case "minecraft:set_random_dyes" -> normalizeNumberField(function, "number_of_dyes", true, legacy);
            case "minecraft:set_attributes" -> normalizeNumberEntries(function, "modifiers", "amount", false, legacy);
            case "minecraft:set_stew_effect" -> normalizeNumberEntries(function, "effects", "duration", true, legacy);
            case "minecraft:set_enchantments" -> {
                if (function.has("enchantments")) {
                    for (var entry : GsonHelper.getAsJsonObject(function, "enchantments").entrySet()) {
                        entry.setValue(normalizeNumber(entry.getValue(), true, legacy, "set_enchantments." + entry.getKey()));
                    }
                }
            }
            case "minecraft:set_custom_model_data" -> {
                normalizeModelDataNumbers(function, "floats", false, legacy);
                normalizeModelDataNumbers(function, "colors", true, legacy);
            }
            case "minecraft:limit_count" -> {
                if (legacy && function.has("limit")) {
                    function.add("limit", normalizeRange(function.get("limit"), true, "limit_count.limit"));
                }
            }
            default -> { }
        }
        // Never walk tag/nbt/components: user custom data may legitimately contain these schema keys.
        return function;
    }

    private static void normalizeNumberField(JsonObject owner, String field, boolean integer, boolean legacy) {
        if (owner.has(field)) {
            owner.add(field, normalizeNumber(owner.get(field), integer, legacy, type(owner) + "." + field));
        }
    }

    private static JsonElement normalizeNumber(JsonElement value, boolean integer, boolean legacy, String path) {
        if (value.isJsonPrimitive() && !value.getAsJsonPrimitive().isNumber()) {
            return value;
        }
        String provider = value.isJsonObject() ? type(value.getAsJsonObject()) : "";
        JsonObject object = value.isJsonObject() ? value.getAsJsonObject() : null;
        if (object != null) {
            legacy |= (provider.isEmpty() && object.has("min") && object.has("max"))
                    || ("minecraft:score".equals(provider) && object.has("scale"))
                    || ("minecraft:sum".equals(provider) && object.has("summands"));
            if ("minecraft:score".equals(provider) && object.has("scale") && object.has("fallback")) {
                throw new JsonParseException("Conflicting legacy scale and native fallback at " + path);
            }
            boolean nativeType = !provider.isEmpty() && !isLegacyProvider(provider);
            boolean registeredNative = nativeType && (integer ? BuiltInRegistries.CONTEXT_INT_PROVIDER_TYPE
                    : BuiltInRegistries.CONTEXT_FLOAT_PROVIDER_TYPE).containsKey(Identifier.parse(provider));
            if (registeredNative || (nativeType && !(legacy && object.has("min") && object.has("max")))
                    || (("minecraft:score".equals(provider) || "minecraft:storage".equals(provider)) && object.has("fallback"))) {
                return value;
            }
        }
        if (legacy) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "tacz:legacy_number");
            wrapper.add("value", value);
            return wrapper;
        }
        if (value.isJsonPrimitive()) {
            if (!integer || !value.getAsJsonPrimitive().isNumber()) {
                return value;
            }
            try {
                value.getAsBigDecimal().intValueExact();
                return value;
            } catch (ArithmeticException exception) {
                throw new JsonParseException("Expected an exact native integer at " + path + "; ambiguous legacy fraction or overflow", exception);
            }
        }
        JsonObject number = GsonHelper.convertToJsonObject(value, "number provider at " + path);
        if ("minecraft:constant".equals(provider)) {
            if (integer && number.has("value")) {
                number.add("value", normalizeNumber(number.get("value"), true, legacy, path + ".value"));
            }
        } else if ("minecraft:uniform".equals(provider)) {
            for (String bound : List.of("min", "max")) {
                if (number.has(bound)) {
                    number.add(bound, normalizeNumber(number.get(bound), integer, legacy, path + "." + bound));
                }
            }
        }
        return number;
    }

    private static void normalizeNumberEntries(JsonObject owner, String list, String field, boolean integer, boolean legacy) {
        if (owner.has(list)) {
            for (JsonElement entry : GsonHelper.getAsJsonArray(owner, list)) {
                normalizeNumberField(GsonHelper.convertToJsonObject(entry, list + " entry"), field, integer, legacy);
            }
        }
    }

    private static void normalizeModelDataNumbers(JsonObject function, String field, boolean colors, boolean legacy) {
        if (!function.has(field)) {
            return;
        }
        JsonObject operation = GsonHelper.getAsJsonObject(function, field);
        if (operation.has("values")) {
            operation.add("values", map(GsonHelper.getAsJsonArray(operation, "values"), value -> {
                if (colors && value.isJsonArray()) {
                    if (!legacy) {
                        return value;
                    }
                    value = new JsonPrimitive(ExtraCodecs.RGB_COLOR_CODEC.parse(JsonOps.INSTANCE, value).getOrThrow(JsonParseException::new));
                }
                return normalizeNumber(value, colors, legacy, "set_custom_model_data." + field + ".values");
            }));
        }
    }

    private static JsonObject normalizeRange(JsonElement value, boolean legacy, String path) {
        JsonObject range;
        if (value.isJsonPrimitive()) {
            int point = Codec.INT.parse(JsonOps.INSTANCE, value).getOrThrow(JsonParseException::new);
            range = new JsonObject();
            range.addProperty("min", point);
            range.addProperty("max", point);
        } else {
            range = GsonHelper.convertToJsonObject(value, "integer range at " + path);
        }
        for (String bound : List.of("min", "max")) {
            if (range.has(bound)) {
                range.add(bound, normalizeNumber(range.get(bound), true, legacy, path + "." + bound));
            }
        }
        return range;
    }

    private static boolean isLegacyProvider(String type) {
        return switch (type) {
            case "minecraft:constant", "minecraft:uniform", "minecraft:binomial", "minecraft:score", "minecraft:storage",
                    "minecraft:sum", "minecraft:enchantment_level", "minecraft:environment_attribute" -> true;
            default -> false;
        };
    }

    private static JsonArray map(JsonArray values, UnaryOperator<JsonElement> normalize) {
        JsonArray result = new JsonArray();
        values.forEach(value -> result.add(normalize.apply(value)));
        return result;
    }

    private static String type(JsonObject object) {
        return object.has("type") ? Identifier.parse(GsonHelper.getAsString(object, "type")).toString() : "";
    }

    private static void moveField(JsonObject object, String oldField, String newField) {
        if (object.has(oldField)) {
            rejectConflict(object, oldField, newField);
            object.add(newField, object.remove(oldField));
        }
    }

    private static void rejectConflict(JsonObject object, String oldField, String newField) {
        if (object.has(newField)) {
            throw new JsonParseException("Conflicting loot fields '" + oldField + "' and '" + newField + "'");
        }
    }

    public List<ItemStack> createStacks(LootContext context) {
        List<ItemStack> stacks = new ArrayList<>();
        lootTable.getRandomItemsRaw(context, stacks::add);
        return stacks;
    }
}
