package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Small offline checks; the full original-8K ZIP build is a separate integration gate. */
public final class MeshGunpackSelfTest {
    @FunctionalInterface interface Check { void run() throws Exception; }
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        Path base = root.resolve("build/gunpack-tool");
        Files.createDirectories(base);
        Path temporary = Files.createTempDirectory(base, "selftest-");
        try {
            paths(temporary);
            zip(root, temporary);
            references(root, temporary);
            MeshGunpackAssetsTest.run(root, temporary);
            System.out.println("Mesh gunpack self-test: " + checks + " checks passed");
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void paths(Path directory) throws Exception {
        MeshGunpackTool.checkNamespace("bolt_action_mesh");
        checks++;
        for (String value : new String[]{"tacz", "minecraft", "Bolt", "../escape", "", "..", "foo/bar", "a:b"}) {
            rejects(() -> MeshGunpackTool.checkNamespace(value), "namespace");
        }
        for (String value : new String[]{"/root", "../a", "a/../b", "a//b", "a/./b", "a\\b", "https:x", "%2e%2e/a", "a?x", "a#x", "a/"}) {
            rejects(() -> MeshGunpackTool.checkPath(value), "relative path");
        }
        for (String value : new String[]{"assets/tacz/display/guns/m95_display.json", "data/tacz/index/guns/m95.json", "wrapped/gunpack.meta.json"}) {
            rejects(() -> MeshGunpackTool.checkPackPath("bolt_action_mesh", value), "forbidden");
        }
        for (String value : new String[]{"tacz:../x", "TACZ:x", "tacz:/x", "tacz:x%2fy", "tacz:x:y"}) {
            rejects(() -> MeshGunpackReferences.resource(value, "assets", "", ""), null);
        }
        Path nested = directory.resolve("source");
        Files.createDirectories(nested);
        Files.writeString(directory.resolve("outside"), "outside");
        Files.createSymbolicLink(nested.resolve("escape"), directory.resolve("outside"));
        rejects(() -> MeshGunpackTool.safeFile(nested, "escape"), "escaping");
        JsonObject input = object("{\"sound_effects\":{\"0\":{\"effect\":\"old:sound\"}},\"particle_effects\":{\"0\":{\"effect\":\"old:sound\"}},\"effect\":\"old:sound\",\"rotation\":[1,2,3],\"other\":\"old:sound\"}");
        JsonObject before = input.deepCopy();
        int count = MeshGunpackTool.remapEffects(input, "old:sound", "new:sound");
        truth(count == 1 && input.get("rotation").equals(before.get("rotation"))
                && input.get("other").equals(before.get("other")), "Sound remapping must not rewrite transforms/other strings");
        truth(input.get("effect").equals(before.get("effect"))
                && input.get("particle_effects").equals(before.get("particle_effects")), "Sound remapping must not rewrite particle or unrelated effects");
        truth(input.getAsJsonObject("sound_effects").getAsJsonObject("0").get("effect").getAsString().equals("new:sound"), "Declared sound effect must be remapped");
        JsonObject outside = object("{\"clips\":[{\"particle_effects\":{\"0\":{\"effect\":\"old:sound\"}}},{\"effect\":\"old:sound\"}]}");
        JsonObject unchanged = outside.deepCopy();
        truth(MeshGunpackTool.remapEffects(outside, "old:sound", "new:sound") == 0 && outside.equals(unchanged), "No sound_effects subtree must mean no remap");
        JsonObject jsonc = MeshGunpackTool.read(new ByteArrayInputStream("{ // retained project JSONC reader\n\"value\":2}".getBytes(StandardCharsets.UTF_8)));
        truth(jsonc.get("value").getAsInt() == 2, "JSONC reader");
    }

    private static void zip(Path root, Path directory) throws Exception {
        TreeMap<String, MeshGunpackTool.Payload> entries = new TreeMap<>();
        JsonObject template = MeshGunpackTool.read(root.resolve(MeshGunpackTool.TEMPLATE + "/pack.json"));
        JsonObject meta = object("{\"namespace\":\"bolt_action_mesh\",\"dependencies\":{}}");
        meta.getAsJsonObject("dependencies").add("tacz", template.get("mod_version").deepCopy());
        entries.put("gunpack.meta.json", MeshGunpackTool.Payload.of(meta));
        entries.put("PROVENANCE.md", bytes("known provenance"));
        entries.put("assets/bolt_action_mesh/a.bin", new MeshGunpackTool.Payload(null, new byte[]{0, 1, 2, 3, -1}));
        JsonObject manifest = manifest(root, entries);
        entries.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(manifest));
        Path first = directory.resolve("first.zip"), second = directory.resolve("second.zip");
        MeshGunpackTool.writeZip(first, entries);
        MeshGunpackTool.writeZip(second, entries);
        truth(Arrays.equals(Files.readAllBytes(first), Files.readAllBytes(second)), "Reproducible ZIP bytes");
        try (ZipFile zip = new ZipFile(first.toFile())) {
            for (var entry : entries.entrySet()) {
                try (var original = entry.getValue().open(); var packed = zip.getInputStream(zip.getEntry(entry.getKey()))) {
                    MeshGunpackTool.Digest before = MeshGunpackTool.digest(original, 1024 * 1024);
                    MeshGunpackTool.Digest after = MeshGunpackTool.digest(packed, 1024 * 1024);
                    truth(before.equals(after) && after.crc32() == zip.getEntry(entry.getKey()).getCrc(), "ZIP byte/CRC identity " + entry.getKey());
                }
            }
        }
        Path defaults = root.resolve(MeshGunpackTool.DEFAULT_PACK);
        rejects(() -> MeshGunpackTool.validate(root, first, defaults), "Missing pack file");
        for (JsonObject invalid : new JsonObject[]{
                object("{\"namespace\":\"bolt_action_mesh\"}"),
                object("{\"namespace\":\"bolt_action_mesh\",\"dependencies\":{}}"),
                object("{\"namespace\":\"bolt_action_mesh\",\"dependencies\":null}"),
                object("{\"namespace\":\"bolt_action_mesh\",\"dependencies\":{\"tacz\":\"[0,)\"}}"),
                object("{\"namespace\":\"bolt_action_mesh\",\"dependencies\":{\"tacz\":1}}")}) {
            entries.put("gunpack.meta.json", MeshGunpackTool.Payload.of(invalid));
            MeshGunpackTool.writeZip(second, entries);
            rejects(() -> MeshGunpackTool.validate(root, second, defaults), "mod dependency");
        }
        JsonObject foreign = meta.deepCopy();
        foreign.addProperty("namespace", "foreign_pack");
        entries.put("gunpack.meta.json", MeshGunpackTool.Payload.of(foreign));
        MeshGunpackTool.writeZip(second, entries);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "Namespace does not match");
        entries.put("gunpack.meta.json", MeshGunpackTool.Payload.of(meta));
        JsonObject wrongGun = manifest.deepCopy();
        wrongGun.addProperty("gun_id", "different_gun");
        entries.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(wrongGun));
        MeshGunpackTool.writeZip(second, entries);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "Gun ID does not match");
        entries.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(manifest));
        entries.put("assets/bolt_action_mesh/a.bin", bytes("changed"));
        MeshGunpackTool.writeZip(second, entries);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "hash/CRC/size mismatch");
        entries.remove("assets/bolt_action_mesh/a.bin");
        MeshGunpackTool.writeZip(second, entries);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "cover every file");
        entries.put("assets/tacz/override.json", bytes("{}"));
        MeshGunpackTool.writeZip(second, entries);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "forbidden");
        MeshGunpackTool.writeZip(second, Map.of("wrapper/gunpack.meta.json", entries.get("gunpack.meta.json")));
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "Missing pack file");

        // ZIP writers forbid duplicates, so mutate equal-length names in both headers of a tiny fixture.
        MeshGunpackTool.writeZip(second, Map.of("a.bin", bytes("a"), "b.bin", bytes("b")));
        byte[] duplicate = Files.readAllBytes(second);
        byte[] name = "b.bin".getBytes(StandardCharsets.US_ASCII);
        int changes = 0;
        for (int i = 0; i <= duplicate.length - name.length; i++) {
            if (Arrays.equals(duplicate, i, i + name.length, name, 0, name.length)) { duplicate[i] = 'a'; changes++; }
        }
        truth(changes == 2, "Local and central duplicate headers");
        Files.write(second, duplicate);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "Duplicate");
        MeshGunpackTool.writeZip(second, Map.of("aa/a.bin", bytes("data")));
        byte[] traversal = Files.readAllBytes(second);
        name = "aa/a.bin".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= traversal.length - name.length; i++) {
            if (Arrays.equals(traversal, i, i + name.length, name, 0, name.length)) { traversal[i] = '.'; traversal[i + 1] = '.'; }
        }
        Files.write(second, traversal);
        rejects(() -> MeshGunpackTool.validate(root, second, defaults), "relative path");
    }

    private static void references(Path root, Path directory) throws Exception {
        String ns = "fixture", gun = "rifle", assets = "assets/fixture/";
        Path defaults = root.resolve(MeshGunpackTool.DEFAULT_PACK);
        TreeMap<String, MeshGunpackTool.Payload> files = new TreeMap<>();
        files.put("data/fixture/index/guns/rifle.json", MeshGunpackTool.Payload.of(object("{\"display\":\"fixture:rifle\",\"data\":\"fixture:rifle\",\"name\":\"name\",\"tooltip\":\"desc\"}")));
        files.put("data/fixture/data/guns/rifle.json", MeshGunpackTool.Payload.of(object("{\"ammo\":\"tacz:50bmg\"}")));
        JsonObject display = MeshGunpackTool.read(defaults.resolve("assets/tacz/display/guns/m95_display.json"));
        display.addProperty("model", "fixture:gun/rifle");
        display.addProperty("animation", "fixture:rifle");
        display.add("render_model", object("{\"type\":\"gltf\",\"location\":\"fixture:models/gltf/rifle/model.gltf\",\"scale\":1,\"node_map\":{\"m95\":\"GunVisual\"}}"));
        JsonObject originalDisplay = display.deepCopy();
        MeshGunpackTool.removeLegacyVisuals(display);
        for (String field : new String[]{"lod", "slot", "hud", "hud_empty"}) {
            truth(!display.has(field), "Mesh display must not retain legacy " + field);
            originalDisplay.remove(field);
        }
        truth(display.equals(originalDisplay), "Removing legacy visuals must preserve rig, texture, animation, sounds and mapping");
        files.put(assets + "display/guns/rifle.json", MeshGunpackTool.Payload.of(display));
        files.put(assets + "geo_models/gun/rifle.json", new MeshGunpackTool.Payload(defaults.resolve("assets/tacz/geo_models/gun/m95_geo.json"), null));
        JsonObject animation = MeshGunpackTool.read(defaults.resolve("assets/tacz/animations/m95.animation.json"));
        MeshGunpackTool.remapEffects(animation, "tacz:m95/mag_drup_large_drum_dirt", "tacz:mag_drop_sound/mag_drup_large_drum_dirt");
        files.put(assets + "animations/rifle.animation.json", MeshGunpackTool.Payload.of(animation));
        files.put(assets + "models/gltf/rifle/model.gltf", bytes("{}"));
        files.put(assets + "gunpack_info.json", MeshGunpackTool.Payload.of(object("{\"name\":\"pack\",\"desc\":\"description\"}")));
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            files.put(assets + "lang/" + locale + ".json", MeshGunpackTool.Payload.of(object("{\"name\":\"Name\",\"desc\":\"Description\",\"pack\":\"Pack\",\"description\":\"Description\"}")));
        }
        MeshGunpackTool.Opener opener = path -> {
            if (!files.containsKey(path)) throw new IOException("Missing own reference " + path);
            return files.get(path).open();
        };
        MeshGunpackReferences references = new MeshGunpackReferences(root, defaults, ns, opener);
        references.check(gun);
        truth(references.sharedJson().size() > 40, "Transitive shared resources including animation effects");
        for (String path : files.keySet().toArray(String[]::new)) {
            var payload = files.remove(path);
            try { rejects(() -> new MeshGunpackReferences(root, defaults, ns, opener).check(gun), "Missing own reference"); }
            finally { files.put(path, payload); }
        }
        Path copiedDefault = directory.resolve("default"), copiedRoot = directory.resolve("repo");
        for (var entry : references.sharedJson()) {
            JsonObject ref = entry.getAsJsonObject();
            String path = ref.get("path").getAsString();
            boolean mod = ref.get("provider").getAsString().equals("mod");
            Path source = (mod ? root.resolve("src/main/resources") : defaults).resolve(path);
            Path target = (mod ? copiedRoot.resolve("src/main/resources") : copiedDefault).resolve(path);
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        new MeshGunpackReferences(copiedRoot, copiedDefault, ns, opener).check(gun);
        checks++;
        for (var entry : references.sharedJson()) {
            JsonObject ref = entry.getAsJsonObject();
            String path = ref.get("path").getAsString();
            Path target = (ref.get("provider").getAsString().equals("mod") ? copiedRoot.resolve("src/main/resources") : copiedDefault).resolve(path);
            Path missing = target.resolveSibling(target.getFileName() + ".missing");
            Files.move(target, missing);
            try { rejects(() -> new MeshGunpackReferences(copiedRoot, copiedDefault, ns, opener).check(gun), null); }
            finally { Files.move(missing, target, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    private static JsonObject manifest(Path root, Map<String, MeshGunpackTool.Payload> entries) throws Exception {
        JsonObject template = MeshGunpackTool.read(root.resolve(MeshGunpackTool.TEMPLATE + "/pack.json"));
        JsonObject manifest = object("{\"schema_version\":1,\"namespace\":\"bolt_action_mesh\",\"gun_id\":\"bolt_action_rifle_762\"}");
        manifest.add("source_files", template.get("source_files").deepCopy());
        manifest.add("reference_remap", template.get("reference_remap").deepCopy());
        manifest.add("shared_resources", new JsonArray());
        JsonObject hashes = new JsonObject();
        for (var entry : entries.entrySet()) {
            try (var stream = entry.getValue().open()) { hashes.add(entry.getKey(), MeshGunpackTool.digest(stream, 1024 * 1024).json()); }
        }
        manifest.add("files", hashes);
        return manifest;
    }

    static JsonObject object(String json) { return MeshGunpackTool.GSON.fromJson(json, JsonObject.class); }
    static MeshGunpackTool.Payload bytes(String text) { return new MeshGunpackTool.Payload(null, text.getBytes(StandardCharsets.UTF_8)); }
    static void truth(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    static void rejects(Check action, String message) throws Exception {
        try { action.run(); }
        catch (IOException exception) {
            if (message != null && !exception.getMessage().contains(message)) throw new AssertionError("Wrong rejection: " + exception.getMessage(), exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }
}
