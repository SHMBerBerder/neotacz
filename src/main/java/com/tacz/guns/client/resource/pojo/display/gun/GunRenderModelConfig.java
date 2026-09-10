package com.tacz.guns.client.resource.pojo.display.gun;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GunRenderModelConfig {
    public static final float MAX_SCALE = 1024.0F;
    public static final float MAX_ANIMATION_SPEED = 1024.0F;
    public static final int MAX_NODE_MAPPINGS = 8;
    public static final int MAX_NODE_NAME_LENGTH = 128;

    @SerializedName("type")
    private String type = "bedrock";
    @Nullable
    @SerializedName("location")
    private Identifier location;
    @SerializedName("scale")
    private float scale = 1.0F;
    @SerializedName("node_map")
    private Map<String, String> nodeMap = Collections.emptyMap();
    @Nullable
    @SerializedName("animation")
    private String animation;
    @SerializedName("loop_animation")
    private boolean loopAnimation = true;
    @SerializedName("animation_speed")
    private float animationSpeed = 1.0F;

    public String getType() {
        return type;
    }

    @Nullable
    public Identifier getLocation() {
        return location;
    }

    public float getScale() {
        return scale;
    }

    public Map<String, String> getNodeMap() {
        return nodeMap == null ? Collections.emptyMap() : nodeMap;
    }

    @Nullable
    public String getAnimation() {
        return animation;
    }

    public boolean isLoopAnimation() {
        return loopAnimation;
    }

    public float getAnimationSpeed() {
        return animationSpeed;
    }

    public boolean isGltf() {
        return "gltf".equals(type);
    }

    /**
     * Validates the opt-in renderer configuration before any model is loaded. The ordinary
     * Bedrock path does not construct this object and therefore keeps its existing behavior.
     */
    public void validate() {
        if (!"bedrock".equals(type) && !"gltf".equals(type)) {
            throw new IllegalArgumentException("render_model.type must be 'bedrock' or 'gltf'");
        }
        if (!Float.isFinite(scale) || scale <= 0.0F || scale > MAX_SCALE) {
            throw new IllegalArgumentException("render_model.scale must be finite and in (0, " + MAX_SCALE + "]");
        }
        if (isGltf() && location == null) {
            throw new IllegalArgumentException("render_model.location is required for gltf");
        }
        if (isGltf()) {
            String path = location.getPath();
            if (!path.startsWith("models/gltf/") || (!path.endsWith(".gltf") && !path.endsWith(".glb"))) {
                throw new IllegalArgumentException(
                        "render_model.location must name a .gltf or .glb under models/gltf"
                );
            }
        }
        if (animation != null && animation.isBlank()) {
            throw new IllegalArgumentException("render_model.animation must not be blank");
        }
        if (!Float.isFinite(animationSpeed) || animationSpeed <= 0.0F
                || animationSpeed > MAX_ANIMATION_SPEED) {
            throw new IllegalArgumentException(
                    "render_model.animation_speed must be finite and in (0, " + MAX_ANIMATION_SPEED + "]"
            );
        }
        Map<String, String> mappings = getNodeMap();
        if (!mappings.isEmpty()) {
            if (!isGltf()) {
                throw new IllegalArgumentException("render_model.node_map is only supported for gltf");
            }
            if (mappings.size() > MAX_NODE_MAPPINGS) {
                throw new IllegalArgumentException(
                        "render_model.node_map must contain at most " + MAX_NODE_MAPPINGS + " entries"
                );
            }
            Set<String> targets = new HashSet<>();
            for (Map.Entry<String, String> mapping : mappings.entrySet()) {
                String source = validateNodeName(mapping.getKey(), "source");
                String target = validateNodeName(mapping.getValue(), "target");
                if ("root".equals(source)) {
                    throw new IllegalArgumentException("render_model.node_map source 'root' is not allowed");
                }
                if (!targets.add(target)) {
                    throw new IllegalArgumentException(
                            "render_model.node_map target '" + target + "' must be unique"
                    );
                }
            }
        }
    }

    private static String validateNodeName(@Nullable String name, String role) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("render_model.node_map " + role + " name must not be blank");
        }
        if (!name.equals(name.strip())) {
            throw new IllegalArgumentException(
                    "render_model.node_map " + role + " name must not have leading or trailing whitespace"
            );
        }
        if (name.length() > MAX_NODE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "render_model.node_map " + role + " name must contain at most "
                            + MAX_NODE_NAME_LENGTH + " characters"
            );
        }
        return name;
    }
}
