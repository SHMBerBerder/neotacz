package com.tacz.guns.resource;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.InclusiveRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GunPackResourcesTest {
    @TempDir Path directory;

    @Test
    void directoryAndZipPreserveFirstMatchNamespacesAndMetadata() throws Exception {
        Path first = directory.resolve("first");
        write(first.resolve("assets/first/models/shared.json"), "directory");
        write(first.resolve("data/first/recipes/shared.json"), "recipe");
        write(first.resolve("pack.png"), "pack-icon");
        Path zip = directory.resolve("second.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(output, "assets/first/models/shared.json", "zip-shadowed");
            entry(output, "assets/second/models/only.json", "zip-only");
        }
        var packs = List.of(new GunPackLoader.GunPack(first, "first"),
                new GunPackLoader.GunPack(zip, "second"));
        var location = new PackLocationInfo("test", Component.literal("Test"), PackSource.BUILT_IN, Optional.empty());
        var metadata = new PackMetadataSection(Component.literal("Test"),
                new InclusiveRange<>(PackFormat.of(84), PackFormat.of(121)));

        // Metadata probes and actual reloads open independent resources in 26.3.
        for (int reopen = 0; reopen < 2; reopen++) {
            try (var resources = new GunPackLoader.TaczPackResources(location, packs, metadata, null)) {
                assertEquals(location, resources.location());
                assertEquals(Set.of("first", "second"), resources.getNamespaces(PackType.CLIENT_RESOURCES));
                assertEquals("directory", read(resources.getResource(PackType.CLIENT_RESOURCES,
                        Identifier.parse("first:models/shared.json"))));
                assertEquals("zip-only", read(resources.getResource(PackType.CLIENT_RESOURCES,
                        Identifier.parse("second:models/only.json"))));
                assertEquals("recipe", read(resources.getResource(PackType.SERVER_DATA,
                        Identifier.parse("first:recipes/shared.json"))));
                assertNull(resources.getResource(PackType.CLIENT_RESOURCES, Identifier.parse("first:missing")));
                var listed = new HashMap<Identifier, IoSupplier<InputStream>>();
                resources.listResources(PackType.CLIENT_RESOURCES, "first", "models", listed::put);
                assertEquals(1, listed.size());
                assertEquals("directory", read(listed.get(Identifier.parse("first:models/shared.json"))));
                assertEquals("pack-icon", read(resources.getRootResource("pack.png")));
                assertSame(metadata, resources.getMetadataSection(PackMetadataSection.CLIENT_TYPE));
                assertSame(metadata, resources.getMetadataSection(PackMetadataSection.SERVER_TYPE));
                assertSame(metadata, resources.getMetadataSection(PackMetadataSection.FALLBACK_TYPE));
            }
        }
        try (var resources = new GunPackLoader.TaczPackResources(location, packs, metadata,
                () -> new ByteArrayInputStream("mod-icon".getBytes(StandardCharsets.UTF_8)))) {
            assertEquals("mod-icon", read(resources.getRootResource("pack.png")));
        }
    }

    @Test
    void bundledMetadataIncludesBothActual263PackFormats() throws Exception {
        try (var input = getClass().getResourceAsStream("/pack.mcmeta")) {
            assertNotNull(input);
            var pack = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().get("pack");
            for (PackType type : PackType.values()) {
                var range = PackFormat.packCodec(type).codec().parse(JsonOps.INSTANCE, pack).getOrThrow();
                var current = type == PackType.CLIENT_RESOURCES ? PackFormat.of(97, 1) : PackFormat.of(121, 0);
                assertTrue(range.isValueInRange(current), type + " must accept the actual target format");
            }
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static void entry(ZipOutputStream output, String path, String value) throws Exception {
        output.putNextEntry(new ZipEntry(path));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String read(IoSupplier<InputStream> supplier) throws Exception {
        assertNotNull(supplier);
        try (var input = supplier.get()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
