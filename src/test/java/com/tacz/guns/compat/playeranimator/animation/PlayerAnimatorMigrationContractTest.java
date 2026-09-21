package com.tacz.guns.compat.playeranimator.animation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAnimatorMigrationContractTest {
    private static final String MIXIN = "com/tacz/guns/mixin/client/";

    @Test
    void registeredHooksMatchTheNativePlayerTickAndAvatarRotationDescriptors() throws IOException {
        try (var input = getClass().getResourceAsStream("/tacz.mixins.json")) {
            assertNotNull(input);
            var clients = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client");
            for (String name : List.of("client.AvatarRendererMixin", "client.PlayerAnimatorPlayerMixin")) {
                assertEquals(1, clients.asList().stream().filter(value -> value.getAsString().equals(name)).count());
            }
        }
        assertTrue(read("net/minecraft/client/player/AbstractClientPlayer").methods.stream()
                .anyMatch(method -> method.name.equals("tick") && method.desc.equals("()V")));
        assertTrue(read("net/minecraft/client/renderer/entity/player/AvatarRenderer").methods.stream()
                .anyMatch(method -> method.name.equals("setupRotations") && method.desc.equals(
                        "(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V")));
        MethodNode tick = method(read(MIXIN + "PlayerAnimatorPlayerMixin"), "tacz$tickPlayerAnimation");
        assertEquals(1, calls(tick, "advance"));
        assertTrue(read(MIXIN + "PlayerAnimatorPlayerMixin").fields.stream()
                .filter(field -> field.name.equals("tacz$playerAnimationState"))
                .allMatch(field -> (field.access & Opcodes.ACC_STATIC) == 0));
    }

    @Test
    void renderModifierIsOfficialAndModelApplicationNeverTicksOrReselectsPlayerAnimations() throws IOException {
        MethodNode register = method(read("com/tacz/guns/compat/playeranimator/animation/PlayerAnimatorRenderBridge"), "register");
        assertEquals(1, calls(register, "registerAvatarEntityModifier"));
        MethodNode apply = method(read("com/tacz/guns/compat/playeranimator/animation/PlayerAnimationFrame"), "apply");
        assertEquals(6, calls(apply, "loadPose"));
        assertEquals(0, calls(apply, "tick"));
        assertEquals(0, calls(apply, "playAnimation"));
        MethodNode model = method(read(MIXIN + "PlayerModelMixin"), "setRotationAnglesTail");
        assertEquals(0, calls(model, "advance"));
        assertEquals(0, calls(model, "playAnimation"));
        assertTrue(Arrays.stream(model.instructions.toArray()).anyMatch(instruction -> instruction instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.INSTANCEOF && type.desc.equals("net/minecraft/client/player/AbstractClientPlayer")));
        MethodNode play = method(read("com/tacz/guns/compat/playeranimator/PlayerAnimatorCompat"), "playAnimation");
        assertEquals(1, calls(play, "selectOnce"));
        assertEquals(1, calls(play, "playLowerAnimation"));
        assertEquals(1, calls(play, "playLoopUpperAnimation"));
        assertEquals(1, calls(play, "playRotationAnimation"));
    }

    @Test
    void nativeNameHooksDisableRemappingWithoutRelaxingRequiredTargets() throws IOException {
        assertNativeHook("PlayerAnimatorPlayerMixin", "tacz$tickPlayerAnimation", "tick()V");
        assertNativeHook("AvatarRendererMixin", "tacz$applyPlayerBody",
                "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V");
    }

    @Test
    void publishedFrameContainsOnlyImmutableValueRecordsAndNoPlayerOrCoreReferences() {
        assertTrue(PlayerAnimationFrame.class.isRecord());
        for (var component : PlayerAnimationFrame.class.getRecordComponents()) {
            assertTrue(component.getType().isRecord());
            for (var field : component.getType().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertTrue(Modifier.isFinal(field.getModifiers()));
                    assertTrue(field.getType().isPrimitive());
                }
            }
        }
    }

    private static long calls(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.name.equals(name)).count();
    }

    private static void assertNativeHook(String name, String callback, String target) throws IOException {
        ClassNode mixin = read(MIXIN + name);
        assertEquals(false, value(annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;"), "remap"));
        MethodNode hook = method(mixin, callback);
        AnnotationNode inject = annotation(hook.visibleAnnotations, hook.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(false, value(inject, "remap"));
        assertEquals(1, value(inject, "require"));
        assertEquals(List.of(target), value(inject, "method"));
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        return Stream.of(visible, invisible).filter(java.util.Objects::nonNull).flatMap(List::stream)
                .filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(name)) {
                return annotation.values.get(index + 1);
            }
        }
        throw new AssertionError("Missing annotation value: " + name);
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = PlayerAnimatorMigrationContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
