package com.tacz.guns.client.migration;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
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

import static org.junit.jupiter.api.Assertions.*;

class ShoulderSurfingMigrationTest {
    private static final String COMPAT = "com/tacz/guns/compat/shouldersurfing/";
    private static final String API = "com/github/exopandora/shouldersurfing/api/";

    @Test
    void pluginUsesTheNewEntrypointArrayAndEventBus() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("shouldersurfing_plugin.json")) {
            assertNotNull(stream);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(json.has("entrypoint"));
            assertEquals(1, json.getAsJsonArray("entrypoints").size());
            assertEquals("com.tacz.guns.compat.shouldersurfing.ShoulderSurfingPlugin",
                    json.getAsJsonArray("entrypoints").get(0).getAsString());
        }
        var register = method(COMPAT + "ShoulderSurfingPlugin", "register");
        assertEquals("(L" + API + "event/IEventBus;)V", register.desc);
        assertTrue(calls(register).stream().anyMatch(call -> call.owner.equals(API + "event/IEventBus")
                && call.name.equals("register")
                && call.desc.contains("ComputePlayerAimStateEventHandler")));
    }

    @Test
    void adaptiveAimChecksBothHandsAndNeverClearsOtherHandlers() throws IOException {
        var aim = method(COMPAT + "ShoulderSurfingPlugin", "computeAimState");
        assertTrue(calls(aim).stream().anyMatch(call -> call.name.equals("getMainHandItem")));
        assertTrue(calls(aim).stream().anyMatch(call -> call.name.equals("getOffhandItem")));
        int gunChecks = 0;
        for (var instruction : aim.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                    && type.desc.equals("com/tacz/guns/api/item/IGun")) gunChecks++;
        }
        assertEquals(2, gunChecks);
        var writes = calls(aim).stream().filter(call -> call.name.equals("setResult")).toList();
        assertEquals(1, writes.size());
        assertEquals(Opcodes.ICONST_1, writes.getFirst().getPrevious().getOpcode());
        assertFalse(calls(aim).stream().anyMatch(call -> call.name.toLowerCase().contains("cancel")));
    }

    @Test
    void crosshairKeepsRawFreeLookKeyAndCurrentPerspective() throws IOException {
        var crosshair = method(COMPAT + "ShoulderSurfingCompatInner", "showCrosshair");
        assertTrue(calls(crosshair).stream().anyMatch(call -> call.owner.equals(API + "client/Perspective")
                && call.name.equals("current")));
        assertTrue(calls(crosshair).stream().anyMatch(call -> call.name.equals("isDown")));
        assertFalse(calls(crosshair).stream().anyMatch(call -> call.name.equals("isFreeLooking")));
        boolean readsFreeLook = false;
        for (var instruction : crosshair.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("FREE_LOOK")) readsFreeLook = true;
        }
        assertTrue(readsFreeLook);
    }

    @Test
    void recoilKeepsBothCameraAxesAndPlayerFallback() throws IOException {
        var recoil = calls(method("com/tacz/guns/client/event/CameraSetupEvent", "applyCameraRecoil"));
        assertEquals(4, recoil.stream().filter(call -> call.owner.equals(API + "client/IShoulderSurfing")
                && call.name.equals("getInstance")).count());
        for (String axis : List.of("setXRot", "setYRot")) {
            assertTrue(recoil.stream().anyMatch(call -> call.owner.equals(API + "client/IShoulderSurfingCamera")
                    && call.name.equals(axis)));
            assertTrue(recoil.stream().anyMatch(call -> !call.owner.startsWith(API) && call.name.equals(axis)));
        }
    }

    private static MethodNode method(String owner, String name) throws IOException {
        try (var stream = ShoulderSurfingMigrationTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(stream, owner);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        }
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }
}
