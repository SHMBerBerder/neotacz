package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.TreeMap;

import static com.tacz.guns.tools.gunpack.MeshGunpackSelfTest.*;

/** Container and provenance checks; real compressed decoder fixtures belong to runtime tests. */
final class MeshGunpackAssetsTest {
    private static final String DIRECTORY = "assets/fixture/models/gltf/rifle/";
    private static final JsonObject NODE_MAP = object("{\"m95\":\"GunVisual\"}");
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==");

    static void run(Path root, Path temporary) throws Exception {
        receipt();
        closure(temporary);
        completeZip(root, temporary);
    }

    private static void completeZip(Path root, Path temporary) throws Exception {
        JsonObject template = MeshGunpackTool.read(root.resolve(MeshGunpackTool.TEMPLATE + "/pack.json"));
        String namespace = template.get("namespace").getAsString(), gun = template.get("gun_id").getAsString();
        String asset = "assets/" + namespace + "/", data = "data/" + namespace + "/";
        String modelDirectory = asset + "models/gltf/" + gun + "/", modelPath = modelDirectory + "derived.glb";
        Path defaults = root.resolve(MeshGunpackTool.DEFAULT_PACK);
        TreeMap<String, MeshGunpackTool.Payload> files = new TreeMap<>();
        JsonObject meta = new JsonObject(), dependencies = new JsonObject();
        meta.addProperty("namespace", namespace);
        dependencies.add("tacz", template.get("mod_version").deepCopy());
        meta.add("dependencies", dependencies);
        files.put("gunpack.meta.json", MeshGunpackTool.Payload.of(meta));
        files.put("PROVENANCE.md", bytes("Container test fixture, not a distributable gunpack"));
        JsonObject index = object("{\"name\":\"name\",\"tooltip\":\"desc\"}");
        index.addProperty("display", namespace + ":" + gun);
        index.addProperty("data", namespace + ":" + gun);
        files.put(data + "index/guns/" + gun + ".json", MeshGunpackTool.Payload.of(index));
        files.put(data + "data/guns/" + gun + ".json", MeshGunpackTool.Payload.of(object("{\"ammo\":\"tacz:50bmg\"}")));
        JsonObject display = MeshGunpackTool.read(defaults.resolve("assets/tacz/display/guns/m95_display.json"));
        MeshGunpackTool.removeLegacyVisuals(display);
        display.addProperty("model", namespace + ":gun/" + gun);
        display.addProperty("animation", namespace + ":" + gun);
        JsonObject renderer = object("{\"type\":\"gltf\",\"scale\":1}");
        renderer.addProperty("location", namespace + ":models/gltf/" + gun + "/derived.glb");
        renderer.add("node_map", NODE_MAP.deepCopy());
        display.add("render_model", renderer);
        files.put(asset + "display/guns/" + gun + ".json", MeshGunpackTool.Payload.of(display));
        files.put(asset + "geo_models/gun/" + gun + ".json", new MeshGunpackTool.Payload(defaults.resolve("assets/tacz/geo_models/gun/m95_geo.json"), null));
        JsonObject animation = MeshGunpackTool.read(defaults.resolve("assets/tacz/animations/m95.animation.json"));
        for (var remap : template.getAsJsonObject("reference_remap").entrySet()) {
            MeshGunpackTool.remapEffects(animation, remap.getKey(), remap.getValue().getAsString());
        }
        files.put(asset + "animations/" + gun + ".animation.json", MeshGunpackTool.Payload.of(animation));
        files.put(asset + "gunpack_info.json", MeshGunpackTool.Payload.of(object("{\"name\":\"pack\",\"desc\":\"description\"}")));
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            files.put(asset + "lang/" + locale + ".json", MeshGunpackTool.Payload.of(object("{\"name\":\"Name\",\"desc\":\"Description\",\"pack\":\"Pack\",\"description\":\"Description\"}")));
        }
        JsonObject model = object("{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"name\":\"GunVisual\"}],\"buffers\":[{\"byteLength\":4}]}");
        files.put(modelPath, new MeshGunpackTool.Payload(null, glb(model, new byte[4])));
        JsonObject receipt = object("{\"tool\":\"test-fixture\",\"version\":\"1\",\"options\":{}}");
        receipt.add("source_files", template.get("source_files").deepCopy());
        JsonObject manifest = object("{\"schema_version\":2,\"asset_mode\":\"derived\"}");
        manifest.addProperty("namespace", namespace);
        manifest.addProperty("gun_id", gun);
        manifest.add("source_files", template.get("source_files").deepCopy());
        manifest.add("reference_remap", template.get("reference_remap").deepCopy());
        manifest.add("derivation", receipt);
        MeshGunpackReferences refs = new MeshGunpackReferences(root, defaults, namespace, name -> {
            if (!files.containsKey(name)) throw new IOException("Missing fixture " + name);
            return files.get(name).open();
        });
        refs.check(gun);
        manifest.add("shared_resources", refs.sharedJson());
        JsonObject hashes = new JsonObject();
        for (var file : files.entrySet()) {
            try (var input = file.getValue().open()) { hashes.add(file.getKey(), MeshGunpackTool.digest(input, MeshGunpackTool.MAX_ENTRY).json()); }
        }
        manifest.add("files", hashes);
        JsonObject assets = new JsonObject();
        assets.add("derived.glb", hashes.getAsJsonObject(modelPath).get("sha256").deepCopy());
        manifest.add("asset_files", assets);
        files.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(manifest));
        Path zip = temporary.resolve("complete-derived.zip"), again = temporary.resolve("complete-derived-again.zip");
        MeshGunpackTool.writeZip(zip, files);
        MeshGunpackTool.validate(root, zip, defaults);
        truth(true, "Complete v2 GLB ZIP validates without original runtime images or legacy gun visuals");
        MeshGunpackTool.writeZip(again, files);
        truth(Files.mismatch(zip, again) == -1, "Derived ZIP is reproducible");
        for (String field : new String[]{"asset_mode", "derivation", "asset_files"}) {
            JsonObject broken = manifest.deepCopy();
            broken.remove(field);
            files.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(broken));
            MeshGunpackTool.writeZip(again, files);
            rejects(() -> MeshGunpackTool.validate(root, again, defaults), null);
        }
        JsonObject wrong = manifest.deepCopy();
        wrong.getAsJsonObject("asset_files").addProperty("derived.glb", "ff".repeat(32));
        files.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(wrong));
        MeshGunpackTool.writeZip(again, files);
        rejects(() -> MeshGunpackTool.validate(root, again, defaults), "Derived asset hash");
        files.put(MeshGunpackTool.MANIFEST, MeshGunpackTool.Payload.of(manifest));
        files.put(modelPath, bytes("changed mesh payload"));
        MeshGunpackTool.writeZip(again, files);
        rejects(() -> MeshGunpackTool.validate(root, again, defaults), "ZIP hash/CRC/size");
    }

    private static void receipt() throws Exception {
        JsonObject sources = object("{\"original.gltf\":\"" + "ab".repeat(32) + "\"}");
        JsonObject receipt = object("{\"tool\":\"gltf-transform\",\"version\":\"4.5.0\",\"options\":{\"draco\":{\"method\":\"edgebreaker\",\"quantizePosition\":14},\"uastc\":true}}");
        receipt.add("source_files", sources.deepCopy());
        MeshGunpackAssets.validateReceipt(receipt, sources);
        truth(true, "Structured encoder receipt");
        for (String field : new String[]{"tool", "version", "options", "source_files"}) {
            JsonObject missing = receipt.deepCopy();
            missing.remove(field);
            rejects(() -> MeshGunpackAssets.validateReceipt(missing, sources), "receipt");
        }
        JsonObject changed = receipt.deepCopy();
        changed.getAsJsonObject("source_files").addProperty("original.gltf", "ff".repeat(32));
        rejects(() -> MeshGunpackAssets.validateReceipt(changed, sources), "source");
        for (String json : new String[]{"{\"input\":\"/Users/private/model.glb\"}", "{\"path\":\"C:\\\\private\\\\model.glb\"}",
                "{\"url\":\"https://example.invalid/x\"}", "{\"token\":\"private\"}", "{\"command\":\"encode --secret hidden\"}"}) {
            JsonObject unsafe = receipt.deepCopy();
            unsafe.add("options", object(json));
            rejects(() -> MeshGunpackAssets.validateReceipt(unsafe, sources), "receipt");
        }
        JsonObject extra = receipt.deepCopy();
        extra.addProperty("source_directory", "/private");
        rejects(() -> MeshGunpackAssets.validateReceipt(extra, sources), "receipt");
    }

    private static void closure(Path temporary) throws Exception {
        JsonObject gltf = object("{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{}, {\"name\":\"GunVisual\"}],\"scenes\":[{\"nodes\":[0,1]}],\"scene\":0,\"buffers\":[{\"uri\":\"mesh.bin\",\"byteLength\":4}],\"images\":[{\"uri\":\"textures/pixel.png\"}]}");
        TreeMap<String, MeshGunpackTool.Payload> files = new TreeMap<>();
        files.put(DIRECTORY + "model.gltf", MeshGunpackTool.Payload.of(gltf));
        files.put(DIRECTORY + "mesh.bin", new MeshGunpackTool.Payload(null, new byte[4]));
        files.put(DIRECTORY + "textures/pixel.png", new MeshGunpackTool.Payload(null, PNG));
        files.put(DIRECTORY + "unrelated.bin", bytes("must not be copied"));
        MeshGunpackTool.Opener opener = name -> {
            if (!files.containsKey(name)) throw new IOException("Missing fixture " + name);
            return files.get(name).open();
        };
        Set<String> closure = MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", NODE_MAP);
        truth(closure.equals(Set.of(DIRECTORY + "model.gltf", DIRECTORY + "mesh.bin", DIRECTORY + "textures/pixel.png")), "Only model dependency closure is selected; unnamed unmapped nodes are valid");
        JsonObject hashes = new JsonObject(), entries = new JsonObject();
        for (String name : closure) {
            try (var input = opener.open(name)) {
                var digest = MeshGunpackTool.digest(input, 1024 * 1024);
                hashes.addProperty(name.substring(DIRECTORY.length()), digest.sha256());
                entries.add(name, digest.json());
            }
        }
        MeshGunpackAssets.verifyAssetFiles(hashes, entries, DIRECTORY, closure, closure);
        truth(true, "Actual payload hashes cover the loaded closure");
        JsonObject wrongHash = hashes.deepCopy();
        wrongHash.addProperty("mesh.bin", "ff".repeat(32));
        rejects(() -> MeshGunpackAssets.verifyAssetFiles(wrongHash, entries, DIRECTORY, closure, closure), "hash");
        JsonObject missing = hashes.deepCopy();
        missing.remove("mesh.bin");
        rejects(() -> MeshGunpackAssets.verifyAssetFiles(missing, entries, DIRECTORY, closure, closure), "closure");
        rejects(() -> MeshGunpackAssets.verifyAssetFiles(hashes, entries, DIRECTORY, closure, files.keySet()), "unreferenced");
        files.remove(DIRECTORY + "mesh.bin");
        rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", NODE_MAP), "Missing");
        files.put(DIRECTORY + "mesh.bin", new MeshGunpackTool.Payload(null, new byte[4]));
        for (String uri : new String[]{"../outside.bin", "https://example.invalid/mesh.bin", "/tmp/mesh.bin", "%2e%2e/mesh.bin", "mesh.bin?x"}) {
            JsonObject unsafe = gltf.deepCopy();
            unsafe.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri", uri);
            files.put(DIRECTORY + "model.gltf", MeshGunpackTool.Payload.of(unsafe));
            rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", NODE_MAP), null);
        }
        JsonObject duplicate = gltf.deepCopy();
        duplicate.getAsJsonArray("nodes").get(0).getAsJsonObject().addProperty("name", "GunVisual");
        files.put(DIRECTORY + "model.gltf", MeshGunpackTool.Payload.of(duplicate));
        rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", NODE_MAP), "Ambiguous mapped");
        files.put(DIRECTORY + "model.gltf", MeshGunpackTool.Payload.of(gltf));
        rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", object("{\"m95\":\"Missing\"}")), "Missing mapped");
        rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", object("{\"lefthand_pos\":\"GunVisual\"}")), "Hand anchors");
        rejects(() -> MeshGunpackTool.validateGltf(opener, DIRECTORY + "model.gltf", NODE_MAP), "six JPEG");

        JsonObject embedded = object("{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"name\":\"GunVisual\"}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0,\"buffers\":[{\"byteLength\":" + PNG.length + "}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + PNG.length + "}],\"images\":[{\"bufferView\":0,\"mimeType\":\"image/png\"}]}");
        files.put(DIRECTORY + "model.glb", new MeshGunpackTool.Payload(null, glb(embedded, PNG)));
        truth(MeshGunpackAssets.validate(opener, DIRECTORY + "model.glb", NODE_MAP).equals(Set.of(DIRECTORY + "model.glb")), "GLB embedded buffer/image needs only one payload file");
        files.put(DIRECTORY + "broken.glb", bytes("not a GLB"));
        rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "broken.glb", NODE_MAP), null);
        JsonObject required = gltf.deepCopy();
        required.add("extensionsRequired", MeshGunpackTool.GSON.toJsonTree(new String[]{"UNSUPPORTED_required"}));
        files.put(DIRECTORY + "model.gltf", MeshGunpackTool.Payload.of(required));
        rejects(() -> MeshGunpackAssets.validate(opener, DIRECTORY + "model.gltf", NODE_MAP), "Unsupported required");

        Path source = Files.createDirectory(temporary.resolve("derived-source"));
        Path derived = Files.createDirectory(source.resolve("encoded"));
        Files.write(derived.resolve("model.glb"), glb(embedded, PNG));
        Files.writeString(derived.resolve("unrelated.txt"), "not copied");
        TreeMap<String, MeshGunpackTool.Payload> copied = new TreeMap<>();
        String model = MeshGunpackAssets.copyDerived(source, derived.resolve("model.glb"), DIRECTORY, NODE_MAP, copied);
        truth(model.equals("model.glb") && copied.keySet().equals(Set.of(DIRECTORY + "model.glb")), "Derived source containment and no directory scanning");
        Path outside = temporary.resolve("outside.glb");
        Files.write(outside, glb(embedded, PNG));
        rejects(() -> MeshGunpackAssets.copyDerived(source, outside, DIRECTORY, NODE_MAP, copied), "source directory");
        Files.createSymbolicLink(derived.resolve("escape.glb"), outside);
        rejects(() -> MeshGunpackAssets.copyDerived(source, derived.resolve("escape.glb"), DIRECTORY, NODE_MAP, copied), "source directory");
    }

    private static byte[] glb(JsonObject model, byte[] binary) {
        byte[] json = MeshGunpackTool.GSON.toJson(model).getBytes(StandardCharsets.UTF_8);
        int jsonLength = (json.length + 3) & ~3, binaryLength = (binary.length + 3) & ~3;
        ByteBuffer out = ByteBuffer.allocate(28 + jsonLength + binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity());
        out.putInt(jsonLength).putInt(0x4e4f534a).put(json);
        while (out.position() < 20 + jsonLength) out.put((byte) 0x20);
        out.putInt(binaryLength).putInt(0x004e4942).put(binary);
        return out.array();
    }
}
