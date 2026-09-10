package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeMap;

import static com.tacz.guns.tools.gunpack.MeshGunpackTool.*;
import static com.tacz.guns.tools.gunpack.PublicMeshPackTool.*;

/** Actual-source, executable checks. No game or native renderer is launched. */
public final class PublicMeshPackTest {
    private static int checks;
    @FunctionalInterface interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        Path directory = root.resolve("build/public-mesh-pack/selftest");
        Files.createDirectories(directory);
        TreeMap<String, Payload> entries = entries(root);
        Path first = directory.resolve("first.zip"), second = directory.resolve("second.zip");
        writeZip(first, entries);
        writeZip(second, entries);
        validate(root, first, false);
        require(Files.mismatch(first, second) == -1, "Reproducible ZIP bytes");
        JsonObject report;
        try (var input = entries.get(REPORT).open()) { report = read(input); }
        require(report.getAsJsonArray("models").size() == 5, "Three guns and two separate attachments");
        for (var item : report.getAsJsonArray("models")) {
            JsonObject model = item.getAsJsonObject();
            require(model.get("accessors_and_buffer_views_unchanged").getAsBoolean(), "No dequantization or vertex rewrite");
            if (model.has("installed_anchors")) for (var anchor : model.getAsJsonObject("installed_anchors").asMap().values()) require(anchor.getAsJsonObject().get("numeric_residual_metres").getAsDouble() < 1e-12, "Matching attachment origin");
            String modelPath = MeshGunpackReferences.resource(model.get("location").getAsString(), "assets", "", "");
            JsonObject serializedModel;
            try (var input = entries.get(modelPath).open()) {
                serializedModel = modelPath.endsWith(".glb") ? readGlb(input.readAllBytes()).json() : read(input);
            }
            for (var scene : serializedModel.getAsJsonArray("scenes")) {
                for (var index : scene.getAsJsonObject().getAsJsonArray("nodes")) {
                    require(index.isJsonPrimitive() && index.getAsJsonPrimitive().isNumber()
                            && index.toString().equals(Integer.toString(index.getAsInt())), "Serialized scene node index is an integer");
                }
            }
        }
        JsonObject template = read(root.resolve(TEMPLATE_DIR + "/pack.json"));
        for (var item : template.getAsJsonArray("guns")) {
            JsonObject spec = item.getAsJsonObject();
            String id = spec.get("id").getAsString();
            JsonObject rig, data;
            try (var input = entries.get(ASSETS + "geo_models/gun/" + id + ".json").open()) { rig = read(input); }
            try (var input = entries.get(DATA + "data/guns/" + id + ".json").open()) { data = read(input); }
            JsonArray bones = rig.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
            JsonArray allowed = data.getAsJsonArray("allow_attachment_types");
            PublicMeshPackReferences.checkRefitViews(bones, allowed);
            JsonObject sourceIndex = read(root.resolve(DEFAULT_PACK + "/data/tacz/index/guns/" + spec.get("template").getAsString() + ".json"));
            JsonObject sourceDisplay = read(root.resolve(DEFAULT_PACK).resolve(MeshGunpackReferences.resource(sourceIndex.get("display").getAsString(), "assets", "display/guns/", ".json")));
            JsonObject sourceRig = read(root.resolve(DEFAULT_PACK).resolve(MeshGunpackReferences.resource(sourceDisplay.get("model").getAsString(), "assets", "geo_models/", ".json")));
            JsonArray sourceBones = sourceRig.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
            for (var type : allowed) {
                String name = "refit_" + type.getAsString() + "_view";
                JsonObject expected = bone(sourceBones, name);
                if (expected == null) expected = bone(sourceBones, "refit_view");
                expected = expected.deepCopy();
                expected.remove("cubes");
                expected.addProperty("name", name);
                require(expected.equals(bone(bones, name)), "Existing refit view preserved or overview copied completely");
                for (JsonObject parent = expected; parent.has("parent");) {
                    parent = bone(sourceBones, parent.get("parent").getAsString()).deepCopy();
                    parent.remove("cubes");
                    require(parent.equals(bone(bones, parent.get("name").getAsString())), "Refit ancestor transforms preserve the original matrix chain");
                }
            }
        }
        checkRefitViewGeneration();
        for (String source : new String[]{"32989.glb", "32981.glb", "33034.glb", "33029.glb"}) {
            Model original = readGlb(Files.readAllBytes(root.resolve("build/public-mesh-assets/" + source)));
            Model roundtrip = readGlb(writeGlb(original));
            require(original.json().equals(roundtrip.json()), "GLB JSON roundtrip");
            require(Arrays.equals(original.binaryChunks(), roundtrip.binaryChunks()), "GLB BIN byte identity");
            require(original.json().getAsJsonArray("extensionsRequired").toString().contains("KHR_mesh_quantization"), "Original quantization remains required");
        }
        reject(() -> readGlb(new byte[12]), "Truncated GLB");
        reject(() -> checkPath("../escape"), "Traversal");
        reject(() -> anchorResidual(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{0, 0, 0}), "Misplaced attachment anchor");
        TreeMap<String, Payload> bad = new TreeMap<>(entries);
        bad.remove(DATA + "index/guns/service_pistol.json");
        rejectZip(root, directory, bad, "Missing index");
        bad = new TreeMap<>(entries);
        bad.put("assets/tacz/override.json", Payload.of(new JsonObject()));
        rejectZip(root, directory, bad, "Default namespace overwrite");
        bad = new TreeMap<>(entries);
        JsonObject attachment;
        String path = ASSETS + "display/attachments/vertical_grip.json";
        try (var input = bad.get(path).open()) { attachment = read(input); }
        attachment.getAsJsonObject("render_model").add("node_map", new JsonObject());
        bad.put(path, Payload.of(attachment));
        rejectZip(root, directory, bad, "Attachment node map tampering");
        bad = new TreeMap<>(entries);
        bad.put(DATA + "tacz_tags/attachments/grip.json", Payload.of(strings("tacz:grip_vertical_talon")));
        rejectZip(root, directory, bad, "Foreign compatibility tag");
        System.out.println("Public mesh checks passed: " + checks);
    }

    private static void checkRefitViewGeneration() throws Exception {
        JsonArray bones = GSON.fromJson("""
                [{"name":"views","pivot":[3,4,0],"rotation":[2,1,0]},
                 {"name":"refit_view","parent":"views","pivot":[30,5,0],"rotation":[0,89.9,0],"locators":{"marker":[1,2,3]}},
                 {"name":"refit_muzzle_view","parent":"views","pivot":[12,8,-30],"rotation":[0,120,0]}]
                """, JsonArray.class);
        JsonObject existing = bone(bones, "refit_muzzle_view").deepCopy();
        JsonArray allowed = strings("grip", "muzzle");
        require(ensureRefitViews(bones, allowed).equals(strings("refit_grip_view")), "Only missing refit views are generated");
        JsonObject expected = bone(bones, "refit_view").deepCopy();
        expected.addProperty("name", "refit_grip_view");
        require(expected.equals(bone(bones, "refit_grip_view")), "Overview transform and all metadata are copied");
        require(existing.equals(bone(bones, "refit_muzzle_view")), "Existing specialized view is unchanged");
        require(ensureRefitViews(bones, allowed).isEmpty(), "Refit view generation is idempotent");
        bone(bones, "refit_view").getAsJsonArray("pivot").set(0, new com.google.gson.JsonPrimitive(999));
        require(expected.equals(bone(bones, "refit_grip_view")), "Generated view is a deep copy");
        JsonArray missing = bones.deepCopy();
        missing.remove(bone(missing, "refit_grip_view"));
        reject(() -> PublicMeshPackReferences.checkRefitViews(missing, allowed), "Missing allowed refit view");
        JsonArray orphan = bones.deepCopy();
        bone(orphan, "refit_grip_view").addProperty("parent", "absent");
        reject(() -> PublicMeshPackReferences.checkRefitViews(orphan, allowed), "Missing refit parent");
        JsonArray cycle = bones.deepCopy();
        bone(cycle, "views").addProperty("parent", "refit_grip_view");
        reject(() -> PublicMeshPackReferences.checkRefitViews(cycle, allowed), "Cyclic refit parent chain");
        JsonArray cubes = bones.deepCopy();
        bone(cubes, "views").add("cubes", new JsonArray());
        reject(() -> PublicMeshPackReferences.checkRefitViews(cubes, allowed), "Cubes in refit parent chain");
        JsonArray invalid = bones.deepCopy();
        bone(invalid, "refit_grip_view").add("rotation", numbers(0, 1e100, 0));
        reject(() -> PublicMeshPackReferences.checkRefitViews(invalid, allowed), "Non-finite runtime refit rotation");
        JsonArray noOverview = missing.deepCopy();
        noOverview.remove(bone(noOverview, "refit_view"));
        reject(() -> ensureRefitViews(noOverview, allowed), "Missing overview cannot become an identity view");
    }

    private static JsonObject bone(JsonArray bones, String name) { for (var value : bones) if (name.equals(value.getAsJsonObject().get("name").getAsString())) return value.getAsJsonObject(); return null; }
    private static void rejectZip(Path root, Path dir, TreeMap<String, Payload> entries, String name) throws Exception { Path file = dir.resolve("invalid.zip"); writeZip(file, entries); reject(() -> validate(root, file, false), name); Files.delete(file); }
    private static void require(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    private static void reject(Check check, String name) throws Exception { checks++; try { check.run(); } catch (IOException | IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection: " + name); }
}
