package com.tacz.guns.client.migration;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstPersonRenderMigrationTest {
    private static final String MIXIN = "com/tacz/guns/mixin/client/";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String HAND_RENDERER = "net/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer";
    private static final String HAND_STATE = "net/minecraft/client/player/FirstPersonHandsAndItems";
    private static final String KEEPING = "com/tacz/guns/api/client/other/KeepingItemRenderer";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";

    @Test
    void registeredHandMixinsUseTheSeparateNativeStateAndRenderOwners() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("tacz.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> clients = new ArrayList<>();
            config.getAsJsonArray("client").forEach(value -> clients.add(value.getAsString()));
            assertEquals(1, clients.stream().filter("client.FirstPersonHandsAndItemsMixin"::equals).count());
            assertEquals(1, clients.stream().filter("client.FirstPersonHandsAndItemsRendererMixin"::equals).count());
            assertFalse(clients.contains("client.ItemInHandRendererMixin"));
            assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        }
        assertTrue(readClass(HAND_STATE).fields.stream().anyMatch(field ->
                field.name.equals("mainHandItem") && field.desc.equals("Lnet/minecraft/world/item/ItemStack;")));
        MethodNode hook = method(readClass(MIXIN + "FirstPersonHandsAndItemsRendererMixin"), "beforeHandRender");
        String target = injectedTarget(hook);
        assertNativeTarget(HAND_RENDERER, target);
        assertTrue(target.startsWith("submitHandsWithItems("));
        assertEquals("HEAD", value(at(annotation(hook, INJECT)), "value"));
        assertEquals(1, instructions(hook).stream().filter(instruction -> instruction instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.NEW
                && type.desc.equals("com/tacz/guns/api/client/event/BeforeRenderHandEvent")).count());
        assertEquals(1, calls(hook).stream().filter(call -> call.name.equals("post")).count());
    }

    @Test
    void keepingRendererReadsTheCurrentPlayerAndHasAnEmptyNoPlayerBranch() throws IOException {
        ClassNode owner = readClass(KEEPING);
        assertFalse(owner.fields.stream().anyMatch(field -> field.desc.equals("Lnet/minecraft/client/player/LocalPlayer;")));
        MethodNode getter = method(owner, "getRenderer");
        assertEquals(1, fields(getter).stream().filter(field -> field.owner.equals("net/minecraft/client/Minecraft")
                && field.name.equals("player") && field.getOpcode() == Opcodes.GETFIELD).count());
        assertEquals(1, calls(getter).stream().filter(call -> call.owner.equals("net/minecraft/client/player/LocalPlayer")
                && call.name.equals("firstPersonHandsAndItems") && call.desc.equals("()L" + HAND_STATE + ";")).count());
        assertTrue(instructions(getter).stream().anyMatch(instruction ->
                instruction.getOpcode() == Opcodes.IFNULL || instruction.getOpcode() == Opcodes.IFNONNULL));
        assertTrue(fields(getter).stream().anyMatch(field -> field.owner.equals(KEEPING)
                && field.name.equals("NO_PLAYER") && field.getOpcode() == Opcodes.GETSTATIC));
        MethodNode empty = method(readClass(KEEPING + "$1"), "getCurrentItem");
        assertTrue(fields(empty).stream().anyMatch(field -> field.owner.equals("net/minecraft/world/item/ItemStack")
                && field.name.equals("EMPTY") && field.getOpcode() == Opcodes.GETSTATIC));
    }

    @Test
    void bobAndHudHooksReadPartialTicksFromTheirNativeRenderStates() throws IOException {
        ClassNode rendererMixin = readClass(MIXIN + "GameRendererMixin");
        MethodNode level = method(rendererMixin, "markLevelBob");
        assertEquals("renderLevel()V", injectedTarget(level));
        assertNativeTarget(GAME_RENDERER, injectedTarget(level));
        assertTrue(fields(level).stream().anyMatch(field -> field.name.equals("worldPartialTicks")
                && field.desc.equals("F") && field.getOpcode() == Opcodes.GETFIELD));
        MethodNode hand = method(rendererMixin, "markItemInHandBob");
        assertNativeTarget(GAME_RENDERER, injectedTarget(hand));
        assertTrue(injectedTarget(hand).contains("Lcom/mojang/renderpearl/api/textures/GpuTextureView;"));
        assertCameraPartialTicks(hand);
        MethodNode hud = method(readClass(MIXIN + "CameraHudFovMixin"), "tacz$applyGunModelHudFov");
        assertNativeTarget("net/minecraft/client/Camera", injectedTarget(hud));
        assertTrue(injectedTarget(hud).contains("Lnet/minecraft/client/DeltaTracker;"));
        assertCameraPartialTicks(hud);
    }

    @Test
    void stencilUpgradeTouchesOnlyTheNativeHudDepthConstructorArgument() throws IOException {
        MethodNode hook = method(readClass(MIXIN + "GameRendererMixin"), "tacz$enableHudDepthStencil");
        AnnotationNode modify = annotation(hook, MODIFY_ARG);
        String constructor = (String) ((List<?>) value(modify, "method")).getFirst();
        assertNativeTarget(GAME_RENDERER, constructor);
        assertEquals(4, value(modify, "index"));
        assertEquals(1, value(modify, "require"));
        assertEquals(1, value(modify, "allow"));
        AnnotationNode at = at(modify);
        assertEquals("INVOKE", value(at, "value"));
        assertEquals(0, value(at, "ordinal"));
        String textureConstructor = "(Ljava/lang/String;IILcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V";
        assertEquals("Lcom/mojang/blaze3d/pipeline/TextureTarget;<init>" + textureConstructor, value(at, "target"));
        assertEquals(1, fields(hook).stream().filter(field -> field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals("com/mojang/renderpearl/api/GpuFormat")
                && field.name.equals("D32_FLOAT_S8_UINT")).count());
        MethodNode nativeConstructor = method(readClass(GAME_RENDERER), constructor);
        List<MethodInsnNode> targets = calls(nativeConstructor).stream().filter(call ->
                call.owner.equals("com/mojang/blaze3d/pipeline/TextureTarget") && call.name.equals("<init>")).toList();
        assertEquals(1, targets.size());
        assertEquals(textureConstructor, targets.getFirst().desc);
        AbstractInsnNode next = targets.getFirst().getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        assertTrue(next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                && field.owner.equals(GAME_RENDERER) && field.name.equals("hud3DTarget"));
    }

    private static void assertCameraPartialTicks(MethodNode method) {
        assertTrue(fields(method).stream().anyMatch(field ->
                field.owner.equals("net/minecraft/client/renderer/state/level/CameraRenderState")
                        && field.name.equals("cameraEntityPartialTicks") && field.getOpcode() == Opcodes.GETFIELD));
    }

    private static String injectedTarget(MethodNode method) {
        return (String) ((List<?>) value(annotation(method, INJECT), "method")).getFirst();
    }

    private static void assertNativeTarget(String owner, String target) throws IOException {
        method(readClass(owner), target);
    }

    private static ClassNode readClass(String name) throws IOException {
        try (var stream = FirstPersonRenderMigrationTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String target) {
        List<MethodNode> matches = owner.methods.stream().filter(method -> target.indexOf('(') < 0
                ? method.name.equals(target) : (method.name + method.desc).equals(target)).toList();
        assertEquals(1, matches.size(), owner.name + "." + target);
        return matches.getFirst();
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static AnnotationNode at(AnnotationNode injection) {
        Object value = value(injection, "at");
        return value instanceof List<?> list ? (AnnotationNode) list.getFirst() : (AnnotationNode) value;
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        }
        throw new AssertionError("Missing annotation value: " + name);
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        method.instructions.forEach(instructions::add);
        return instructions;
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        return instructions(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    private static List<FieldInsnNode> fields(MethodNode method) {
        return instructions(method).stream().filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast).toList();
    }
}
