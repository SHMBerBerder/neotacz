package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import static com.tacz.guns.tools.gunpack.MeshGunpackTool.*;

/** A bounded showcase builder, not another runtime pack loader or calibration system. */
public final class PublicMeshPackTool {
    static final String NS = "public_mesh_showcase";
    static final String TEMPLATE_DIR = "gunpacks/" + NS;
    static final String ASSETS = "assets/" + NS + "/";
    static final String DATA = "data/" + NS + "/";
    static final String REPORT = "public-mesh-manifest.json";
    static final String OUTPUT = "build/public-mesh-pack/" + NS + "-1.0.0-local.zip";
    static final String MIXED_LICENSE = "Mixed CC0 visual sources and TACZ CC BY-NC-ND 4.0 derivatives; local private validation only, not for publication";

    record Model(JsonObject json, byte[] binaryChunks) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("build|validate|validate-runtime <repo> [zip]");
        Path root = Path.of(args[1]).toRealPath();
        Path output = args.length == 3 ? Path.of(args[2]).toAbsolutePath().normalize() : root.resolve(OUTPUT);
        switch (args[0]) {
            case "build" -> build(root, output);
            case "validate", "validate-runtime" -> validate(root, output, args[0].equals("validate-runtime"));
            default -> throw new IllegalArgumentException("Unknown public mesh command");
        }
    }

    static void build(Path root, Path output) throws Exception {
        if (!output.normalize().startsWith(root.resolve("build/public-mesh-pack"))) throw new IOException("Output must remain in build/public-mesh-pack");
        TreeMap<String, Payload> entries = entries(root);
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "public-mesh-", ".zip.tmp");
        try {
            writeZip(temporary, entries);
            validate(root, temporary, false);
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        try (InputStream in = Files.newInputStream(output)) {
            System.out.println("Built " + output + " " + digest(in, MAX_TOTAL).json());
        }
    }

    static TreeMap<String, Payload> entries(Path root) throws Exception {
        JsonObject template = read(root.resolve(TEMPLATE_DIR + "/pack.json"));
        if (!NS.equals(template.get("namespace").getAsString())) throw new IOException("Unexpected showcase namespace");
        Path source = root.resolve("build/public-mesh-assets");
        for (var entry : template.getAsJsonObject("source_files").entrySet()) {
            try (InputStream in = Files.newInputStream(safeFile(source, entry.getKey()))) {
                if (!digest(in, MAX_ENTRY).sha256().equals(entry.getValue().getAsString())) throw new IOException("Pinned source SHA mismatch: " + entry.getKey());
            }
        }
        verifyPolyHavenMd5(source);
        TreeMap<String, Payload> entries = new TreeMap<>();
        JsonObject meta = new JsonObject();
        meta.addProperty("namespace", NS);
        JsonObject dependencies = new JsonObject();
        dependencies.add("tacz", template.get("mod_version").deepCopy());
        meta.add("dependencies", dependencies);
        entries.put("gunpack.meta.json", Payload.of(meta));
        JsonObject info = new JsonObject();
        info.add("version", template.get("version").deepCopy());
        info.addProperty("name", NS + ".pack.name");
        info.addProperty("desc", NS + ".pack.desc");
        info.addProperty("license", MIXED_LICENSE);
        info.add("authors", strings("3D Assets (AI-generated CC0 meshes)", "Mateusz Sadek / Poly Haven", "TACZ Dev Team (game templates)"));
        info.addProperty("date", "2026-09-10");
        info.addProperty("url", "https://3dassets.dev/packs/mega-weapon-pack");
        entries.put(ASSETS + "gunpack_info.json", Payload.of(info));
        entries.put("PROVENANCE.md", new Payload(root.resolve(TEMPLATE_DIR + "/PROVENANCE.md"), null));
        JsonObject language = new JsonObject();
        language.addProperty(NS + ".pack.name", "Public Mesh Showcase");
        language.addProperty(NS + ".pack.desc", "Local visual compatibility test; requires the default gunpack.");
        JsonArray models = new JsonArray();
        JsonObject sourceCatalog = read(source.resolve("mega-weapon-pack.json")).getAsJsonObject("data");
        for (String category : new String[]{"guns", "attachments"}) {
            for (JsonElement item : template.getAsJsonArray(category)) {
                JsonObject spec = item.getAsJsonObject();
                JsonObject proof = addModel(entries, source, spec, category.equals("attachments"));
                if (spec.has("source_id")) {
                    JsonObject author = findById(sourceCatalog.getAsJsonArray("assets"), spec.get("source_id").getAsString());
                    for (String key : new String[]{"url", "cdnUrl", "license", "contributor", "aiGenerated", "aiModel", "description"}) proof.add(key, author.get(key).deepCopy());
                } else {
                    for (String key : new String[]{"source_url", "author", "license"}) proof.add(key, spec.get(key).deepCopy());
                    proof.addProperty("polyhaven_md5_verified", true);
                }
                if (category.equals("guns")) addGun(entries, root, spec, proof);
                else addAttachment(entries, root, spec);
                String key = NS + "." + category + "." + spec.get("id").getAsString();
                language.add(key + ".name", spec.get("name").deepCopy());
                language.addProperty(key + ".desc", "Local mesh test; inherited game values, not real firearm specifications.");
                models.add(proof);
            }
        }
        entries.put(ASSETS + "lang/en_us.json", Payload.of(language));
        entries.put(ASSETS + "lang/zh_cn.json", Payload.of(language));
        for (String type : new String[]{"grip", "muzzle"}) {
            JsonArray ids = new JsonArray();
            for (JsonElement element : template.getAsJsonArray("attachments")) {
                JsonObject item = element.getAsJsonObject();
                if (type.equals(item.get("type").getAsString())) ids.add(NS + ":" + item.get("id").getAsString());
            }
            entries.put(DATA + "tacz_tags/attachments/" + type + ".json", Payload.of(ids));
        }
        PublicMeshPackReferences refs = new PublicMeshPackReferences(root, entries);
        refs.check(template);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema_version", 1);
        manifest.addProperty("namespace", NS);
        manifest.addProperty("license", MIXED_LICENSE);
        manifest.add("source_files", template.get("source_files").deepCopy());
        manifest.add("models", models);
        manifest.add("shared_resources", refs.shared());
        manifest.addProperty("coordinate_contract", "gun=B*(C*p+d); attachment=B*C*(p-mount); B=diag(-1,-1,1); BedrockPivot=root+16*(-g.x,g.y,g.z); metres; scale=1; no legacy attachment 24/16 offset");
        manifest.addProperty("action_binding", "Body follows Bedrock; embedded source clips retained but not selected. No claimed hand/contact or mechanical-motion calibration.");
        JsonObject files = new JsonObject();
        for (var entry : entries.entrySet()) try (InputStream in = entry.getValue().open()) { files.add(entry.getKey(), digest(in, MAX_ENTRY).json()); }
        manifest.add("files", files);
        entries.put(REPORT, Payload.of(manifest));
        return entries;
    }

    private static JsonObject addModel(Map<String, Payload> entries, Path source, JsonObject spec, boolean attachment) throws Exception {
        String id = spec.get("id").getAsString();
        checkPath(id);
        Path input = safeFile(source, spec.get("source").getAsString());
        boolean glb = input.toString().endsWith(".glb");
        Model original = glb ? readGlb(Files.readAllBytes(input)) : new Model(read(input), new byte[0]);
        JsonObject json = original.json().deepCopy();
        JsonArray nodes = json.getAsJsonArray("nodes");
        if (spec.has("selected_nodes")) {
            JsonArray selected = new JsonArray();
            JsonArray meshes = new JsonArray();
            for (JsonElement name : spec.getAsJsonArray("selected_nodes")) {
                JsonObject node = findByName(nodes, name.getAsString()).deepCopy();
                if (node.has("children") || node.has("skin")) throw new IOException("Selection expects the flat unskinned source variant");
                meshes.add(json.getAsJsonArray("meshes").get(node.get("mesh").getAsInt()).deepCopy());
                node.addProperty("mesh", meshes.size() - 1);
                JsonObject overrides = spec.getAsJsonObject("node_translation_overrides");
                if (overrides.has(name.getAsString())) node.add("translation", overrides.get(name.getAsString()).deepCopy());
                selected.add(node);
            }
            json.add("nodes", nodes = selected);
            json.add("meshes", meshes);
            JsonObject scene = new JsonObject();
            JsonArray roots = new JsonArray();
            for (int i = 0; i < nodes.size(); i++) roots.add(i);
            scene.add("nodes", roots);
            JsonArray scenes = new JsonArray();
            scenes.add(scene);
            json.add("scenes", scenes);
            json.addProperty("scene", 0);
        }
        int sceneIndex = json.has("scene") ? json.get("scene").getAsInt() : 0;
        JsonObject scene = json.getAsJsonArray("scenes").get(sceneIndex).getAsJsonObject();
        JsonObject parent = new JsonObject();
        parent.addProperty("name", attachment ? "AttachmentVisual" : "GunVisual");
        parent.add("children", scene.get("nodes").deepCopy());
        int angle = spec.get("rotation_y_degrees").getAsInt();
        parent.add("rotation", numbers(0, Math.sin(Math.toRadians(angle / 2.0)), 0, Math.cos(Math.toRadians(angle / 2.0))));
        double[] translation = attachment ? negate(rotate(vector(spec.getAsJsonArray("mount")), angle)) : vector(spec.getAsJsonArray("translation"));
        parent.add("translation", numbers(translation));
        nodes.add(parent);
        JsonArray rootIndices = new JsonArray();
        rootIndices.add(nodes.size() - 1);
        scene.add("nodes", rootIndices);
        String directory = ASSETS + "models/gltf/" + id + "/";
        String name = "model." + (glb ? "glb" : "gltf");
        entries.put(directory + name, glb ? new Payload(null, writeGlb(new Model(json, original.binaryChunks()))) : Payload.of(json));
        JsonObject closure = new JsonObject();
        for (String table : new String[]{"buffers", "images"}) if (json.has(table)) {
            for (JsonElement element : json.getAsJsonArray(table)) {
                JsonObject resource = element.getAsJsonObject();
                if (!resource.has("uri")) continue;
                String uri = resource.get("uri").getAsString();
                Path dependency = safeFile(input.getParent(), uri);
                entries.put(directory + uri, new Payload(dependency, null));
            }
        }
        for (var entry : entries.entrySet()) if (entry.getKey().startsWith(directory)) {
            try (InputStream in = entry.getValue().open()) { closure.add(entry.getKey().substring(directory.length()), digest(in, MAX_ENTRY).json()); }
        }
        long tris = 0, vertices = 0;
        for (JsonElement mesh : json.getAsJsonArray("meshes")) for (JsonElement primitive : mesh.getAsJsonObject().getAsJsonArray("primitives")) {
            JsonObject p = primitive.getAsJsonObject();
            if (p.has("mode") && p.get("mode").getAsInt() != 4) throw new IOException("Showcase expects triangle primitives");
            int count = json.getAsJsonArray("accessors").get(p.get("indices").getAsInt()).getAsJsonObject().get("count").getAsInt();
            if (count % 3 != 0) throw new IOException("Invalid triangle index count");
            tris += count / 3;
            vertices += json.getAsJsonArray("accessors").get(p.getAsJsonObject("attributes").get("POSITION").getAsInt()).getAsJsonObject().get("count").getAsInt();
        }
        if (tris != spec.get("triangles").getAsLong()) throw new IOException("Unexpected source triangle count: " + id + " " + tris);
        if (!original.json().get("accessors").equals(json.get("accessors")) || !original.json().get("bufferViews").equals(json.get("bufferViews"))) throw new IOException("Accessor encoding must remain unchanged");
        if (glb && !original.json().get("extensionsRequired").equals(json.get("extensionsRequired"))) throw new IOException("Quantization extension must remain required");
        JsonObject proof = new JsonObject();
        proof.addProperty("id", id);
        proof.addProperty("kind", attachment ? "attachment" : "gun");
        proof.add("derivation", spec.deepCopy());
        proof.addProperty("location", NS + ":models/gltf/" + id + "/" + name);
        proof.addProperty("triangles", tris);
        proof.addProperty("position_vertex_records", vertices);
        proof.addProperty("mesh_count", json.getAsJsonArray("meshes").size());
        proof.addProperty("material_count", json.getAsJsonArray("materials").size());
        proof.addProperty("source_binary_chunks_sha256", sha(original.binaryChunks(), "SHA-256"));
        proof.addProperty("accessors_and_buffer_views_unchanged", true);
        proof.add("offline_root", parent.deepCopy());
        proof.add("asset_files", closure);
        return proof;
    }

    private static void addGun(Map<String, Payload> entries, Path root, JsonObject spec, JsonObject proof) throws Exception {
        String id = spec.get("id").getAsString(), template = spec.get("template").getAsString();
        Path defaults = root.resolve(DEFAULT_PACK);
        JsonObject index = read(defaults.resolve("data/tacz/index/guns/" + template + ".json"));
        JsonObject display = read(defaults.resolve(MeshGunpackReferences.resource(index.get("display").getAsString(), "assets", "display/guns/", ".json")));
        JsonObject data = read(defaults.resolve(MeshGunpackReferences.resource(index.get("data").getAsString(), "data", "data/guns/", ".json")));
        JsonObject rig = read(defaults.resolve(MeshGunpackReferences.resource(display.get("model").getAsString(), "assets", "geo_models/", ".json")));
        JsonObject animation = read(defaults.resolve(MeshGunpackReferences.resource(display.get("animation").getAsString(), "assets", "animations/", ".animation.json")));
        JsonObject templateSources = new JsonObject();
        for (String path : new String[]{"data/tacz/index/guns/" + template + ".json",
                MeshGunpackReferences.resource(index.get("display").getAsString(), "assets", "display/guns/", ".json"),
                MeshGunpackReferences.resource(index.get("data").getAsString(), "data", "data/guns/", ".json"),
                MeshGunpackReferences.resource(display.get("model").getAsString(), "assets", "geo_models/", ".json"),
                MeshGunpackReferences.resource(display.get("animation").getAsString(), "assets", "animations/", ".animation.json")}) {
            try (InputStream in = Files.newInputStream(safeFile(defaults, path))) { templateSources.add(path, digest(in, MAX_ENTRY).json()); }
        }
        proof.add("game_template_sources", templateSources);
        JsonObject soundRetirements = new JsonObject();
        for (var retirement : spec.getAsJsonObject("retired_sound_effects").entrySet()) {
            String clip = retirement.getKey();
            JsonObject action = animation.getAsJsonObject("animations").getAsJsonObject(clip);
            JsonObject old = action.getAsJsonObject("sound_effects");
            TreeSet<String> actualIds = new TreeSet<>(), expectedIds = new TreeSet<>();
            for (JsonElement value : old.asMap().values()) actualIds.add(value.getAsJsonObject().get("effect").getAsString());
            for (JsonElement value : retirement.getValue().getAsJsonArray()) expectedIds.add(value.getAsString());
            if (!actualIds.equals(expectedIds)) throw new IOException("Obsolete sound event set changed: " + template + "/" + clip);
            String replacement = display.getAsJsonObject("sounds").get(clip).getAsString();
            safeFile(defaults, MeshGunpackReferences.resource(replacement, "assets", "tacz_sounds/", ".ogg"));
            JsonObject record = new JsonObject();
            record.add("old_sound_effects", old.deepCopy());
            record.addProperty("existing_display_track", replacement);
            soundRetirements.add(clip, record);
            // These legacy aliases do not resolve. The display already owns the complete audio track.
            action.remove("sound_effects");
        }
        proof.add("retired_sound_effects", soundRetirements);
        JsonArray bones = rig.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        for (JsonElement bone : bones) bone.getAsJsonObject().remove("cubes");
        double[] rootPivot = vector(findByName(bones, "root").getAsJsonArray("pivot"));
        String body = spec.get("body_bone").getAsString();
        JsonObject sockets = spec.getAsJsonObject("sockets");
        JsonObject anchorProof = new JsonObject();
        JsonArray allowed = new JsonArray(), tags = new JsonArray();
        for (var socket : sockets.entrySet()) {
            String type = socket.getKey();
            allowed.add(type);
            tags.add("#" + NS + ":" + type);
            double[] g = add(rotate(vector(socket.getValue().getAsJsonArray()), spec.get("rotation_y_degrees").getAsInt()), vector(spec.getAsJsonArray("translation")));
            double[] pivot = {rootPivot[0] - 16 * g[0], rootPivot[1] + 16 * g[1], rootPivot[2] + 16 * g[2]};
            setAnchor(bones, type + "_pos", body, pivot);
            if (type.equals("muzzle")) setAnchor(bones, "muzzle_flash", body, pivot);
            JsonObject anchor = new JsonObject();
            anchor.add("author_socket", socket.getValue().deepCopy());
            anchor.add("gltf_point", numbers(g));
            anchor.add("bedrock_pivot", numbers(pivot));
            anchor.add("runtime_relative_point", numbers(-g[0], -g[1], g[2]));
            anchor.addProperty("numeric_residual_metres", anchorResidual(rootPivot, pivot, g));
            anchorProof.add(type, anchor);
        }
        proof.add("bedrock_root_pivot", numbers(rootPivot));
        proof.add("installed_anchors", anchorProof);
        data.add("allow_attachment_types", allowed);
        proof.add("refit_overview_views", ensureRefitViews(bones, allowed));
        entries.put(DATA + "tacz_tags/attachments/allow_attachments/" + id + ".json", Payload.of(tags));
        index.addProperty("name", NS + ".guns." + id + ".name");
        index.addProperty("tooltip", NS + ".guns." + id + ".desc");
        index.addProperty("display", NS + ":" + id);
        index.addProperty("data", NS + ":" + id);
        removeLegacyVisuals(display);
        display.addProperty("model", NS + ":gun/" + id);
        display.addProperty("animation", NS + ":" + id);
        JsonObject renderer = renderer(proof.get("location").getAsString());
        JsonObject map = new JsonObject();
        map.addProperty(body, "GunVisual");
        renderer.add("node_map", map);
        display.add("render_model", renderer);
        entries.put(DATA + "index/guns/" + id + ".json", Payload.of(index));
        entries.put(DATA + "data/guns/" + id + ".json", Payload.of(data));
        entries.put(ASSETS + "display/guns/" + id + ".json", Payload.of(display));
        entries.put(ASSETS + "geo_models/gun/" + id + ".json", Payload.of(rig));
        entries.put(ASSETS + "animations/" + id + ".animation.json", Payload.of(animation));
    }

    private static void addAttachment(Map<String, Payload> entries, Path root, JsonObject spec) throws Exception {
        String id = spec.get("id").getAsString(), template = spec.get("template").getAsString();
        Path defaults = root.resolve(DEFAULT_PACK);
        JsonObject index = read(defaults.resolve("data/tacz/index/attachments/" + template + ".json"));
        JsonObject display = read(defaults.resolve(MeshGunpackReferences.resource(index.get("display").getAsString(), "assets", "display/attachments/", ".json")));
        JsonObject data = read(defaults.resolve(MeshGunpackReferences.resource(index.get("data").getAsString(), "data", "data/attachments/", ".json")));
        index.addProperty("name", NS + ".attachments." + id + ".name");
        index.addProperty("display", NS + ":" + id);
        index.addProperty("data", NS + ":" + id);
        display.remove("slot");
        display.addProperty("model", NS + ":attachment/" + id);
        display.add("render_model", renderer(NS + ":models/gltf/" + id + "/model.glb"));
        JsonObject rig = read(defaults.resolve(MeshGunpackReferences.resource("tacz:attachment/" + template + "_geo", "assets", "geo_models/", ".json")));
        for (JsonElement geometry : rig.getAsJsonArray("minecraft:geometry")) for (JsonElement bone : geometry.getAsJsonObject().getAsJsonArray("bones")) bone.getAsJsonObject().remove("cubes");
        entries.put(DATA + "index/attachments/" + id + ".json", Payload.of(index));
        entries.put(DATA + "data/attachments/" + id + ".json", Payload.of(data));
        entries.put(ASSETS + "display/attachments/" + id + ".json", Payload.of(display));
        entries.put(ASSETS + "geo_models/attachment/" + id + ".json", Payload.of(rig));
    }

    static void validate(Path root, Path zipPath, boolean runtime) throws Exception {
        TreeMap<String, Payload> expected = entries(root);
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Set<String> names = new TreeSet<>();
            long total = 0;
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                var entry = iterator.nextElement();
                checkPath(entry.getName());
                if (entry.isDirectory() || !names.add(entry.getName()) || !expected.containsKey(entry.getName())) throw new IOException("Unexpected/duplicate pack entry: " + entry.getName());
                Digest actual, wanted;
                try (InputStream in = zip.getInputStream(entry)) { actual = digest(in, MAX_ENTRY); }
                try (InputStream in = expected.get(entry.getName()).open()) { wanted = digest(in, MAX_ENTRY); }
                if (!actual.equals(wanted) || actual.crc32() != entry.getCrc() || actual.size() != entry.getSize()) throw new IOException("Pack content/hash/CRC mismatch: " + entry.getName());
                if ((total += actual.size()) > MAX_TOTAL) throw new IOException("Pack size limit exceeded");
            }
            if (!names.equals(expected.keySet())) throw new IOException("Missing pack entries");
            if (runtime) {
                Opener opener = name -> {
                    var entry = zip.getEntry(name);
                    if (entry == null) throw new IOException("Missing runtime asset: " + name);
                    return zip.getInputStream(entry);
                };
                JsonObject manifest = read(opener, REPORT);
                for (JsonElement item : manifest.getAsJsonArray("models")) {
                    JsonObject model = item.getAsJsonObject();
                    String kind = model.get("kind").getAsString().equals("gun") ? "guns" : "attachments";
                    JsonObject display = read(opener, ASSETS + "display/" + kind + "/" + model.get("id").getAsString() + ".json");
                    JsonObject renderer = display.getAsJsonObject("render_model");
                    JsonObject nodeMap = renderer.has("node_map") ? renderer.getAsJsonObject("node_map") : new JsonObject();
                    String path = MeshGunpackReferences.resource(renderer.get("location").getAsString(), "assets", "", "");
                    Set<String> closure = MeshGunpackAssets.validate(opener, path, nodeMap);
                    String directory = path.substring(0, path.lastIndexOf('/') + 1);
                    Set<String> declared = new TreeSet<>();
                    for (String member : model.getAsJsonObject("asset_files").keySet()) declared.add(directory + member);
                    if (!closure.equals(declared)) throw new IOException("Runtime model closure mismatch");
                }
            }
            System.out.println("Validated " + NS + ": " + names.size() + " files, " + total + " bytes, runtimeLoader=" + runtime);
        }
    }

    static Model readGlb(byte[] bytes) throws IOException {
        if (bytes.length < 20) throw new IOException("Truncated GLB");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != 0x46546c67 || input.getInt() != 2 || input.getInt() != bytes.length) throw new IOException("Invalid GLB header");
        int length = input.getInt();
        if (input.getInt() != 0x4e4f534a || length < 2 || length > bytes.length - 20 || length % 4 != 0) throw new IOException("Invalid GLB JSON chunk");
        byte[] tail = Arrays.copyOfRange(bytes, 20 + length, bytes.length);
        if (tail.length < 8 || ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).getInt() != tail.length - 8
                || ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).getInt(4) != 0x004e4942) throw new IOException("Expected one intact GLB BIN chunk");
        return new Model(read(new ByteArrayInputStream(bytes, 20, length)), tail);
    }

    static byte[] writeGlb(Model model) {
        byte[] json = GSON.toJson(model.json()).getBytes(StandardCharsets.UTF_8);
        int padded = (json.length + 3) & ~3;
        ByteBuffer result = ByteBuffer.allocate(20 + padded + model.binaryChunks().length).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x46546c67).putInt(2).putInt(result.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);
        while (result.position() < 20 + padded) result.put((byte) ' ');
        result.put(model.binaryChunks());
        return result.array();
    }

    private static void verifyPolyHavenMd5(Path source) throws Exception {
        JsonObject record = read(source.resolve("service_pistol.files.json")).getAsJsonObject("gltf").getAsJsonObject("4k").getAsJsonObject("gltf");
        Map<String, JsonObject> files = new TreeMap<>();
        files.put("service_pistol_4k.gltf", record);
        record.getAsJsonObject("include").entrySet().forEach(entry -> files.put(entry.getKey(), entry.getValue().getAsJsonObject()));
        for (var entry : files.entrySet()) {
            byte[] bytes = Files.readAllBytes(safeFile(source.resolve("service_pistol"), entry.getKey()));
            if (!sha(bytes, "MD5").equals(entry.getValue().get("md5").getAsString()) || bytes.length != entry.getValue().get("size").getAsLong()) throw new IOException("Poly Haven MD5/size mismatch: " + entry.getKey());
        }
    }

    static JsonArray ensureRefitViews(JsonArray bones, JsonArray allowed) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        for (JsonElement bone : bones) names.add(bone.getAsJsonObject().get("name").getAsString());
        JsonArray added = new JsonArray();
        for (JsonElement type : allowed) {
            String name = "refit_" + type.getAsString() + "_view";
            if (names.contains(name)) continue;
            // A missing attachment view otherwise becomes an identity camera transform at runtime.
            JsonObject view = findByName(bones, "refit_view").deepCopy();
            view.addProperty("name", name);
            bones.add(view);
            names.add(name);
            added.add(name);
        }
        PublicMeshPackReferences.checkRefitViews(bones, allowed);
        return added;
    }

    private static void setAnchor(JsonArray bones, String name, String parent, double[] pivot) {
        JsonObject bone = null;
        for (JsonElement element : bones) if (name.equals(element.getAsJsonObject().get("name").getAsString())) bone = element.getAsJsonObject();
        if (bone == null) { bone = new JsonObject(); bone.addProperty("name", name); bones.add(bone); }
        bone.addProperty("parent", parent);
        bone.add("pivot", numbers(pivot));
        bone.remove("rotation");
    }

    static double anchorResidual(double[] root, double[] pivot, double[] g) throws IOException {
        double residual = Math.max(Math.abs((pivot[0] - root[0]) / 16 + g[0]), Math.max(Math.abs((root[1] - pivot[1]) / 16 + g[1]), Math.abs((pivot[2] - root[2]) / 16 - g[2])));
        if (residual > 1e-12) throw new IOException("Attachment origin mismatch");
        return residual;
    }

    private static JsonObject renderer(String location) { JsonObject value = new JsonObject(); value.addProperty("type", "gltf"); value.addProperty("location", location); value.addProperty("scale", 1); return value; }
    private static JsonObject findById(JsonArray array, String id) throws IOException { for (JsonElement e : array) if (id.equals(e.getAsJsonObject().get("id").getAsString())) return e.getAsJsonObject(); throw new IOException("Missing source metadata: " + id); }
    private static JsonObject findByName(JsonArray array, String name) throws IOException { JsonObject found = null; for (JsonElement e : array) { JsonObject o = e.getAsJsonObject(); if (o.has("name") && name.equals(o.get("name").getAsString())) { if (found != null) throw new IOException("Duplicate source name: " + name); found = o; } } if (found == null) throw new IOException("Missing source node: " + name); return found; }
    static double[] rotate(double[] point, int degrees) { return switch (degrees) { case 180 -> new double[]{-point[0], point[1], -point[2]}; case 90 -> new double[]{point[2], point[1], -point[0]}; default -> throw new IllegalArgumentException("Only the declared cardinal source bases are supported"); }; }
    private static double[] negate(double[] a) { return new double[]{-a[0], -a[1], -a[2]}; }
    private static double[] add(double[] a, double[] b) { return new double[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]}; }
    static double[] vector(JsonArray a) { if (a.size() != 3) throw new IllegalArgumentException("Expected XYZ"); return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }
    static JsonArray numbers(double... values) { JsonArray a = new JsonArray(); for (double x : values) { if (!Double.isFinite(x)) throw new IllegalArgumentException("Non-finite coordinate"); a.add(x); } return a; }
    static JsonArray strings(String... values) { JsonArray a = new JsonArray(); for (String x : values) a.add(x); return a; }
    static String sha(byte[] bytes, String algorithm) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
}
