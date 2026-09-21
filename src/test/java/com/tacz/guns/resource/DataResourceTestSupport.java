package com.tacz.guns.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.tacz.guns.testsupport.MinecraftTestEnvironment;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class DataResourceTestSupport {
    private DataResourceTestSupport() {
    }

    static void bootstrap() {
        MinecraftTestEnvironment.bootstrap();
    }

    static JsonObject json(String path) throws Exception {
        try (var stream = openResource(path)) {
            assertNotNull(stream, "Missing data resource: " + path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    static boolean hasResource(String path) {
        try (var stream = openResource(path)) {
            return stream != null;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to inspect data resource: " + path, exception);
        }
    }

    private static java.io.InputStream openResource(String path) {
        String absolutePath = "/data/" + path;
        var override = DataResourceTestSupport.class.getResourceAsStream(absolutePath);
        return override != null ? override : SharedConstants.class.getResourceAsStream(absolutePath);
    }

    static RegistryOps<JsonElement> recipeOps() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        return RegistryOps.create(JsonOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
            @Override
            public <T> Optional<HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                return registries.lookup(key).map(parent -> {
                    if (!key.equals(Registries.ITEM)) {
                        return parent;
                    }
                    // Keep real tag IDs through codec round trips without mutating global registries.
                    // These unbound holders verify schema, not live datapack tag membership.
                    return new HolderGetter<T>() {
                        @Override
                        public Optional<Holder.Reference<T>> get(ResourceKey<T> id) {
                            return parent.get(id);
                        }

                        @Override
                        public Optional<HolderSet.Named<T>> get(TagKey<T> id) {
                            return Optional.of(HolderSet.emptyNamed(parent, id));
                        }

                        @Override
                        public boolean canSerialize(net.minecraft.core.HolderOwner<T> owner) {
                            return parent.canSerialize(owner);
                        }
                    };
                });
            }
        });
    }
}
