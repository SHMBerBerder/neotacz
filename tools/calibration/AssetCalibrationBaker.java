package com.tacz.guns.client.model.gltf.render;

import com.tacz.guns.client.model.gltf.GltfRuntimePolicy;

import com.google.gson.*;
import com.tacz.guns.api.client.animation.Animations;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.gltf.convert.ConvertedGltfAsset;
import com.tacz.guns.client.model.gltf.convert.JgltfRuntimeConverter;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.runtime.GltfSceneTransforms;
import com.tacz.guns.client.resource.pojo.animation.bedrock.AnimationKeyframes;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimationFile;
import com.tacz.guns.client.resource.pojo.animation.bedrock.SoundEffectKeyframes;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.pojo.model.CubesItem;
import com.tacz.guns.client.resource.serialize.AnimationKeyframesSerializer;
import com.tacz.guns.client.resource.serialize.SoundEffectKeyframesSerializer;
import com.tacz.guns.client.resource.serialize.Vector3fSerializer;
import com.tacz.guns.resource.serialize.IdentifierSerializer;
import com.tacz.guns.util.math.MathUtil;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline visual calibration; outputs normal Bedrock resources, never a runtime constraint system. */
public final class AssetCalibrationBaker {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .registerTypeAdapter(Identifier.class, new IdentifierSerializer())
            .registerTypeAdapter(CubesItem.class, new CubesItem.Deserializer())
            .registerTypeAdapter(Vector3f.class, new Vector3fSerializer())
            .registerTypeAdapter(AnimationKeyframes.class, new AnimationKeyframesSerializer())
            .registerTypeAdapter(SoundEffectKeyframes.class, new SoundEffectKeyframesSerializer()).create();

    public static void main(String[] args) throws Exception {
        GltfRuntimePolicy.rejectRetiredOverride();
        require(args.length == 2 || args.length == 4,
                "Expected profile JSON and output directory, optionally followed by encoded-image MiB and model-images MiB");
        var budgets = args.length == 2 ? GltfRuntimePolicy.DEFAULT
                : GltfRuntimePolicy.fromMiB(Long.parseLong(args[2]), Long.parseLong(args[3]), 512);
        Path profilePath = Path.of(args[0]).toAbsolutePath().normalize();
        JsonObject profile = read(profilePath);
        JsonObject inputs = profile.getAsJsonObject("inputs");
        Path base = profilePath.getParent();
        Path geometryPath = resolve(base, string(inputs, "geometry"));
        Path animationPath = resolve(base, string(inputs, "animation"));
        Path gltfPath = resolve(base, string(inputs, "gltf"));
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        require(!output.resolve("geometry.json").equals(geometryPath)
                && !output.resolve("animation.json").equals(animationPath), "Do not overwrite source inputs");
        ConvertedGltfAsset asset;
        try (var stream = Files.newInputStream(gltfPath)) {
            var loaded = new JgltfModelLoader().load(stream, uri -> {
                Path resource = gltfPath.getParent().resolve(uri).normalize();
                require(resource.startsWith(gltfPath.getParent()), "glTF reference escapes its directory");
                return ByteBuffer.wrap(Files.readAllBytes(resource));
            });
            asset = new JgltfRuntimeConverter().convert(loaded, budgets);
        }
        Result result = bake(read(geometryPath), read(animationPath), profile, asset);
        Files.createDirectories(output);
        write(output.resolve("geometry.json"), result.geometry);
        write(output.resolve("animation.json"), result.animation);
        write(output.resolve("report.json"), result.report);
        System.out.println("PASS calibration: " + result.report.get("verified_samples") + " samples; max error "
                + result.report.get("max_blended_contact_error_model_units"));
    }

    static Result bake(JsonObject sourceGeometry, JsonObject sourceAnimation, JsonObject profile,
                       ConvertedGltfAsset asset) {
        require(profile.get("version").getAsInt() == 1, "Unsupported calibration profile version");
        double hz = number(profile, "sample_hz");
        double tolerance = number(profile, "max_contact_error");
        require(hz >= 30 && hz <= 1000 && tolerance > 0, "Invalid sample rate or tolerance");
        JsonObject geometry = sourceGeometry.deepCopy();
        JsonObject animation = sourceAnimation.deepCopy();
        JsonObject inputs = profile.getAsJsonObject("inputs");
        float renderScale = (float) number(inputs, "render_scale");
        String baseClip = inputs.has("base_clip") ? string(inputs, "base_clip") : null;
        Map<String, String> mappings = new LinkedHashMap<>();
        inputs.getAsJsonObject("node_map").entrySet().forEach(e -> mappings.put(e.getKey(), e.getValue().getAsString()));
        for (JsonElement item : array(profile, "pivots")) {
            JsonObject pivot = item.getAsJsonObject();
            bone(geometry, string(pivot, "bone")).add("pivot", vectorJson(vector(pivot, "pivot_bedrock_pixels")));
        }
        List<JsonObject> driverReports = new ArrayList<>();
        for (JsonElement item : array(profile, "joints")) {
            JsonObject joint = item.getAsJsonObject();
            driverReports.add(calibrateJoint(animation, joint, hz));
        }
        JsonArray visibilityReports = convertVisibilitySteps(animation, profile, hz);
        for (JsonElement item : array(profile, "bind_contacts")) {
            JsonObject binding = item.getAsJsonObject();
            Contact contact = contact(binding, false);
            Sampler sampler = new Sampler(geometry, animation, asset, mappings, renderScale, baseClip);
            if (binding.has("reference_clip")) {
                String reference = string(binding, "reference_clip");
                require(sampler.animations.containsKey(reference), "Missing reference clip " + reference);
                sampler.sample(reference, number(binding, "reference_time"));
            }
            require(sampler.part(contact.anchor).children.isEmpty(), "Bind-contact anchor must be a leaf");
            Vector3f delta = sampler.target(contact).sub(sampler.hand(contact));
            Matrix4f parent = sampler.parentWorld(contact.anchor);
            requireInvertible(parent, "bind-contact parent");
            parent.invert().transformDirection(delta);
            JsonObject anchor = bone(geometry, contact.anchor);
            Vector3f pivot = vector(anchor, "pivot");
            pivot.add(delta.x * 16, -delta.y * 16, delta.z * 16);
            anchor.add("pivot", vectorJson(pivot));
        }

        List<Contact> contacts = new ArrayList<>();
        for (JsonElement item : array(profile, "contacts")) contacts.add(contact(item.getAsJsonObject(), true));
        Sampler original = new Sampler(geometry, animation, asset, mappings, renderScale, baseClip);
        for (Contact contact : contacts) {
            require(original.animations.containsKey(contact.clip), "Missing contact clip: " + contact.clip);
            List<BedrockPart> anchorPath = original.path(contact.anchor);
            require(anchorPath.contains(original.part(contact.bone)), "Hand bone must contain its anchor");
            for (String source : mappings.keySet()) require(!original.path(source).contains(original.part(contact.bone)),
                    "Contact hand bone cannot also drive its target or another mapped part");
        }
        for (int a = 0; a < contacts.size(); a++) for (int b = a + 1; b < contacts.size(); b++) {
            Contact x = contacts.get(a), y = contacts.get(b);
            require(!x.clip.equals(y.clip) || !x.bone.equals(y.bone)
                            || Math.min(x.times[3], y.times[3]) <= Math.max(x.times[0], y.times[0]),
                    "Overlapping constraints on one hand bone are ambiguous");
        }

        Map<String, TreeSet<Double>> timesByClip = new LinkedHashMap<>();
        Map<String, Set<String>> handsByClip = new LinkedHashMap<>();
        for (Contact contact : contacts) {
            timesByClip.computeIfAbsent(contact.clip, clip -> sampleTimes(animation, clip, hz));
            for (double time : contact.times) timesByClip.get(contact.clip).add(time);
            handsByClip.computeIfAbsent(contact.clip, clip -> new LinkedHashSet<>()).add(contact.bone);
            require(contact.times[3] <= duration(animation, contact.clip) + 1e-6, "Contact exceeds clip duration");
        }
        JsonObject baked = animation.deepCopy();
        for (var entry : timesByClip.entrySet()) {
            String clip = entry.getKey();
            Map<String, JsonObject> channels = new LinkedHashMap<>();
            handsByClip.get(clip).forEach(hand -> channels.put(hand, new JsonObject()));
            for (double time : entry.getValue()) {
                original.sample(clip, time);
                for (Contact contact : contacts) {
                    if (!contact.clip.equals(clip)) continue;
                    double weight = influence(contact, time);
                    if (weight == 0) continue;
                    Vector3f delta = original.target(contact).sub(original.hand(contact)).mul((float) weight);
                    Matrix4f parent = original.parentWorld(contact.bone);
                    requireInvertible(parent, "animated hand parent");
                    parent.invert().transformDirection(delta);
                    BedrockPart hand = original.part(contact.bone);
                    hand.offsetX += delta.x;
                    hand.offsetY += delta.y;
                    hand.offsetZ += delta.z;
                }
                for (String handName : channels.keySet()) {
                    BedrockPart hand = original.part(handName);
                    channels.get(handName).add(timeKey(time), vectorJson(new Vector3f(
                            hand.offsetX * 16, -hand.offsetY * 16, hand.offsetZ * 16)));
                }
            }
            JsonObject bones = clip(baked, clip).getAsJsonObject("bones");
            channels.forEach((name, channel) -> {
                if (!bones.has(name)) bones.add(name, new JsonObject());
                bones.getAsJsonObject(name).add("position", channel);
            });
        }

        Sampler calibrated = new Sampler(geometry, baked, asset, mappings, renderScale, baseClip);
        JsonArray jointReport = verifyJoints(calibrated, baked, profile, hz);
        for (int i = 0; i < driverReports.size(); i++) jointReport.get(i).getAsJsonObject().add("driver", driverReports.get(i));
        JsonArray bindReports = new JsonArray();
        for (JsonElement item : array(profile, "bind_contacts")) {
            JsonObject binding = item.getAsJsonObject();
            Contact contact = contact(binding, false);
            calibrated.rig.cleanAnimationTransform();
            if (binding.has("reference_clip")) calibrated.sample(string(binding, "reference_clip"), number(binding, "reference_time"));
            double error = calibrated.hand(contact).distance(calibrated.target(contact));
            require(error <= tolerance, "Static reference contact exceeds tolerance: " + contact.anchor + " / " + error);
            JsonObject row = binding.deepCopy();
            row.addProperty("reference_error_model_units", error);
            row.add("runtime_reference", calibrated.runtimeReference(contact));
            bindReports.add(row);
        }
        JsonArray contactReports = new JsonArray();
        int verifiedSamples = 0;
        double maximum = 0;
        for (Contact contact : contacts) {
            TreeSet<Double> checks = new TreeSet<>(timesByClip.get(contact.clip));
            Double previous = null;
            for (double time : timesByClip.get(contact.clip)) {
                if (previous != null) checks.add((previous + time) / 2);
                previous = time;
            }
            double maxError = 0, maxHeldError = 0, squared = 0;
            int count = 0, heldCount = 0;
            for (double time : checks) {
                double weight = influence(contact, time);
                if (time < contact.times[0] || time > contact.times[3]) continue;
                original.sample(contact.clip, time);
                Vector3f desired = original.hand(contact);
                if (weight > 0) desired.lerp(original.target(contact), (float) weight);
                calibrated.sample(contact.clip, time);
                double error = calibrated.hand(contact).distance(desired);
                maxError = Math.max(maxError, error);
                squared += error * error;
                count++;
                if (weight == 1) {
                    maxHeldError = Math.max(maxHeldError, calibrated.hand(contact).distance(calibrated.target(contact)));
                    heldCount++;
                }
            }
            JsonObject row = new JsonObject();
            row.addProperty("clip", contact.clip);
            row.addProperty("hand_bone", contact.bone);
            row.addProperty("target_node", contact.target);
            row.addProperty("samples", count);
            row.addProperty("held_samples", heldCount);
            row.addProperty("max_blended_error_model_units", maxError);
            row.addProperty("rms_blended_error_model_units", Math.sqrt(squared / Math.max(count, 1)));
            row.addProperty("max_held_error_model_units", maxHeldError);
            row.add("runtime_reference", calibrated.runtimeReference(contact));
            contactReports.add(row);
            require(maxError <= tolerance, "Contact verification exceeded tolerance: " + row);
            verifiedSamples += count;
            maximum = Math.max(maximum, maxError);
        }
        JsonObject report = new JsonObject();
        report.addProperty("status", "PASS");
        report.addProperty("scope", "Bedrock parser and bridge; isolated clips, not runtime track-transition proof");
        report.addProperty("sample_hz", hz);
        report.addProperty("verified_samples", verifiedSamples);
        report.addProperty("max_blended_contact_error_model_units", maximum);
        report.add("joints", jointReport);
        report.add("bind_contacts", bindReports);
        report.add("visibility_steps", visibilityReports);
        report.add("contacts", contactReports);
        return new Result(geometry, baked, report);
    }

    private static JsonArray convertVisibilitySteps(JsonObject animation, JsonObject profile, double hz) {
        JsonArray report = new JsonArray();
        Set<String> selected = new HashSet<>();
        for (JsonElement item : array(profile, "visibility_steps")) {
            JsonObject selector = item.getAsJsonObject();
            String clipName = string(selector, "clip"), boneName = string(selector, "bone");
            require(selected.add(clipName + "\0" + boneName), "Duplicate visibility step selection");
            JsonObject bones = clip(animation, clipName).getAsJsonObject("bones");
            require(bones != null && bones.has(boneName) && bones.getAsJsonObject(boneName).has("scale"), "Missing visibility scale channel");
            JsonElement input = bones.getAsJsonObject(boneName).get("scale");
            require(input.isJsonObject() && !input.getAsJsonObject().isEmpty(), "Visibility steps require a numeric-key object");
            TreeMap<Double, Integer> states = new TreeMap<>();
            for (var entry : input.getAsJsonObject().entrySet()) {
                double time = Double.parseDouble(entry.getKey());
                require(Double.isFinite(time) && time >= 0 && time <= duration(animation, clipName), "Invalid visibility key time");
                require(entry.getValue().isJsonArray(), "Visibility steps accept only numeric array keys, not existing pre/post objects");
                JsonArray values = entry.getValue().getAsJsonArray();
                require(values.size() == 3, "Visibility scale must have three axes");
                int state = -1;
                for (JsonElement value : values) {
                    require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Visibility states must be numeric");
                    double number = value.getAsDouble();
                    require(number == 0 || number == 1, "Visibility states must be exact zero or one");
                    if (state == -1) state = (int) number;
                    require(state == number, "Visibility scale must be uniform across all axes");
                }
                require(states.put(time, state) == null, "Duplicate numeric visibility key time");
            }
            JsonObject output = new JsonObject();
            int previous = states.firstEntry().getValue(), transitions = 0;
            float lastTime = -1;
            for (var entry : states.entrySet()) {
                require((float) (double) entry.getKey() > lastTime, "Visibility key times collide in runtime float precision");
                lastTime = entry.getKey().floatValue();
                int current = entry.getValue();
                JsonObject key = new JsonObject();
                key.add("pre", vectorJson(new Vector3f(previous)));
                key.add("post", vectorJson(new Vector3f(current)));
                key.addProperty("lerp_mode", "linear");
                output.add(timeKey(entry.getKey()), key);
                if (current != previous) transitions++;
                previous = current;
            }
            bones.getAsJsonObject(boneName).add("scale", output);
            ObjectAnimation object = Animations.createAnimationFromBedrock(GSON.fromJson(animation, BedrockAnimationFile.class))
                    .stream().filter(c -> c.name.equals(clipName)).findFirst().orElseThrow();
            ObjectAnimationChannel channel = object.getChannels().get(boneName).stream()
                    .filter(c -> c.type == ObjectAnimationChannel.ChannelType.SCALE).findFirst().orElseThrow();
            TreeSet<Double> times = withMidpoints(sampleTimes(animation, clipName, hz));
            for (double time : states.keySet()) {
                float boundary = (float) time;
                times.add((double) boundary);
                if (boundary > 0) times.add((double) Math.nextDown(boundary));
                times.add((double) Math.nextUp(boundary));
            }
            for (double time : times) {
                float[] values = channel.getResult((float) time);
                require((values[0] == 0 || values[0] == 1) && values[0] == values[1] && values[1] == values[2],
                        "Visibility conversion generated a non-boolean interpolated scale");
            }
            JsonObject row = selector.deepCopy();
            row.addProperty("keys", states.size());
            row.addProperty("transitions", transitions);
            row.addProperty("verified_samples", times.size());
            row.addProperty("encoding", "Bedrock LINEAR pre=previous state, post=current state");
            report.add(row);
        }
        return report;
    }

    private static JsonArray verifyJoints(Sampler sampler, JsonObject animation, JsonObject profile, double hz) {
        JsonArray report = new JsonArray();
        for (JsonElement item : array(profile, "joints")) {
            JsonObject joint = item.getAsJsonObject();
            JsonObject row = joint.deepCopy();
            JsonArray clips = new JsonArray();
            int axis = "XYZ".indexOf(string(joint, "axis"));
            boolean translation = string(joint, "kind").equals("prismatic");
            var type = translation ? ObjectAnimationChannel.ChannelType.TRANSLATION : ObjectAnimationChannel.ChannelType.ROTATION;
            double[] limits = numbers(joint, "target_range", 2);
            for (JsonElement clipName : joint.getAsJsonArray("clips")) {
                String name = clipName.getAsString();
                ObjectAnimationChannel channel = sampler.animations.get(name).getChannels().get(string(joint, "bone"))
                        .stream().filter(c -> c.type == type).findFirst().orElseThrow();
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                TreeSet<Double> times = withMidpoints(sampleTimes(animation, name, hz));
                times.addAll(sampleTimes(animation, name, hz * 2));
                for (double time : times) {
                    float[] values = channel.getResult((float) time);
                    if (values.length == 4) values = MathUtil.toEulerAngles(values);
                    double value = translation ? values[axis] * 16.0 : Math.toDegrees(values[axis]);
                    require(Double.isFinite(value), "Non-finite mechanical sample");
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                require(min >= limits[0] - 1e-4 && max <= limits[1] + 1e-4,
                        "Interpolated joint exceeds measured target limits: " + name + " / " + string(joint, "bone")
                                + " sampled [" + min + ", " + max + "] versus " + Arrays.toString(limits));
                JsonObject sample = new JsonObject();
                sample.addProperty("clip", name);
                sample.addProperty("samples", times.size());
                sample.addProperty("sampled_min", min);
                sample.addProperty("sampled_max", max);
                clips.add(sample);
            }
            row.add("sampled_clips", clips);
            report.add(row);
        }
        return report;
    }

    private static JsonObject calibrateJoint(JsonObject animation, JsonObject joint, double hz) {
        String kind = string(joint, "kind");
        require(kind.equals("prismatic") || kind.equals("revolute"), "Unsupported joint kind");
        String channel = kind.equals("prismatic") ? "position" : "rotation";
        require(string(joint, "unit").equals(kind.equals("prismatic") ? "bedrock_pixels" : "degrees"),
                "Joint range unit does not match joint kind");
        String axis = string(joint, "axis");
        require(axis.length() == 1 && "XYZ".contains(axis), "Joint axis must be X, Y, or Z");
        int component = "XYZ".indexOf(axis);
        double[] from = numbers(joint, "source_range", 2), to = numbers(joint, "target_range", 2);
        require(from[1] > from[0] && to[1] > to[0], "Joint ranges must be finite and increasing");
        require(!string(joint, "provenance").isBlank(), "Joint measurement provenance is required");
        String policy = joint.has("limit_policy") ? string(joint, "limit_policy") : "reject";
        require(policy.equals("reject") || policy.equals("clamp_and_bake"), "Unsupported joint limit_policy");
        JsonObject driverReport = new JsonObject();
        driverReport.addProperty("limit_policy", policy);
        JsonArray driverClips = new JsonArray();
        for (JsonElement clipName : joint.getAsJsonArray("clips")) {
            JsonObject bones = clip(animation, clipName.getAsString()).getAsJsonObject("bones");
            String name = string(joint, "bone");
            require(bones.has(name) && bones.getAsJsonObject(name).has(channel), "Missing mechanical channel " + name);
            if (policy.equals("reject")) {
                transformChannel(bones.getAsJsonObject(name).get(channel), component, from, to);
            } else {
                // A public scalar joint limit bounds the driver, not arbitrary multi-axis authored poses.
                transformChannel(bones.getAsJsonObject(name).get(channel).deepCopy(), component, from, from);
                var type = kind.equals("prismatic") ? ObjectAnimationChannel.ChannelType.TRANSLATION : ObjectAnimationChannel.ChannelType.ROTATION;
                ObjectAnimation sourceClip = Animations.createAnimationFromBedrock(GSON.fromJson(animation, BedrockAnimationFile.class))
                        .stream().filter(c -> c.name.equals(clipName.getAsString())).findFirst().orElseThrow();
                ObjectAnimationChannel source = sourceClip.getChannels().get(name).stream().filter(c -> c.type == type).findFirst().orElseThrow();
                for (float[] key : source.content.values) {
                    require(key.length == 3 || key.length == 6, "Bounded driver requires a scalar-axis Euler/translation channel");
                    if (key.length == 6) for (int i = 0; i < 3; i++) require(key[i] == key[i + 3], "Bounded driver rejects discontinuous pre/post keys");
                    for (int i = 0; i < key.length; i++) if (i % 3 != component) require(key[i] == 0, "Bounded driver must be single-axis");
                }
                JsonObject output = new JsonObject();
                int clamped = 0;
                double overshoot = 0;
                TreeSet<Double> times = sampleTimes(animation, clipName.getAsString(), hz);
                for (double time : times) {
                    float[] values = source.getResult((float) time);
                    require(values.length == 3, "Bounded driver requires a scalar-axis sample");
                    double value = kind.equals("prismatic") ? values[component] * 16.0 : Math.toDegrees(values[component]);
                    require(Double.isFinite(value), "Non-finite mechanical driver sample");
                    double excess = Math.max(0, Math.max(from[0] - value, value - from[1]));
                    if (excess > 0) clamped++;
                    overshoot = Math.max(overshoot, excess);
                    double normalized = Math.clamp((value - from[0]) / (from[1] - from[0]), 0, 1);
                    Vector3f position = new Vector3f();
                    position.setComponent(component, (float) (to[0] + normalized * (to[1] - to[0])));
                    output.add(timeKey(time), vectorJson(position));
                }
                bones.getAsJsonObject(name).add(channel, output);
                JsonObject row = new JsonObject();
                row.addProperty("clip", clipName.getAsString());
                row.addProperty("driver_samples", times.size());
                row.addProperty("clamped_samples", clamped);
                row.addProperty("observed_source_max_overshoot", overshoot);
                driverClips.add(row);
            }
        }
        driverReport.add("clips", driverClips);
        return driverReport;
    }

    private static void transformChannel(JsonElement value, int axis, double[] from, double[] to) {
        if (value.isJsonArray()) {
            JsonArray vector = value.getAsJsonArray();
            require(vector.size() == 3, "Mechanical channel must contain three numeric components");
            double source = vector.get(axis).getAsDouble();
            require(Double.isFinite(source) && source >= from[0] - 1e-5 && source <= from[1] + 1e-5,
                    "Mechanical key outside declared source range: " + source);
            double mapped = to[0] + (source - from[0]) / (from[1] - from[0]) * (to[1] - to[0]);
            vector.set(axis, new JsonPrimitive(mapped));
        } else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (!entry.getKey().equals("lerp_mode")) transformChannel(entry.getValue(), axis, from, to);
            }
        } else throw new IllegalArgumentException("Molang/scalar mechanical channels are not calibrated");
    }

    private static Contact contact(JsonObject json, boolean animated) {
        String anchor = string(json, "hand_anchor");
        double[] times = animated ? numbers(json, "interval", 4) : new double[4];
        require(times[0] >= 0 && times[0] <= times[1] && times[1] <= times[2] && times[2] <= times[3],
                "Contact times must be ordered");
        return new Contact(animated ? string(json, "clip") : "", animated ? string(json, "hand_bone") : anchor,
                anchor, vector(json, "hand_point_after_arm_model"), string(json, "target_node"),
                vector(json, "target_point_local"), times);
    }

    private static double influence(Contact contact, double time) {
        double[] t = contact.times;
        if (time < t[0] || time > t[3]) return 0;
        if (time >= t[1] && time <= t[2]) return 1;
        double x = time < t[1] ? (time - t[0]) / (t[1] - t[0]) : (t[3] - time) / (t[3] - t[2]);
        return x * x * (3 - 2 * x);
    }

    private static TreeSet<Double> sampleTimes(JsonObject animation, String clipName, double hz) {
        double end = duration(animation, clipName);
        require(end >= 0 && end <= 120, "Clip duration out of calibration range");
        TreeSet<Double> times = new TreeSet<>();
        for (int i = 0; i <= Math.ceil(end * hz); i++) times.add(Math.min(end, i / hz));
        for (var bone : clip(animation, clipName).getAsJsonObject("bones").entrySet()) {
            for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                if (!channel.getValue().isJsonObject()) continue;
                for (String key : channel.getValue().getAsJsonObject().keySet()) {
                    double time = Double.parseDouble(key);
                    require(Double.isFinite(time) && time >= 0 && time <= end + 1e-6, "Key outside declared clip duration");
                    times.add(time);
                }
            }
        }
        return times;
    }

    private static TreeSet<Double> withMidpoints(TreeSet<Double> times) {
        TreeSet<Double> result = new TreeSet<>(times);
        Double previous = null;
        for (double time : times) {
            if (previous != null) result.add((previous + time) / 2);
            previous = time;
        }
        return result;
    }

    static final class Sampler {
        final BedrockGunModel rig;
        final Map<String, ObjectAnimation> animations = new LinkedHashMap<>();
        final ConvertedGltfAsset asset;
        final GltfNodeMapBridge bridge;
        final Matrix4f basis;
        final String baseClip;
        final Map<String, String> sourceByTarget = new LinkedHashMap<>();
        final Map<String, Matrix4f> sourceBindWorld = new LinkedHashMap<>();
        final Matrix4f[] targetBindWorld;

        Sampler(JsonObject geometry, JsonObject animation, ConvertedGltfAsset asset,
                Map<String, String> mappings, float scale) {
            this(geometry, animation, asset, mappings, scale, null);
        }

        Sampler(JsonObject geometry, JsonObject animation, ConvertedGltfAsset asset,
                Map<String, String> mappings, float scale, String baseClip) {
            rig = new BedrockGunModel(GSON.fromJson(geometry, BedrockModelPOJO.class), BedrockVersion.NEW);
            this.asset = asset;
            bridge = GltfNodeMapBridge.create(asset, mappings, Set.of(), rig, scale, null);
            basis = new Matrix4f().scaling(-scale, -scale, scale);
            this.baseClip = baseClip;
            mappings.forEach((source, target) -> {
                sourceByTarget.put(target, source);
                sourceBindWorld.put(source, world(source));
            });
            targetBindWorld = GltfSceneTransforms.computeWorldTransforms(asset.runtimeScene());
            for (ObjectAnimation clip : Animations.createAnimationFromBedrock(GSON.fromJson(animation, BedrockAnimationFile.class))) {
                clip.applyAnimationListeners(rig);
                animations.put(clip.name, clip);
            }
            require(baseClip == null || animations.containsKey(baseClip), "Missing base clip " + baseClip);
        }

        void sample(String clip, double time) {
            rig.cleanAnimationTransform();
            if (baseClip != null && !baseClip.equals(clip)) animations.get(baseClip).update(false, 0);
            animations.get(clip).update(false, (float) (time * 1e9));
        }

        List<BedrockPart> path(String name) {
            List<BedrockPart> path = rig.getNodePath(name);
            require(path != null && path.size() > 1 && "root".equals(path.getFirst().name), "Missing/root-relative bone " + name);
            return path.subList(1, path.size());
        }

        BedrockPart part(String name) {
            require(rig.hasUniqueNodeName(name), "Missing or ambiguous bone: " + name);
            return rig.getNode(name);
        }

        Matrix4f world(String name) {
            Matrix4f world = new Matrix4f();
            for (BedrockPart part : path(name)) part.translateAndRotateAndScale(world);
            require(world.isFinite(), "Non-finite Bedrock world");
            return world;
        }

        Matrix4f parentWorld(String name) {
            Matrix4f world = new Matrix4f();
            List<BedrockPart> path = path(name);
            for (int i = 0; i < path.size() - 1; i++) path.get(i).translateAndRotateAndScale(world);
            return world;
        }

        Vector3f hand(Contact contact) {
            return world(contact.anchor).rotateZ((float) Math.PI).transformPosition(new Vector3f(contact.handPoint));
        }

        Vector3f target(Contact contact) {
            int index = targetIndex(contact.target);
            Matrix4f world = GltfGunBodyPose.sample(asset, null, 0, bridge.snapshotWorldOverrides()).worldTransforms()[index];
            requireInvertible(world, "active contact target (exclude hidden intervals)");
            return new Matrix4f(basis).mul(world).transformPosition(new Vector3f(contact.targetPoint));
        }

        JsonObject runtimeReference(Contact contact) {
            int target = targetIndex(contact.target);
            int ancestor = target;
            String source = null;
            while (ancestor >= 0) {
                source = sourceByTarget.get(asset.runtimeScene().nodes().get(ancestor).name());
                if (source != null) break;
                int parent = -1;
                for (int i = 0; i < asset.runtimeScene().nodes().size(); i++) {
                    for (int child : asset.runtimeScene().nodes().get(i).children()) if (child == ancestor) parent = i;
                }
                ancestor = parent;
            }
            require(source != null, "Runtime reference requires a mapped target ancestor: " + contact.target);
            Matrix4f inverse = new Matrix4f(sourceBindWorld.get(source));
            requireInvertible(inverse, "source bind frame");
            Vector3f point = inverse.invert().mul(basis).mul(targetBindWorld[target]).transformPosition(new Vector3f(contact.targetPoint));
            JsonObject result = new JsonObject();
            result.addProperty("source_bone", source);
            result.add("point_in_source_bind_space", vectorJson(point));
            return result;
        }

        private int targetIndex(String name) {
            int index = -1;
            for (int i = 0; i < asset.runtimeScene().nodes().size(); i++) {
                if (!name.equals(asset.runtimeScene().nodes().get(i).name())) continue;
                require(index == -1, "Ambiguous target node " + name);
                index = i;
            }
            require(index != -1, "Missing target node " + name);
            return index;
        }
    }

    static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
    private static void write(Path path, JsonObject value) throws IOException { Files.writeString(path, GSON.toJson(value) + "\n"); }
    private static Path resolve(Path base, String value) { return base.resolve(value).toAbsolutePath().normalize(); }
    private static JsonArray array(JsonObject parent, String key) { return parent.has(key) ? parent.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static double number(JsonObject object, String key) {
        double value = object.get(key).getAsDouble();
        require(Double.isFinite(value), "Non-finite " + key);
        return value;
    }
    private static double[] numbers(JsonObject object, String key, int count) {
        JsonArray values = object.getAsJsonArray(key);
        require(values.size() == count, "Wrong vector size: " + key);
        double[] result = new double[count];
        for (int i = 0; i < count; i++) {
            result[i] = values.get(i).getAsDouble();
            require(Double.isFinite(result[i]), "Non-finite vector: " + key);
        }
        return result;
    }
    private static Vector3f vector(JsonObject object, String key) {
        double[] values = numbers(object, key, 3);
        Vector3f result = new Vector3f((float) values[0], (float) values[1], (float) values[2]);
        require(result.isFinite(), "Vector outside float range: " + key);
        return result;
    }
    private static JsonArray vectorJson(Vector3f vector) {
        require(vector.isFinite(), "Non-finite generated position");
        JsonArray result = new JsonArray();
        result.add(vector.x); result.add(vector.y); result.add(vector.z);
        return result;
    }
    private static JsonObject bone(JsonObject geometry, String name) {
        JsonObject found = null;
        for (JsonElement entry : geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")) {
            JsonObject bone = entry.getAsJsonObject();
            if (!name.equals(string(bone, "name"))) continue;
            require(found == null, "Ambiguous bone: " + name);
            found = bone;
        }
        require(found != null, "Missing bone: " + name);
        return found;
    }
    private static JsonObject clip(JsonObject animation, String name) {
        JsonObject clips = animation.getAsJsonObject("animations");
        require(clips.has(name), "Missing clip: " + name);
        return clips.getAsJsonObject(name);
    }
    private static double duration(JsonObject animation, String name) { return number(clip(animation, name), "animation_length"); }
    private static String timeKey(double time) { return BigDecimal.valueOf(time).stripTrailingZeros().toPlainString(); }
    private static void requireInvertible(Matrix4f matrix, String role) {
        require(matrix.isFinite() && Math.abs(matrix.determinant3x3()) > 1e-8, "Invalid " + role);
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    record Contact(String clip, String bone, String anchor, Vector3f handPoint, String target, Vector3f targetPoint, double[] times) { }
    record Result(JsonObject geometry, JsonObject animation, JsonObject report) { }
}
