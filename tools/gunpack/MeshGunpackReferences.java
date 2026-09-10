package com.tacz.guns.tools.gunpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Resolves this pack's typed references using the existing gunpack directory conventions. */
final class MeshGunpackReferences {
    private final Path root;
    private final Path defaultPack;
    private final String namespace;
    private final MeshGunpackTool.Opener own;
    private final TreeMap<String, String> shared = new TreeMap<>();

    MeshGunpackReferences(Path root, Path defaultPack, String namespace, MeshGunpackTool.Opener own) {
        this.root = root;
        this.defaultPack = defaultPack;
        this.namespace = namespace;
        this.own = own;
    }

    void check(String gun) throws IOException {
        String assets = "assets/" + namespace + "/";
        JsonObject index = json("data/" + namespace + "/index/guns/" + gun + ".json");
        String displayPath = resource(text(index, "display"), "assets", "display/guns/", ".json");
        String dataPath = resource(text(index, "data"), "data", "data/guns/", ".json");
        if (!displayPath.startsWith(assets) || !dataPath.startsWith("data/" + namespace + "/")) throw new IOException("Gun display and game data must belong to the pack");
        JsonObject display = json(displayPath);
        JsonObject data = json(dataPath);
        String geometryPath = resource(text(display, "model"), "assets", "geo_models/", ".json");
        String animationPath = resource(text(display, "animation"), "assets", "animations/", ".animation.json");
        if (!geometryPath.startsWith(assets) || !animationPath.startsWith(assets)) throw new IOException("Calibrated rig and animation must belong to the pack");
        JsonObject geometry = json(geometryPath);
        JsonObject animation = json(animationPath);
        JsonObject renderer = display.getAsJsonObject("render_model");
        if (!text(renderer, "type").equals("gltf")) throw new IOException("Mesh gunpack requires the glTF renderer");
        String modelPath = resource(text(renderer, "location"), "assets", "", "");
        if (!modelPath.startsWith(assets + "models/gltf/")) throw new IOException("Mesh model must be bundled under the pack namespace");
        require(modelPath);
        if (!(renderer.get("scale").getAsDouble() > 0) || !Double.isFinite(renderer.get("scale").getAsDouble())) throw new IOException("Invalid render scale");
        Set<String> bones = new TreeSet<>();
        for (JsonElement model : geometry.getAsJsonArray("minecraft:geometry")) {
            for (JsonElement bone : model.getAsJsonObject().getAsJsonArray("bones")) bones.add(text(bone.getAsJsonObject(), "name"));
        }
        for (String source : renderer.getAsJsonObject("node_map").keySet()) {
            if (!bones.contains(source)) throw new IOException("Mapped source bone missing: " + source);
        }
        for (String hand : new String[]{"lefthand_pos", "righthand_pos"}) {
            if (!bones.contains(hand)) throw new IOException("Calibrated hand anchor missing: " + hand);
        }
        requireResource(display, "texture", "assets", "textures/", ".png");
        // Mesh displays use an automatically rendered atlas icon. Older explicit resources remain checked.
        for (String field : new String[]{"hud", "hud_empty", "slot"}) {
            if (display.has(field)) requireResource(display, field, "assets", "textures/", ".png");
        }
        if (display.has("lod")) {
            JsonObject lod = display.getAsJsonObject("lod");
            requireResource(lod, "model", "assets", "geo_models/", ".json");
            requireResource(lod, "texture", "assets", "textures/", ".png");
        }
        requireResource(display.getAsJsonObject("muzzle_flash"), "texture", "assets", "textures/", ".png");
        requireResource(display, "state_machine", "assets", "scripts/", ".lua");
        for (JsonElement sound : display.getAsJsonObject("sounds").asMap().values()) sound(sound.getAsString());
        checkEffects(animation);

        // These dependencies are inserted by GunDisplayInstance, or required by the declared Lua script.
        for (String sound : new String[]{"dry_fire", "fire_select", "head_hit", "flesh_hit", "kill", "melee_bayonet/melee_bayonet_01"}) sound("tacz:" + sound);
        require("assets/tacz/scripts/default_state_machine.lua");
        if (!text(display, "use_default_animation").equals("rifle")) throw new IOException("This template expects the existing default rifle animation provider");
        require("assets/tacz/animations/rifle_default.animation.json");
        JsonObject ammoIndex = json(resource(text(data, "ammo"), "data", "index/ammo/", ".json"));
        JsonObject ammoDisplay = json(resource(text(ammoIndex, "display"), "assets", "display/ammo/", ".json"));
        requireResource(ammoDisplay, "model", "assets", "geo_models/", ".json");
        requireResource(ammoDisplay, "texture", "assets", "textures/", ".png");
        requireResource(ammoDisplay, "slot", "assets", "textures/", ".png");
        JsonObject shell = ammoDisplay.getAsJsonObject("shell");
        requireResource(shell, "model", "assets", "geo_models/", ".json");
        requireResource(shell, "texture", "assets", "textures/", ".png");
        JsonObject info = json(assets + "gunpack_info.json");
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            JsonObject lang = json(assets + "lang/" + locale + ".json");
            for (String key : new String[]{text(index, "name"), text(index, "tooltip"), text(info, "name"), text(info, "desc")}) {
                if (!lang.has(key) || lang.get(key).getAsString().isBlank()) throw new IOException("Missing localization " + locale + ": " + key);
            }
        }
    }

    private void checkEffects(JsonElement element) throws IOException {
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("sound_effects")) checkSoundEffects(entry.getValue());
                else checkEffects(entry.getValue());
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) checkEffects(child);
        }
    }

    private void checkSoundEffects(JsonElement element) throws IOException {
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("effect")) sound(entry.getValue().getAsString());
                else checkSoundEffects(entry.getValue());
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) checkSoundEffects(child);
        }
    }

    private void sound(String id) throws IOException { require(resource(id, "assets", "tacz_sounds/", ".ogg")); }

    private void requireResource(JsonObject object, String key, String domain, String prefix, String suffix) throws IOException {
        require(resource(text(object, key), domain, prefix, suffix));
    }

    private void require(String path) throws IOException {
        try (InputStream stream = open(path)) {
            if (stream.read() == -1) throw new IOException("Empty referenced file: " + path);
        }
    }

    private JsonObject json(String path) throws IOException {
        try (InputStream stream = open(path)) { return MeshGunpackTool.read(stream); }
    }

    private InputStream open(String path) throws IOException {
        MeshGunpackTool.checkPath(path);
        if (path.startsWith("assets/" + namespace + "/") || path.startsWith("data/" + namespace + "/")) return own.open(path);
        if (!(path.startsWith("assets/tacz/") || path.startsWith("data/tacz/"))) throw new IOException("Undeclared resource provider: " + path);
        // Only the built-in rifle animation is supplied by the mod. All other tacz refs require the default pack.
        boolean internal = path.equals("assets/tacz/animations/rifle_default.animation.json");
        Path provider = internal ? root.resolve("src/main/resources") : defaultPack;
        Path file = MeshGunpackTool.safeFile(provider, path);
        shared.put(path, internal ? "mod" : "default_pack");
        return Files.newInputStream(file);
    }

    JsonArray sharedJson() {
        JsonArray values = new JsonArray();
        shared.forEach((path, provider) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", path);
            entry.addProperty("provider", provider);
            values.add(entry);
        });
        return values;
    }

    static String resource(String id, String domain, String prefix, String suffix) throws IOException {
        String[] parts = id.split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[a-z0-9_.-]+") || !parts[1].matches("[a-z0-9_./-]+")) throw new IOException("Invalid resource ID: " + id);
        MeshGunpackTool.checkPath(parts[1]);
        String path = domain + "/" + parts[0] + "/" + prefix + parts[1] + suffix;
        MeshGunpackTool.checkPath(path);
        return path;
    }

    private static String text(JsonObject object, String field) throws IOException {
        if (object == null || !object.has(field) || !object.get(field).isJsonPrimitive() || !object.get(field).getAsJsonPrimitive().isString()) throw new IOException("Missing string field: " + field);
        return object.get(field).getAsString();
    }
}
