package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.client.model.gltf.loader.GltfResourcePolicy;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Copies an already encoded model's local dependency closure, never runs an encoder. */
final class MeshGunpackAssets {
    private static final Set<String> RECEIPT_FIELDS = Set.of("tool", "version", "options", "source_files");

    static String copyDerived(Path source, Path model, String directory, JsonObject nodeMap,
                              Map<String, MeshGunpackTool.Payload> entries) throws IOException {
        Path sourceRoot = source.toRealPath();
        Path input = model.toRealPath();
        if (!input.startsWith(sourceRoot) || !Files.isRegularFile(input)) throw new IOException("Derived model must be inside the source directory");
        String name = input.getFileName().toString();
        MeshGunpackTool.checkPath(name);
        Path inputDirectory = input.getParent();
        MeshGunpackTool.Opener opener = path -> {
            if (!path.startsWith(directory)) throw new IOException("Derived reference escapes the model directory");
            return Files.newInputStream(MeshGunpackTool.safeFile(inputDirectory, path.substring(directory.length())));
        };
        Set<String> closure = validate(opener, directory + name, nodeMap);
        for (String path : closure) {
            Path file = MeshGunpackTool.safeFile(inputDirectory, path.substring(directory.length()));
            entries.put(path, new MeshGunpackTool.Payload(file, null));
        }
        return name;
    }

    static Set<String> validate(MeshGunpackTool.Opener opener, String modelPath, JsonObject nodeMap) throws IOException {
        try {
            MeshGunpackTool.checkPath(modelPath);
            String[] parts = modelPath.split("/", 3);
            if (parts.length != 3 || !parts[0].equals("assets")) throw new IOException("Expected a bundled model resource");
            Identifier modelId = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
            GltfResourcePolicy.requireMainModelId(modelId);
            Map<String, byte[]> loaded = new TreeMap<>();
            long[] total = {0};
            byte[] main = load(opener, modelPath, loaded, total);
            var normalized = new JgltfModelLoader().load(new ByteArrayInputStream(main), uri -> {
                MeshGunpackTool.checkPath(uri);
                Identifier target = GltfResourcePolicy.resolveResourceId(modelId, uri);
                String path = "assets/" + target.getNamespace() + "/" + target.getPath();
                return ByteBuffer.wrap(load(opener, path, loaded, total));
            });
            List<String> names = new ArrayList<>();
            if (normalized.gltf().getNodes() != null) {
                for (var node : normalized.gltf().getNodes()) names.add(node.getName());
            }
            checkNodeMappings(names, nodeMap);
            return new TreeSet<>(loaded.keySet());
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new IOException("Invalid derived glTF: " + exception.getMessage(), exception);
        }
    }

    private static byte[] load(MeshGunpackTool.Opener opener, String path, Map<String, byte[]> loaded, long[] total) throws IOException {
        byte[] existing = loaded.get(path);
        if (existing != null) return existing;
        byte[] bytes;
        try (InputStream input = opener.open(path)) {
            bytes = GltfResourcePolicy.readAllBytesLimited(input, MeshGunpackTool.MAX_ENTRY);
        }
        if (bytes.length == 0 || (total[0] += bytes.length) > MeshGunpackTool.MAX_TOTAL) throw new IOException("Empty/oversized derived model resource set");
        loaded.put(path, bytes);
        return bytes;
    }

    static void checkNodeMappings(Iterable<String> names, JsonObject nodeMap) throws IOException {
        if (nodeMap == null) throw new IOException("Missing mesh node map");
        Map<String, Integer> counts = new TreeMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) counts.merge(name, 1, Integer::sum);
        }
        for (var mapping : nodeMap.entrySet()) {
            if (mapping.getKey().equals("lefthand_pos") || mapping.getKey().equals("righthand_pos")) throw new IOException("Hand anchors cannot be rigid mesh targets");
            String target = mapping.getValue().getAsString();
            int count = counts.getOrDefault(target, 0);
            if (count == 0) throw new IOException("Missing mapped node: " + target);
            if (count != 1) throw new IOException("Ambiguous mapped node: " + target);
        }
    }

    static void validateReceipt(JsonObject receipt, JsonObject sources) throws IOException {
        if (receipt == null || !receipt.keySet().equals(RECEIPT_FIELDS)) throw new IOException("Encoder receipt requires exactly tool, version, options, source_files");
        for (String field : new String[]{"tool", "version"}) {
            JsonElement value = receipt.get(field);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || !value.getAsString().matches("[A-Za-z0-9_.+-]{1,100}")) throw new IOException("Invalid encoder receipt " + field);
        }
        if (!sources.equals(receipt.get("source_files"))) throw new IOException("Encoder receipt source hashes must match the trusted template");
        if (!receipt.get("options").isJsonObject()) throw new IOException("Encoder receipt options must be a structured object");
        checkOptions(receipt.get("options"), 0);
    }

    private static void checkOptions(JsonElement value, int depth) throws IOException {
        if (depth > 8 || value == null || value.isJsonNull()) throw new IOException("Invalid encoder receipt options");
        if (value.isJsonObject()) {
            for (var option : value.getAsJsonObject().entrySet()) {
                String key = option.getKey().toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_-]{1,80}") || key.matches(".*(password|secret|token|credential|authorization|api.?key).*")) throw new IOException("Unsafe encoder receipt option key");
                checkOptions(option.getValue(), depth + 1);
            }
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) checkOptions(item, depth + 1);
        } else {
            var primitive = value.getAsJsonPrimitive();
            // Receipts record encoding settings, not executable commands or machine-local paths.
            if (primitive.isString() && !primitive.getAsString().matches("[A-Za-z0-9_.+-]{1,100}")) throw new IOException("Encoder receipt strings must be path-free option values");
            if (primitive.isNumber() && !Double.isFinite(primitive.getAsDouble())) throw new IOException("Non-finite encoder receipt option");
        }
    }

    static void verifyAssetFiles(JsonObject assets, JsonObject files, String directory,
                                 Set<String> closure, Set<String> allEntries) throws IOException {
        if (assets == null) throw new IOException("Missing derived asset_files closure");
        Set<String> declared = new TreeSet<>();
        for (var entry : assets.entrySet()) {
            MeshGunpackTool.checkPath(entry.getKey());
            String path = directory + entry.getKey();
            declared.add(path);
            JsonObject stat = files.getAsJsonObject(path);
            if (stat == null || !entry.getValue().equals(stat.get("sha256"))) throw new IOException("Derived asset hash mismatch: " + path);
        }
        if (!declared.equals(closure)) throw new IOException("asset_files must match the complete model dependency closure");
        for (String path : allEntries) {
            if (path.startsWith(directory) && !closure.contains(path)) throw new IOException("Derived payload contains an unreferenced model file: " + path);
        }
    }
}
