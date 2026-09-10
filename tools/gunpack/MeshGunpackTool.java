package com.tacz.guns.tools.gunpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.util.ResourceScanner;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Offline packaging only: never changes the default pack or the source assets. */
public final class MeshGunpackTool {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final String MANIFEST = "mesh-pack-manifest.json";
    static final String TEMPLATE = "gunpacks/bolt_action_mesh";
    static final String DEFAULT_PACK = "src/main/resources/assets/tacz/custom/tacz_default_gun";
    static final String MODEL_FILE = "neotacz_bolt_action_rifle_7_62_8k.gltf";
    static final long MAX_ENTRY = 128L << 20;
    static final long MAX_TOTAL = 512L << 20;
    static final long MAX_JSON = 32L << 20;

    @FunctionalInterface
    interface Opener { InputStream open(String path) throws IOException; }

    record Payload(Path file, byte[] bytes) {
        static Payload of(JsonElement json) { return new Payload(null, (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8)); }
        InputStream open() throws IOException { return file == null ? new ByteArrayInputStream(bytes) : Files.newInputStream(file); }
    }

    record Digest(long size, String sha256, long crc32) {
        JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("size", size);
            value.addProperty("sha256", sha256);
            value.addProperty("crc32", crc32);
            return value;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("build <repo> <zip> <adapted-directory> <baked-directory> [derived-model encoder-receipt] | validate <repo> <zip> [default-pack-directory]");
        Path root = Path.of(args[1]).toRealPath();
        Path zip = Path.of(args[2]).toAbsolutePath().normalize();
        if (args[0].equals("build") && (args.length == 5 || args.length == 7)) {
            build(root, zip, Path.of(args[3]).toRealPath(), Path.of(args[4]).toRealPath(),
                    args.length == 7 ? Path.of(args[5]).toRealPath() : null,
                    args.length == 7 ? Path.of(args[6]).toRealPath() : null);
        } else if (args[0].equals("validate") && (args.length == 3 || args.length == 4)) {
            validate(root, zip, args.length == 4 ? Path.of(args[3]).toRealPath() : root.resolve(DEFAULT_PACK));
        } else {
            throw new IllegalArgumentException("Invalid mesh gunpack arguments");
        }
    }

    static void build(Path root, Path output, Path source, Path baked) throws IOException {
        build(root, output, source, baked, null, null);
    }

    static void build(Path root, Path output, Path source, Path baked, Path derived, Path receiptPath) throws IOException {
        if ((derived == null) != (receiptPath == null)) throw new IOException("Derived model and encoder receipt must be supplied together");
        JsonObject template = read(root.resolve(TEMPLATE + "/pack.json"));
        String namespace = template.get("namespace").getAsString();
        checkNamespace(namespace);
        String gun = template.get("gun_id").getAsString();
        checkPath(gun);
        if (gun.contains("/")) throw new IOException("This template expects one flat gun ID");
        Path defaultPack = root.resolve(DEFAULT_PACK);
        String asset = "assets/" + namespace + "/";
        String data = "data/" + namespace + "/";
        String modelDirectory = asset + "models/gltf/" + gun + "/";
        TreeMap<String, Payload> entries = new TreeMap<>();

        JsonObject meta = new JsonObject();
        meta.addProperty("namespace", namespace);
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("tacz", template.get("mod_version").getAsString());
        meta.add("dependencies", dependencies);
        entries.put("gunpack.meta.json", Payload.of(meta));
        JsonObject index = read(defaultPack.resolve("data/tacz/index/guns/m95.json"));
        index.addProperty("name", namespace + ".gun." + gun + ".name");
        index.addProperty("tooltip", namespace + ".gun." + gun + ".desc");
        index.addProperty("display", namespace + ":" + gun);
        index.addProperty("data", namespace + ":" + gun);
        entries.put(data + "index/guns/" + gun + ".json", Payload.of(index));
        // Gameplay is deliberately inherited, not guessed from the mesh's real-world name.
        entries.put(data + "data/guns/" + gun + ".json", Payload.of(read(defaultPack.resolve("data/tacz/data/guns/m95_data.json"))));
        JsonObject profile = read(root.resolve("tools/blender/neotacz_bolt_action_8k/calibration-profile.json"));
        JsonObject inputs = profile.getAsJsonObject("inputs");
        for (var expected : template.getAsJsonObject("source_files").entrySet()) {
            Path file = safeFile(source, expected.getKey());
            Digest actual;
            try (InputStream stream = Files.newInputStream(file)) { actual = digest(stream, MAX_ENTRY); }
            if (!actual.sha256().equals(expected.getValue().getAsString())) throw new IOException("Original asset hash mismatch: " + file);
            if (derived == null) entries.put(modelDirectory + expected.getKey(), new Payload(file, null));
        }
        JsonObject receipt = derived == null ? null : read(receiptPath);
        if (receipt != null) MeshGunpackAssets.validateReceipt(receipt, template.getAsJsonObject("source_files"));
        String modelName = derived == null ? MODEL_FILE : MeshGunpackAssets.copyDerived(
                source, derived, modelDirectory, inputs.getAsJsonObject("node_map"), entries);
        JsonObject display = read(defaultPack.resolve("assets/tacz/display/guns/m95_display.json"));
        removeLegacyVisuals(display);
        display.addProperty("model", namespace + ":gun/" + gun);
        display.addProperty("animation", namespace + ":" + gun);
        JsonObject renderer = new JsonObject();
        renderer.addProperty("type", "gltf");
        renderer.addProperty("location", namespace + ":models/gltf/" + gun + "/" + modelName);
        renderer.add("scale", inputs.get("render_scale").deepCopy());
        renderer.add("node_map", inputs.get("node_map").deepCopy());
        display.add("render_model", renderer);
        entries.put(asset + "display/guns/" + gun + ".json", Payload.of(display));
        entries.put(asset + "geo_models/gun/" + gun + ".json", new Payload(safeFile(baked, "geometry.json"), null));
        JsonObject animation = read(safeFile(baked, "animation.json"));
        JsonObject remaps = template.getAsJsonObject("reference_remap");
        for (var remap : remaps.entrySet()) {
            int count = remapEffects(animation, remap.getKey(), remap.getValue().getAsString());
            if (count == 0) throw new IOException("Stale sound remap: " + remap.getKey());
        }
        entries.put(asset + "animations/" + gun + ".animation.json", Payload.of(animation));

        JsonObject info = new JsonObject();
        info.addProperty("version", template.get("version").getAsString());
        info.addProperty("name", namespace + ".pack.name");
        info.addProperty("desc", namespace + ".pack.desc");
        info.addProperty("license", "Mixed: Poly Haven CC0; TACZ-derived rig/animation/data CC BY-NC-ND 4.0; local private testing only");
        JsonArray authors = new JsonArray();
        authors.add("Mateusz Sadek / Poly Haven (mesh and images)");
        authors.add("TACZ Dev Team (game template and source rig/animation)");
        info.add("authors", authors);
        info.addProperty("date", "2026-09-06");
        info.addProperty("url", "https://polyhaven.com/a/bolt_action_rifle_7_62");
        entries.put(asset + "gunpack_info.json", Payload.of(info));
        entries.put("PROVENANCE.md", new Payload(root.resolve(TEMPLATE + "/PROVENANCE.md"), null));
        for (String language : new String[]{"en_us", "zh_cn"}) {
            JsonObject lang = new JsonObject();
            lang.addProperty(index.get("name").getAsString(), "Bolt Action Rifle 7.62 (" + (derived == null ? "8K" : "Mesh") + ")");
            lang.addProperty(index.get("tooltip").getAsString(), "Local mesh test; M95 game template, not real firearm specifications.");
            lang.addProperty(namespace + ".pack.name", "Bolt Action Mesh");
            lang.addProperty(namespace + ".pack.desc", "Independent mesh add-on. Requires the NeoTaCZ default gunpack.");
            entries.put(asset + "lang/" + language + ".json", Payload.of(lang));
        }
        Opener opener = path -> {
            Payload payload = entries.get(path);
            if (payload == null) throw new IOException("Missing pack file: " + path);
            return payload.open();
        };
        MeshGunpackReferences refs = new MeshGunpackReferences(root, defaultPack, namespace, opener);
        refs.check(gun);
        if (derived == null) validateGltf(opener, modelDirectory + modelName, inputs.getAsJsonObject("node_map"));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema_version", derived == null ? 1 : 2);
        if (receipt != null) {
            manifest.addProperty("asset_mode", "derived");
            manifest.add("derivation", receipt);
        }
        manifest.addProperty("namespace", namespace);
        manifest.addProperty("gun_id", gun);
        manifest.add("reference_remap", remaps.deepCopy());
        manifest.add("shared_resources", refs.sharedJson());
        manifest.add("source_files", template.get("source_files").deepCopy());
        JsonObject calibrationReport = read(safeFile(baked, "report.json"));
        if (!calibrationReport.has("status") || !calibrationReport.get("status").getAsString().equals("PASS")) throw new IOException("Calibration report must pass before packaging");
        manifest.add("calibration_report", calibrationReport);
        JsonObject bakeInputs = new JsonObject();
        for (String name : new String[]{"geometry.json", "animation.json", "report.json"}) {
            try (InputStream stream = Files.newInputStream(safeFile(baked, name))) { bakeInputs.add(name, digest(stream, MAX_JSON).json()); }
        }
        manifest.add("calibration_inputs", bakeInputs);
        JsonObject files = new JsonObject();
        for (var entry : entries.entrySet()) {
            try (InputStream stream = entry.getValue().open()) { files.add(entry.getKey(), digest(stream, MAX_ENTRY).json()); }
        }
        manifest.add("files", files);
        if (derived != null) {
            JsonObject assetFiles = new JsonObject();
            for (var entry : files.entrySet()) {
                if (entry.getKey().startsWith(modelDirectory)) assetFiles.add(
                        entry.getKey().substring(modelDirectory.length()), entry.getValue().getAsJsonObject().get("sha256").deepCopy());
            }
            manifest.add("asset_files", assetFiles);
        }
        entries.put(MANIFEST, Payload.of(manifest));
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "mesh-pack-", ".zip.tmp");
        try {
            writeZip(temporary, entries);
            validate(root, temporary, defaultPack);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("Built independent gunpack: " + output + " (" + Files.size(output) + " bytes)");
    }

    static void removeLegacyVisuals(JsonObject display) {
        for (String field : new String[]{"lod", "slot", "hud", "hud_empty"}) display.remove(field);
    }

    static int remapEffects(JsonElement element, String from, String to) {
        return remapEffects(element, from, to, false);
    }

    private static int remapEffects(JsonElement element, String from, String to, boolean soundEffects) {
        int count = 0;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (var entry : new ArrayList<>(object.entrySet())) {
                if (soundEffects && entry.getKey().equals("effect") && entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isString()
                        && entry.getValue().getAsString().equals(from)) {
                    object.addProperty(entry.getKey(), to);
                    count++;
                } else count += remapEffects(entry.getValue(), from, to,
                        soundEffects || entry.getKey().equals("sound_effects"));
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) count += remapEffects(child, from, to, soundEffects);
        }
        return count;
    }

    static void validate(Path root, Path file, Path defaultPack) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Set<String> names = new TreeSet<>();
            long total = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                checkPath(entry.getName());
                if (entry.isDirectory() || !names.add(entry.getName())) throw new IOException("Duplicate/directory ZIP entry: " + entry.getName());
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY || (total += entry.getSize()) > MAX_TOTAL) throw new IOException("ZIP size limit exceeded");
            }
            Opener opener = path -> {
                checkPath(path);
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) throw new IOException("Missing pack file: " + path);
                return zip.getInputStream(entry);
            };
            JsonObject meta = read(opener, "gunpack.meta.json");
            String namespace = meta.get("namespace").getAsString();
            checkNamespace(namespace);
            JsonObject template = read(root.resolve(TEMPLATE + "/pack.json"));
            if (!template.get("namespace").equals(meta.get("namespace"))) throw new IOException("Namespace does not match the trusted template");
            if (!meta.has("dependencies") || !meta.get("dependencies").isJsonObject()
                    || !template.get("mod_version").equals(meta.getAsJsonObject("dependencies").get("tacz"))) throw new IOException("TACZ mod dependency must match the trusted template");
            for (String name : names) checkPackPath(namespace, name);
            JsonObject manifest = read(opener, MANIFEST);
            int version = manifest.get("schema_version").getAsInt();
            if ((version != 1 && version != 2) || !manifest.get("namespace").getAsString().equals(namespace)) throw new IOException("Manifest version/namespace mismatch");
            if (!template.get("gun_id").equals(manifest.get("gun_id"))) throw new IOException("Gun ID does not match the trusted template");
            if (!template.get("source_files").equals(manifest.get("source_files"))) throw new IOException("Source hashes do not match the trusted template");
            if (!template.get("reference_remap").equals(manifest.get("reference_remap"))) throw new IOException("Sound remaps do not match the trusted template");
            Set<String> declared = new TreeSet<>(manifest.getAsJsonObject("files").keySet());
            Set<String> expectedNames = new TreeSet<>(names);
            expectedNames.remove(MANIFEST);
            if (!declared.equals(expectedNames)) throw new IOException("Manifest must cover every file except itself exactly once");
            for (String name : declared) {
                Digest actual;
                try (InputStream stream = opener.open(name)) { actual = digest(stream, MAX_ENTRY); }
                JsonObject expected = manifest.getAsJsonObject("files").getAsJsonObject(name);
                ZipEntry entry = zip.getEntry(name);
                if (!actual.json().equals(expected) || actual.crc32() != entry.getCrc() || actual.size() != entry.getSize()) throw new IOException("ZIP hash/CRC/size mismatch: " + name);
            }
            String gun = manifest.get("gun_id").getAsString();
            checkPath(gun);
            MeshGunpackReferences refs = new MeshGunpackReferences(root, defaultPack, namespace, opener);
            refs.check(gun);
            if (!refs.sharedJson().equals(manifest.getAsJsonArray("shared_resources"))) throw new IOException("Declared shared resources do not match the complete checked reference set");
            String displayPath = "assets/" + namespace + "/display/guns/" + gun + ".json";
            JsonObject display = read(opener, displayPath);
            JsonObject renderer = display.getAsJsonObject("render_model");
            String modelPath = MeshGunpackReferences.resource(renderer.get("location").getAsString(), "assets", "", "");
            String directory = modelPath.substring(0, modelPath.lastIndexOf('/') + 1);
            if (version == 1) {
                validateGltf(opener, modelPath, renderer.getAsJsonObject("node_map"));
                for (var source : manifest.getAsJsonObject("source_files").entrySet()) {
                    String path = directory + source.getKey();
                    JsonObject stat = manifest.getAsJsonObject("files").getAsJsonObject(path);
                    if (stat == null || !stat.get("sha256").getAsString().equals(source.getValue().getAsString())) throw new IOException("Source file was changed or omitted: " + path);
                }
            } else {
                if (!manifest.has("asset_mode") || !manifest.get("asset_mode").getAsString().equals("derived")) throw new IOException("Manifest v2 requires derived asset_mode");
                MeshGunpackAssets.validateReceipt(manifest.getAsJsonObject("derivation"), template.getAsJsonObject("source_files"));
                Set<String> closure = MeshGunpackAssets.validate(opener, modelPath, renderer.getAsJsonObject("node_map"));
                MeshGunpackAssets.verifyAssetFiles(manifest.getAsJsonObject("asset_files"),
                        manifest.getAsJsonObject("files"), directory, closure, names);
            }
            if (!names.contains("PROVENANCE.md")) throw new IOException("Missing mixed-license provenance");
            System.out.println("Validated " + namespace + ":" + gun + ": " + names.size() + " files, " + refs.sharedJson().size() + " shared resources, " + total + " bytes");
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid mesh gunpack structure: " + exception.getMessage(), exception);
        }
    }

    static void validateGltf(Opener opener, String modelPath, JsonObject nodeMap) throws IOException {
        JsonObject gltf = read(opener, modelPath);
        String directory = modelPath.substring(0, modelPath.lastIndexOf('/') + 1);
        ArrayList<String> nodes = new ArrayList<>();
        for (JsonElement node : gltf.getAsJsonArray("nodes")) {
            JsonElement name = node.getAsJsonObject().get("name");
            nodes.add(name == null || name.isJsonNull() ? null : name.getAsString());
        }
        MeshGunpackAssets.checkNodeMappings(nodes, nodeMap);
        for (JsonElement buffer : gltf.getAsJsonArray("buffers")) {
            JsonObject value = buffer.getAsJsonObject();
            String uri = value.get("uri").getAsString();
            checkPath(uri);
            try (InputStream stream = opener.open(directory + uri)) {
                if (digest(stream, MAX_ENTRY).size() != value.get("byteLength").getAsLong()) throw new IOException("glTF buffer size mismatch: " + uri);
            }
        }
        JsonArray images = gltf.getAsJsonArray("images");
        if (images.size() != 6) throw new IOException("Original 8K pack requires six JPEG images");
        for (JsonElement image : images) {
            String uri = image.getAsJsonObject().get("uri").getAsString();
            checkPath(uri);
            if (!uri.endsWith(".jpg")) throw new IOException("Original image must remain JPEG: " + uri);
            try (InputStream stream = opener.open(directory + uri);
                 MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(stream)) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Invalid JPEG: " + uri);
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    if (!reader.getFormatName().equalsIgnoreCase("JPEG") || reader.getWidth(0) != 8192 || reader.getHeight(0) != 8192) throw new IOException("Expected original 8192 x 8192 JPEG: " + uri);
                } finally { reader.dispose(); }
            }
        }
    }

    static void writeZip(Path path, Map<String, Payload> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                checkPath(entry.getKey());
                Digest stat;
                try (InputStream in = entry.getValue().open()) { stat = digest(in, MAX_ENTRY); }
                ZipEntry member = new ZipEntry(entry.getKey());
                member.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                // Original JPEGs are already compressed. STORED avoids another encoding and is reproducible.
                member.setMethod(ZipEntry.STORED);
                member.setSize(stat.size());
                member.setCompressedSize(stat.size());
                member.setCrc(stat.crc32());
                out.putNextEntry(member);
                try (InputStream in = entry.getValue().open()) { in.transferTo(out); }
                out.closeEntry();
            }
        }
    }

    static JsonObject read(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) { return read(stream); }
    }

    static JsonObject read(Opener opener, String path) throws IOException {
        try (InputStream stream = opener.open(path)) { return read(stream); }
    }

    static JsonObject read(InputStream stream) throws IOException {
        byte[] data = stream.readNBytes((int) MAX_JSON + 1);
        if (data.length > MAX_JSON) throw new IOException("JSON size limit exceeded");
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            JsonElement value = ResourceScanner.parseLenientJsonElement(GSON, reader);
            if (value == null || !value.isJsonObject()) throw new IOException("Expected JSON object");
            return value.getAsJsonObject();
        }
    }

    static Digest digest(InputStream stream, long limit) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[65536];
            long size = 0;
            for (int length; (length = stream.read(buffer)) != -1;) {
                if ((size += length) > limit) throw new IOException("File exceeds size limit");
                sha.update(buffer, 0, length);
                crc.update(buffer, 0, length);
            }
            return new Digest(size, HexFormat.of().formatHex(sha.digest()), crc.getValue());
        } catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }

    static Path safeFile(Path directory, String name) throws IOException {
        checkPath(name);
        Path root = directory.toRealPath();
        Path file = root.resolve(name).toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) == 0) throw new IOException("Missing/escaping input: " + name);
        return file;
    }

    static void checkNamespace(String namespace) throws IOException {
        if (!namespace.matches("[a-z0-9_][a-z0-9_.-]*") || namespace.equals("tacz") || namespace.equals("minecraft")) throw new IOException("Expected a private lowercase namespace, not a default namespace: " + namespace);
    }

    static void checkPath(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.contains("%") || name.contains("?") || name.contains("#") || !name.matches("[A-Za-z0-9_./-]+")) throw new IOException("Unsafe relative path: " + name);
        for (String part : name.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe relative path: " + name);
    }

    static void checkPackPath(String namespace, String name) throws IOException {
        checkPath(name);
        if (!(name.equals("gunpack.meta.json") || name.equals(MANIFEST) || name.equals("PROVENANCE.md")
                || name.startsWith("assets/" + namespace + "/") || name.startsWith("data/" + namespace + "/"))) throw new IOException("Foreign namespace/default override is forbidden: " + name);
    }
}
