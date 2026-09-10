package com.tacz.guns.client.model.gltf.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.api.client.animation.Animations;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.client.resource.manager.GltfModelManager;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimationFile;
import com.tacz.guns.client.resource.pojo.display.gun.GunRenderModelConfig;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Two different axis/basis/hand fixtures plus input guards; uses no private production test fixtures. */
public final class CalibrationSelfTest {
    public static void main(String[] args) throws Exception {
        check(args.length == 0 || args.length == 4, "Optional args: animation path, clip, bone, time");
        for (int variant = 0; variant < 2; variant++) {
            JsonObject geometry = json("""
                    {"format_version":"1.12.0","minecraft:geometry":[{
                      "description":{"identifier":"geometry.calibration","texture_width":16,"texture_height":16,
                        "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                      "bones":[{"name":"root","pivot":[0,24,0]},
                        {"name":"carrier","parent":"root","pivot":[0,24,0]},
                        {"name":"slide","parent":"carrier","pivot":[0,24,0]},
                        {"name":"hand","parent":"carrier","pivot":[1,22,3]},
                        {"name":"righthand_pos","parent":"hand","pivot":[2,20,4]}]}]}
                    """);
            String travel = variant == 0 ? "[0,0,8]" : "[8,0,0]";
            String turn = variant == 0 ? "[0,0,60]" : "[60,0,0]";
            String targetName = variant == 0 ? "MovingVisual" : "ContactVisual";
            JsonObject animation = json("""
                    {"format_version":"1.8.0","animations":{
                      "reference":{"animation_length":1,"bones":{
                        "hand":{"rotation":[10,20,30],"position":[2,-1,3],"scale":[1,1.5,1]}}},
                      "shoot":{"animation_length":1,"bones":{"slide":{"position":[0,0,0]}}},
                      "operate":{"animation_length":1,"bones":{
                        "carrier":{"rotation":{"0":[0,0,0],"1":[15,30,20]}},
                        "slide":{"position":{"0":[0,0,0],"0.5":TRAVEL,"1":[0,0,0]},
                          "rotation":{"0":{"post":[0,0,0],"lerp_mode":"catmullrom"},
                            "0.25":{"post":[0,0,0],"lerp_mode":"catmullrom"},
                            "0.5":{"post":TURN,"lerp_mode":"catmullrom"},
                            "0.75":{"post":TURN,"lerp_mode":"catmullrom"},
                            "1":{"post":[0,0,0],"lerp_mode":"catmullrom"}}},
                        "hand":{"position":{"0":[1,2,3],"1":[-1,1,2]},
                          "rotation":{"0":[10,20,30],"1":[-20,35,45]},"scale":[1,1.5,1]}}}}}
                    """.replace("TRAVEL", travel).replace("TURN", turn));
            JsonObject profile = json("""
                    {"version":1,"sample_hz":960,"max_contact_error":0.0001,
                     "inputs":{"render_scale":SCALE,"base_clip":"reference","node_map":{"slide":"MovingVisual"}},
                     "pivots":[{"bone":"slide","pivot_bedrock_pixels":[1,23,2]}],
                     "joints":[{"bone":"slide","kind":"prismatic","axis":"AXIS",
                       "clips":["operate"],"source_range":[0,8],"target_range":[0,2],
                       "unit":"bedrock_pixels","provenance":"synthetic fixture extent"},
                       {"bone":"slide","kind":"revolute","axis":"AXIS","clips":["operate"],
                        "source_range":[0,60],"target_range":[0,30],"unit":"degrees",
                        "limit_policy":"clamp_and_bake","provenance":"synthetic angular limits"}],
                     "bind_contacts":[{"hand_anchor":"righthand_pos","hand_point_after_arm_model":[0.2,0.7,0.1],
                       "target_node":"TARGET","target_point_local":[0.1,0.2,0.3],
                       "reference_clip":"reference","reference_time":0}],
                     "contacts":[{"clip":"operate","hand_bone":"hand","hand_anchor":"righthand_pos",
                       "hand_point_after_arm_model":[0.2,0.7,0.1],"target_node":"TARGET",
                       "target_point_local":[0.1,0.2,0.3],"interval":[0,0.15,0.85,1]}]}
                    """.replace("SCALE", variant == 0 ? "1" : "1.7").replace("AXIS", variant == 0 ? "Z" : "X").replace("TARGET", targetName));
            String geometryBefore = geometry.toString(), animationBefore = animation.toString();
            ConvertedGltfAsset asset = asset(variant);
            JsonObject rejectOvershoot = profile.deepCopy();
            rejectOvershoot.getAsJsonArray("joints").get(1).getAsJsonObject().remove("limit_policy");
            rejects(() -> AssetCalibrationBaker.bake(geometry, animation, rejectOvershoot, asset), "exceeds measured target limits");
            var first = AssetCalibrationBaker.bake(geometry, animation, profile, asset);
            var second = AssetCalibrationBaker.bake(geometry, animation, profile, asset);
            check(first.geometry().equals(second.geometry()) && first.animation().equals(second.animation())
                    && first.report().equals(second.report()), "Non-reproducible output");
            check(geometryBefore.equals(geometry.toString()) && animationBefore.equals(animation.toString()), "Source changed");
            check(first.report().get("verified_samples").getAsInt() > 400, "Missing midpoint checks");
            check(first.report().get("max_blended_contact_error_model_units").getAsDouble() < 0.0001, "Contact residual");
            var sampler = new AssetCalibrationBaker.Sampler(first.geometry(), first.animation(), asset,
                    java.util.Map.of("slide", "MovingVisual"), variant == 0 ? 1 : 1.7f, "reference");
            sampler.sample("reference", 0);
            var bindContact = new AssetCalibrationBaker.Contact("reference", "hand", "righthand_pos",
                    new org.joml.Vector3f(0.2f, 0.7f, 0.1f), targetName, new org.joml.Vector3f(0.1f, 0.2f, 0.3f), new double[4]);
            check(sampler.hand(bindContact).distance(sampler.target(bindContact)) < 0.000001,
                    "Reference-pose static grip was not calibrated");
            var referenceHand = sampler.hand(bindContact);
            sampler.sample("shoot", 0.5);
            check(referenceHand.distance(sampler.hand(bindContact)) < 0.000001, "Action without hand channels lost base fallback");
            sampler.sample("operate", 0.5);
            float travelPixels = variant == 0 ? sampler.part("slide").offsetZ * 16 : sampler.part("slide").offsetX * 16;
            check(Math.abs(travelPixels - 2) < 0.00001, "Wrong calibrated travel");
            var runtimeReference = sampler.runtimeReference(bindContact);
            var point = runtimeReference.getAsJsonArray("point_in_source_bind_space");
            var localPoint = new org.joml.Vector3f(point.get(0).getAsFloat(), point.get(1).getAsFloat(), point.get(2).getAsFloat());
            check(sampler.world(runtimeReference.get("source_bone").getAsString()).transformPosition(localPoint)
                    .distance(sampler.target(bindContact)) < 0.000001, "Runtime bind reference does not reproduce bridge target");
            check(first.report().getAsJsonArray("joints").get(1).getAsJsonObject().getAsJsonObject("driver")
                    .getAsJsonArray("clips").get(0).getAsJsonObject().get("clamped_samples").getAsInt() > 0, "Overshoot was not clamped");
            JsonObject discontinuous = animation.deepCopy();
            discontinuous.getAsJsonObject("animations").getAsJsonObject("operate").getAsJsonObject("bones")
                    .getAsJsonObject("slide").getAsJsonObject("rotation").getAsJsonObject("0.25")
                    .add("pre", JsonParser.parseString(turn));
            rejects(() -> AssetCalibrationBaker.bake(geometry, discontinuous, profile, asset), "discontinuous pre/post");
            JsonObject multiAxis = animation.deepCopy();
            multiAxis.getAsJsonObject("animations").getAsJsonObject("operate").getAsJsonObject("bones")
                    .getAsJsonObject("slide").getAsJsonObject("rotation").getAsJsonObject("0.5")
                    .getAsJsonArray("post").set(1, new com.google.gson.JsonPrimitive(1));
            rejects(() -> AssetCalibrationBaker.bake(geometry, multiAxis, profile, asset), "single-axis");
            JsonObject invalid = profile.deepCopy();
            invalid.getAsJsonArray("joints").get(0).getAsJsonObject().addProperty("axis", "XY");
            JsonObject badAxis = invalid;
            rejects(() -> AssetCalibrationBaker.bake(geometry, animation, badAxis, asset), "axis");
            invalid = profile.deepCopy();
            invalid.getAsJsonArray("contacts").get(0).getAsJsonObject().addProperty("hand_bone", "carrier");
            JsonObject targetAncestor = invalid;
            rejects(() -> AssetCalibrationBaker.bake(geometry, animation, targetAncestor, asset), "drive");
            System.out.println("PASS synthetic calibration " + variant + ": " + first.report());
        }
        visibilitySteps();
        if (args.length == 4) {
            var animation = Animations.createAnimationFromBedrock(AssetCalibrationBaker.GSON.fromJson(
                    AssetCalibrationBaker.read(Path.of(args[0])), BedrockAnimationFile.class));
            var channel = animation.stream().filter(c -> c.name.equals(args[1])).findFirst().orElseThrow()
                    .getChannels().get(args[2]).stream().filter(c -> c.type == ObjectAnimationChannel.ChannelType.SCALE).findFirst().orElseThrow();
            float time = Float.parseFloat(args[3]);
            float[] values = channel.getResult(time);
            Matrix4f matrix = new Matrix4f().scaling(values[0], values[1], values[2]);
            String outcome;
            try { GltfPreparedGeometry.requiresWindingReversal(matrix); outcome = "accepted"; }
            catch (IllegalArgumentException exception) { outcome = "rejected: " + exception.getMessage(); }
            System.out.println("ACTUAL SOURCE SCALE PROBE: path=" + args[0] + ", clip=" + args[1] + ", bone=" + args[2]
                    + ", time=" + time + ", scale=" + java.util.Arrays.toString(values) + ", determinant=" + matrix.determinant3x3()
                    + ", shared_renderer_guard=" + outcome + "; CPU parser/guard, not a GPU frame");
        }
    }

    private static void visibilitySteps() throws Exception {
        JsonObject geometry = json("""
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.visibility","texture_width":16,"texture_height":16,
                    "visible_bounds_width":2,"visible_bounds_height":2,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"root","pivot":[0,24,0]},{"name":"slide","parent":"root","pivot":[0,24,0]}]}]}
                """);
        JsonObject animation = json("""
                {"format_version":"1.8.0","animations":{"hide":{"animation_length":3,"bones":{"slide":{
                  "scale":{"1.4":[1,1,1],"1.4333":[0,0,0],"2.5333":[0,0,0],"2.5667":[1,1,1]}}}}}}
                """);
        JsonObject profile = json("""
                {"version":1,"sample_hz":120,"max_contact_error":0.0001,
                  "inputs":{"render_scale":1,"node_map":{"slide":"MovingVisual"}},
                  "visibility_steps":[{"clip":"hide","bone":"slide"}]}
                """);
        ConvertedGltfAsset asset = asset(0);
        var source = new AssetCalibrationBaker.Sampler(geometry, animation, asset, java.util.Map.of("slide", "MovingVisual"), 1);
        var originalRenderer = renderer(asset, source);
        source.sample("hide", 1.43329);
        check(source.part("slide").xScale > 0 && source.part("slide").xScale < 0.001, "Missing near-zero red fixture");
        rejects(() -> originalRenderer.preparedAtTime(0), "singular");
        var result = AssetCalibrationBaker.bake(geometry, animation, profile, asset);
        var stepped = new AssetCalibrationBaker.Sampler(result.geometry(), result.animation(), asset, java.util.Map.of("slide", "MovingVisual"), 1);
        var fixedRenderer = renderer(asset, stepped);
        var channel = stepped.animations.get("hide").getChannels().get("slide").getFirst();
        for (double boundary : new double[]{1.4, 1.4333, 2.5333, 2.5667}) {
            float key = (float) boundary;
            for (float time : new float[]{Math.nextDown(key), key, Math.nextUp(key)}) {
                stepped.rig.cleanAnimationTransform();
                channel.update(time, false);
                float scale = stepped.part("slide").xScale;
                check(scale == 0 || scale == 1, "Non-boolean scale at boundary");
                check(fixedRenderer.preparedAtTime(0).size() == (scale == 0 ? 0 : 1), "Renderer did not preserve exact visibility state");
            }
        }
        stepped.sample("hide", 1.43329);
        check(stepped.part("slide").xScale == 1 && fixedRenderer.preparedAtTime(0).size() == 1, "Before hide boundary must remain visible");
        for (String invalidValue : new String[]{"[0,1,1]", "[0.5,0.5,0.5]", "1", "{\"post\":[0,0,0]}", "[\"0\",0,0]"}) {
            JsonObject invalid = animation.deepCopy();
            invalid.getAsJsonObject("animations").getAsJsonObject("hide").getAsJsonObject("bones")
                    .getAsJsonObject("slide").getAsJsonObject("scale").add("1.4333", JsonParser.parseString(invalidValue));
            rejects(() -> AssetCalibrationBaker.bake(geometry, invalid, profile, asset), "Visibility");
        }
        JsonObject noSelection = profile.deepCopy();
        noSelection.remove("visibility_steps");
        check(AssetCalibrationBaker.bake(geometry, animation, noSelection, asset).animation().equals(animation), "Unselected scale changed");
        System.out.println("PASS visibility boundary red/green: original near-zero rejects; exact 0/1 step restores same renderer; "
                + result.report().get("visibility_steps"));
    }

    private static GltfGunBodyRenderer renderer(ConvertedGltfAsset asset, AssetCalibrationBaker.Sampler sampler) {
        JsonObject config = json("""
                {"type":"gltf","location":"fixture:models/gltf/calibration.gltf","scale":1,"node_map":{"slide":"MovingVisual"}}
                """);
        var parsed = AssetCalibrationBaker.GSON.fromJson(config, GunRenderModelConfig.class);
        return new GltfGunBodyRenderer(Identifier.parse("fixture:models/gltf/calibration.gltf"), asset,
                new GltfModelManager(), 0, parsed, sampler.rig);
    }

    private static ConvertedGltfAsset asset(int variant) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0,0,0, 1,0,0, 0,1,0}) buffer.putFloat(value);
        String data = Base64.getEncoder().encodeToString(buffer.array());
        String text = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":NODES,
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
                 "buffers":[{"byteLength":36,"uri":"data:application/octet-stream;base64,DATA"}],
                 "bufferViews":[{"buffer":0,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",
                    "min":[0,0,0],"max":[1,1,0]}]}
                """.replace("DATA", data).replace("NODES", variant == 0
                        ? "[{\"name\":\"MovingVisual\",\"mesh\":0,\"translation\":[0.2,0.1,-0.3]}]"
                        : "[{\"name\":\"MovingVisual\",\"children\":[1],\"translation\":[-0.5,0.4,0.1]},"
                          + "{\"name\":\"ContactVisual\",\"mesh\":0,\"translation\":[0,0.05,0.03]}]");
        return new JgltfRuntimeConverter().convert(new JgltfModelLoader().load(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Expected rejection: " + message); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains(message), "Wrong rejection: " + expected); }
    }
}
