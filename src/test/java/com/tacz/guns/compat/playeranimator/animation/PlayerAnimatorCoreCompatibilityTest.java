package com.tacz.guns.compat.playeranimator.animation;

import com.google.gson.JsonParser;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.AnimationFormat;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.gson.AnimationSerializing;
import dev.kosmx.playerAnim.core.util.Vec3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAnimatorCoreCompatibilityTest {
    @Test
    void allBundledLegacyClipsDecodeAndRemainFiniteAtStartLoopBoundaryAndStop() throws IOException {
        Map<String, Integer> legacyClipCounts = Map.of("rifle_default", 24, "pistol_default", 23, "minigun", 13);
        for (var entry : legacyClipCounts.entrySet()) {
            String name = entry.getKey();
            String path = "/assets/tacz/custom/tacz_default_gun/assets/tacz/player_animator/" + name + ".player_animation.json";
            try (var input = getClass().getResourceAsStream(path)) {
                assertNotNull(input, path);
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var sourceNames = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("animations").keySet();
                var clips = AnimationSerializing.deserializeAnimation(new StringReader(json));
                assertEquals(entry.getValue().intValue(), sourceNames.size(), name);
                assertEquals(entry.getValue().intValue(), clips.size(), name);
                var decodedNames = new HashSet<String>();
                for (KeyframeAnimation clip : clips) {
                    assertTrue(decodedNames.add(assertInstanceOf(String.class, clip.extraData.get("name"))), name);
                    for (int tick : new int[]{0, clip.endTick, clip.stopTick}) {
                        var player = new KeyframeAnimationPlayer(clip, tick);
                        player.setupAnim(0);
                        for (String part : new String[]{"body", "torso", "head", "leftArm", "rightArm", "leftLeg", "rightLeg"}) {
                            for (TransformType type : new TransformType[]{TransformType.POSITION, TransformType.ROTATION}) {
                                Vec3f value = player.get3DTransform(part, type, 0, new Vec3f(0.2F, 0.3F, 0.4F));
                                assertTrue(Float.isFinite(value.getX()) && Float.isFinite(value.getY()) && Float.isFinite(value.getZ()),
                                        name + "/" + clip.extraData.get("name") + "/" + part + "/" + tick);
                            }
                        }
                    }
                }
                assertEquals(sourceNames, decodedNames, name);
            }
        }
    }

    @Test
    void zeroBeginTickAdjustmentIsFiniteWithoutChangingPositiveBeginTickInterpolation() {
        for (int begin : new int[]{0, 4}) {
            var builder = new KeyframeAnimation.AnimationBuilder(AnimationFormat.JSON_MC_ANIM);
            builder.beginTick = begin;
            builder.endTick = 20;
            var clip = builder.build();
            var angles = new AdjustmentYRotModifier(0.5F, 0.6F, false, false, false, false);
            var adjusted = AdjustmentYRotModifier.getModifier(() -> angles);
            var original = new AdjustmentModifier(part -> angles.apply(part));
            adjusted.setAnim(new KeyframeAnimationPlayer(clip));
            original.setAnim(new KeyframeAnimationPlayer(clip));
            for (float partial : new float[]{0, 0.5F}) {
                adjusted.setupAnim(partial);
                original.setupAnim(partial);
                Vec3f value = adjusted.get3DTransform("head", TransformType.ROTATION, partial, Vec3f.ZERO);
                assertTrue(Float.isFinite(value.getX()) && Float.isFinite(value.getY()) && Float.isFinite(value.getZ()));
                if (begin > 0) {
                    Vec3f expected = original.get3DTransform("head", TransformType.ROTATION, partial, Vec3f.ZERO);
                    assertEquals(expected.getX(), value.getX());
                    assertEquals(expected.getY(), value.getY());
                    assertEquals(expected.getZ(), value.getZ());
                }
            }
        }
    }

    @Test
    void animationNamesRetainPlainQuotedAndTextComponentSemantics() {
        assertEquals("hold_upper", PlayerAnimatorAssetManager.animationName("hold_upper"));
        assertEquals("hold_upper", PlayerAnimatorAssetManager.animationName("\"hold_upper\""));
        assertEquals("hold_upper", PlayerAnimatorAssetManager.animationName("{\"text\":\"hold\",\"extra\":[{\"text\":\"_upper\"}]}"));
    }
}
