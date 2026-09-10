package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.tacz.guns.tools.gunpack.MeshGunpackTool.*;
import static com.tacz.guns.tools.gunpack.PublicMeshPackTool.*;

/** Checks the typed default-pack dependencies of the five pinned showcase templates. */
final class PublicMeshPackReferences {
    private final Path root;
    private final Map<String, Payload> own;
    private final TreeMap<String, String> shared = new TreeMap<>();

    PublicMeshPackReferences(Path root, Map<String, Payload> own) { this.root = root; this.own = own; }

    void check(JsonObject template) throws Exception {
        for (String category : new String[]{"guns", "attachments"}) for (JsonElement element : template.getAsJsonArray(category)) {
            JsonObject spec = element.getAsJsonObject();
            String id = spec.get("id").getAsString();
            JsonObject index = json(DATA + "index/" + category + "/" + id + ".json");
            JsonObject display = json(resource(index, "display", "assets", "display/" + category + "/", ".json"));
            JsonObject data = json(resource(index, "data", "data", "data/" + category + "/", ".json"));
            JsonObject rig = json(resource(display, "model", "assets", "geo_models/", ".json"));
            resource(display, "texture", "assets", "textures/", ".png");
            if (display.has("lod") || display.has("slot") || display.has("hud") || display.has("hud_empty")) throw new IOException("Legacy visual reference in mesh display");
            JsonObject render = display.getAsJsonObject("render_model");
            if (!render.get("type").getAsString().equals("gltf") || render.get("scale").getAsDouble() != 1) throw new IOException("Unexpected mesh renderer contract");
            String model = resource(render, "location", "assets", "", "");
            if (!model.startsWith(ASSETS + "models/gltf/" + id + "/")) throw new IOException("Model must be owned by its item");
            for (JsonElement value : display.getAsJsonObject("sounds").asMap().values()) sound(value.getAsString());
            if (category.equals("attachments")) {
                if (render.has("node_map")) throw new IOException("Attachments must not have a Bedrock node map");
                if (!index.get("type").equals(spec.get("type"))) throw new IOException("Attachment type mismatch");
                continue;
            }
            JsonObject animation = json(resource(display, "animation", "assets", "animations/", ".animation.json"));
            effects(animation, false);
            TreeSet<String> bones = new TreeSet<>();
            for (JsonElement geometry : rig.getAsJsonArray("minecraft:geometry")) for (JsonElement bone : geometry.getAsJsonObject().getAsJsonArray("bones")) bones.add(bone.getAsJsonObject().get("name").getAsString());
            for (String required : new String[]{"root", "lefthand_pos", "righthand_pos", spec.get("body_bone").getAsString()}) if (!bones.contains(required)) throw new IOException("Missing rig bone: " + required);
            JsonObject mapping = render.getAsJsonObject("node_map");
            if (mapping.size() != 1 || !mapping.has(spec.get("body_bone").getAsString()) || !mapping.get(spec.get("body_bone").getAsString()).getAsString().equals("GunVisual")) throw new IOException("Unexpected visual body mapping");
            if (display.has("state_machine")) resource(display, "state_machine", "assets", "scripts/", ".lua");
            require("assets/tacz/scripts/default_state_machine.lua");
            resource(display.getAsJsonObject("muzzle_flash"), "texture", "assets", "textures/", ".png");
            String style = display.get("use_default_animation").getAsString();
            if (!style.equals("rifle") && !style.equals("pistol")) throw new IOException("Unsupported template animation provider");
            require("assets/tacz/animations/" + style + "_default.animation.json");
            String animator = display.has("player_animator_3rd") ? display.get("player_animator_3rd").getAsString() : "tacz:rifle_default.player_animation";
            require(MeshGunpackReferences.resource(animator, "assets", "player_animator/", ".json"));
            for (String sound : new String[]{"dry_fire", "fire_select", "head_hit", "flesh_hit", "kill", "melee_bayonet/melee_bayonet_01"}) sound("tacz:" + sound);
            JsonObject ammo = json(resource(data, "ammo", "data", "index/ammo/", ".json"));
            JsonObject ammoDisplay = json(resource(ammo, "display", "assets", "display/ammo/", ".json"));
            resource(ammoDisplay, "model", "assets", "geo_models/", ".json");
            resource(ammoDisplay, "texture", "assets", "textures/", ".png");
            resource(ammoDisplay, "slot", "assets", "textures/", ".png");
            resource(ammoDisplay.getAsJsonObject("shell"), "model", "assets", "geo_models/", ".json");
            resource(ammoDisplay.getAsJsonObject("shell"), "texture", "assets", "textures/", ".png");
            JsonArray allowed = data.getAsJsonArray("allow_attachment_types");
            checkRefitViews(rig.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones"), allowed);
            JsonArray tags = anyJson(DATA + "tacz_tags/attachments/allow_attachments/" + id + ".json").getAsJsonArray();
            if (allowed.size() != spec.getAsJsonObject("sockets").size() || tags.size() != allowed.size()) throw new IOException("Attachment allow-list disagrees with actual sockets");
            for (JsonElement type : allowed) {
                String name = type.getAsString();
                if (!spec.getAsJsonObject("sockets").has(name) || !tags.contains(new com.google.gson.JsonPrimitive("#" + NS + ":" + name))) throw new IOException("Undeclared socket type");
                if (!bones.contains(name + "_pos")) throw new IOException("Allowed attachment lacks a rig anchor");
                JsonArray ids = anyJson(DATA + "tacz_tags/attachments/" + name + ".json").getAsJsonArray();
                if (ids.size() != 1) throw new IOException("Only the pinned compatible attachment may be exposed");
                JsonObject attachment = json(MeshGunpackReferences.resource(ids.get(0).getAsString(), "data", "index/attachments/", ".json"));
                if (!attachment.get("type").getAsString().equals(name)) throw new IOException("Compatibility tag points to the wrong attachment type");
            }
        }
        JsonObject info = json(ASSETS + "gunpack_info.json");
        if (!MIXED_LICENSE.equals(info.get("license").getAsString())) throw new IOException("Mixed-license warning missing");
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            JsonObject lang = json(ASSETS + "lang/" + locale + ".json");
            for (String category : new String[]{"guns", "attachments"}) for (JsonElement item : template.getAsJsonArray(category)) {
                String prefix = NS + "." + category + "." + item.getAsJsonObject().get("id").getAsString();
                for (String suffix : new String[]{".name", ".desc"}) if (!lang.has(prefix + suffix)) throw new IOException("Missing localization: " + prefix + suffix);
            }
        }
    }

    static void checkRefitViews(JsonArray bones, JsonArray allowed) throws IOException {
        TreeMap<String, JsonObject> byName = new TreeMap<>();
        for (JsonElement bone : bones) {
            JsonObject value = bone.getAsJsonObject();
            String name = value.get("name").getAsString();
            if (byName.put(name, value) != null) throw new IOException("Duplicate rig bone: " + name);
        }
        TreeSet<String> views = new TreeSet<>();
        views.add("refit_view");
        for (JsonElement type : allowed) views.add("refit_" + type.getAsString() + "_view");
        for (String view : views) {
            TreeSet<String> visited = new TreeSet<>();
            for (String name = view; name != null;) {
                JsonObject bone = byName.get(name);
                if (bone == null || !visited.add(name)) throw new IOException("Missing/cyclic refit view parent chain: " + view + "/" + name);
                if (bone.has("cubes")) throw new IOException("Refit view chain must not contain cubes: " + name);
                for (String field : new String[]{"pivot", "rotation"}) {
                    if (field.equals("rotation") && !bone.has(field)) continue;
                    JsonElement value = bone.get(field);
                    if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) throw new IOException("Invalid refit transform: " + name + "/" + field);
                    for (JsonElement coordinate : value.getAsJsonArray()) {
                        if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber() || !Float.isFinite(coordinate.getAsFloat())) throw new IOException("Non-finite refit transform: " + name + "/" + field);
                    }
                }
                name = bone.has("parent") ? bone.get("parent").getAsString() : null;
            }
        }
    }

    private void effects(JsonElement element, boolean inSounds) throws IOException {
        if (element.isJsonObject()) for (var entry : element.getAsJsonObject().entrySet()) {
            if (inSounds && entry.getKey().equals("effect")) sound(entry.getValue().getAsString());
            else effects(entry.getValue(), inSounds || entry.getKey().equals("sound_effects"));
        }
        else if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) effects(child, inSounds);
    }

    private String resource(JsonObject object, String key, String domain, String prefix, String suffix) throws IOException {
        if (object == null || !object.has(key)) throw new IOException("Missing typed resource field: " + key);
        String path = MeshGunpackReferences.resource(object.get(key).getAsString(), domain, prefix, suffix);
        require(path);
        return path;
    }

    private void sound(String id) throws IOException { require(MeshGunpackReferences.resource(id, "assets", "tacz_sounds/", ".ogg")); }
    private JsonObject json(String path) throws IOException { try (InputStream in = open(path)) { return read(in); } }
    private JsonElement anyJson(String path) throws IOException { try (InputStream in = open(path)) { return GSON.fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), JsonElement.class); } }
    private void require(String path) throws IOException { try (InputStream in = open(path)) { if (in.read() < 0) throw new IOException("Empty referenced asset: " + path); } }

    private InputStream open(String path) throws IOException {
        checkPath(path);
        Payload payload = own.get(path);
        if (payload != null) return payload.open();
        if (path.startsWith(ASSETS) || path.startsWith(DATA)) throw new IOException("Missing own resource: " + path);
        if (!path.startsWith("assets/tacz/") && !path.startsWith("data/tacz/")) throw new IOException("Unapproved resource provider: " + path);
        boolean internal = path.equals("assets/tacz/animations/rifle_default.animation.json") || path.equals("assets/tacz/animations/pistol_default.animation.json");
        Path base = root.resolve(internal ? "src/main/resources" : DEFAULT_PACK);
        Path file = safeFile(base, path);
        shared.put(path, internal ? "mod" : "default_pack");
        return Files.newInputStream(file);
    }

    JsonArray shared() {
        JsonArray array = new JsonArray();
        shared.forEach((path, provider) -> { JsonObject item = new JsonObject(); item.addProperty("path", path); item.addProperty("provider", provider); array.add(item); });
        return array;
    }
}
