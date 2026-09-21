package com.tacz.guns.compat.playeranimator.animation;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.util.MinecraftGuiCompat;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Frame-local inputs: the core's adjustment function must not retain a live player. */
public record AdjustmentYRotModifier(float yaw, float pitch, boolean prone, boolean mounted,
                                     boolean fixedHands, boolean disabled)
        implements Function<String, Optional<AdjustmentModifier.PartModifier>> {
    public static final AdjustmentYRotModifier NONE = new AdjustmentYRotModifier(0, 0, false, false, false, true);

    public static AdjustmentYRotModifier capture(AbstractClientPlayer player, float partialTick) {
        float yBodyRot = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float yHeadRot = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
        float xRot = Mth.lerp(partialTick, player.xRotO, player.getXRot());

        float yaw = yHeadRot - yBodyRot;
        yaw = Mth.wrapDegrees(yaw);
        yaw = Mth.clamp(yaw, -85f, 85f);

        return new AdjustmentYRotModifier(yaw * Mth.DEG_TO_RAD, Mth.wrapDegrees(xRot) * Mth.DEG_TO_RAD,
                !player.isSwimming() && player.getPose() == Pose.SWIMMING, player.getVehicle() != null,
                TimelessAPI.getGunDisplay(player.getMainHandItem()).map(GunDisplayInstance::is3rdFixedHand).orElse(false),
                player == Minecraft.getInstance().player && MinecraftGuiCompat.screen() != null);
    }

    @Override
    public Optional<AdjustmentModifier.PartModifier> apply(String partName) {
        if (disabled || mounted && "body".equals(partName)) {
            return Optional.empty();
        }
        Vec3f rotation = switch (partName) {
            case "body" -> prone ? new Vec3f(0, 0, -yaw) : new Vec3f(0, -yaw, 0);
            case "head" -> new Vec3f(pitch, 0, 0);
            case "leftArm", "rightArm" -> fixedHands ? null : new Vec3f(pitch, 0, 0);
            default -> null;
        };
        return rotation == null ? Optional.empty()
                : Optional.of(new AdjustmentModifier.PartModifier(rotation, Vec3f.ZERO));
    }

    public static AdjustmentModifier getModifier(Supplier<AdjustmentYRotModifier> source) {
        return new AdjustmentModifier(part -> source.get().apply(part)) {
            @Override
            protected float getFadeIn(float delta) {
                // The original core divides by beginTick, including 0/0 on a new empty clip.
                if (getAnim() instanceof KeyframeAnimationPlayer player && player.getData().beginTick <= 0) {
                    return 1;
                }
                return super.getFadeIn(delta);
            }
        };
    }
}
