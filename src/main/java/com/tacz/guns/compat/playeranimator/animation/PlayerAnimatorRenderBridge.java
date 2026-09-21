package com.tacz.guns.compat.playeranimator.animation;

import com.tacz.guns.GunMod;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.animation.third.InnerThirdPersonManager;
import com.tacz.guns.compat.playeranimator.PlayerAnimatorCompat;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

public final class PlayerAnimatorRenderBridge {
    public static final ContextKey<PlayerAnimationFrame> FRAME =
            new ContextKey<>(Identifier.fromNamespaceAndPath(GunMod.MOD_ID, "player_animation_frame"));
    private static HumanoidModel<AvatarRenderState> wideSampler;
    private static HumanoidModel<AvatarRenderState> slimSampler;

    private PlayerAnimatorRenderBridge() {
    }

    public static void register(RegisterRenderStateModifiersEvent event) {
        // This callback runs after NeoForge resets the render state's extension data.
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState renderState) {
                if (avatar instanceof AbstractClientPlayer player && PlayerAnimatorCompat.isInstalled()) {
                    prepare(player, renderState);
                }
            }
        });
    }

    private static void prepare(AbstractClientPlayer player, AvatarRenderState renderState) {
        PlayerAnimationState animation = PlayerAnimatorCompat.state(player);
        if (IGun.getIGunOrNull(player.getMainHandItem()) == null
                || !TimelessAPI.getGunDisplay(player.getMainHandItem()).map(AnimationManager::hasPlayerAnimator3rd).orElse(false)) {
            animation.stopAll(8);
        }
        HumanoidModel<AvatarRenderState> baseline = sampler(renderState.skin.model() == PlayerModelType.SLIM);
        baseline.setupAnim(renderState);
        // The existing fallback still runs for guns without custom clips. Mutation gates live in the player state.
        InnerThirdPersonManager.setRotationAnglesHead(player, baseline.rightArm, baseline.leftArm, baseline.body,
                baseline.head, renderState.walkAnimationSpeed);
        animation.prepareSample(AdjustmentYRotModifier.capture(player, renderState.partialTick), renderState.partialTick);
        renderState.setRenderData(FRAME, PlayerAnimationFrame.capture(baseline, animation));
    }

    private static HumanoidModel<AvatarRenderState> sampler(boolean slim) {
        if (slim) {
            if (slimSampler == null) {
                slimSampler = createSampler(true);
            }
            return slimSampler;
        }
        if (wideSampler == null) {
            wideSampler = createSampler(false);
        }
        return wideSampler;
    }

    static HumanoidModel<AvatarRenderState> createSampler(boolean slim) {
        // HumanoidModel has the identical vanilla setup, without the PlayerModel animation mixins.
        return new HumanoidModel<>(PlayerModel.createMesh(CubeDeformation.NONE, slim).getRoot().bake(64, 64));
    }
}
