package com.tacz.guns.client.migration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInteractionMigrationTest {
    private static final String OWNER = "com/tacz/guns/client/input/TaczClientInteraction";
    private static final String PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String EVENT = "net/neoforged/neoforge/client/event/InputEvent$InteractionKeyMappingTriggered";
    private static final String SWING_SOURCE = "net/minecraft/world/InteractionResult$SwingSource";
    private static final String SWING_DESCRIPTOR = "(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z";

    @Test
    void interactionStackIsReadAfterTheClickHookAndCanceledReturn() throws IOException {
        MethodNode method = method("startUseItem");
        List<MethodInsnNode> reads = calls(method, PLAYER, "getItemInHand");
        assertEquals(2, reads.size(), "The hook may replace the hand stack; animation and interaction need separate reads");
        MethodInsnNode animation = onlyCall(method, STACK, "getInteractAnimation");
        assertEquals(animation, next(reads.getFirst()));
        MethodInsnNode hook = onlyCall(method, "net/neoforged/neoforge/client/ClientHooks", "onClickInput");
        MethodInsnNode canceled = onlyCall(method, EVENT, "isCanceled");
        JumpInsnNode cancelBranch = assertInstanceOf(JumpInsnNode.class, next(canceled));
        assertEquals(Opcodes.IFEQ, cancelBranch.getOpcode());
        List<AbstractInsnNode> cancelArm = instructions(method).subList(
                method.instructions.indexOf(cancelBranch) + 1, method.instructions.indexOf(cancelBranch.label));
        assertEquals(1, cancelArm.stream().filter(node -> node.getOpcode() == Opcodes.RETURN).count());
        assertEquals(Opcodes.RETURN, previous(cancelBranch.label).getOpcode());
        assertTrue(index(method, reads.getFirst()) < index(method, animation));
        assertTrue(index(method, animation) < index(method, hook));
        assertTrue(index(method, hook) < index(method, canceled));
        assertTrue(index(method, cancelBranch.label) < index(method, reads.getLast()));

        VarInsnNode storedStack = assertInstanceOf(VarInsnNode.class, next(reads.getLast()));
        assertEquals(Opcodes.ASTORE, storedStack.getOpcode());
        assertLoadedFrom(next(storedStack), storedStack.var);
        MethodInsnNode enabled = onlyCall(method, STACK, "isItemEnabled");
        assertTrue(index(method, storedStack) < index(method, enabled));
        assertEquals(2, calls(method, STACK, "getCount").size());
        assertEquals(3, calls(method, STACK, "isEmpty").size());
        for (String name : List.of("getCount", "isEmpty")) {
            for (MethodInsnNode call : calls(method, STACK, name)) {
                assertTrue(index(method, enabled) < index(method, call));
                assertLoadedFrom(previous(call), storedStack.var);
            }
        }
        for (String name : List.of("interact", "useItemOn", "useItem")) {
            assertTrue(index(method, enabled) < index(method,
                    onlyCall(method, "net/minecraft/client/multiplayer/MultiPlayerGameMode", name)));
        }
        assertTrue(index(method, enabled) < index(method,
                onlyCall(method, "net/neoforged/neoforge/common/CommonHooks", "onEmptyClick")));
        VarInsnNode animationHand = assertInstanceOf(VarInsnNode.class, previous(reads.getFirst()));
        assertLoadedFrom(previous(reads.getLast()), animationHand.var);
    }

    @Test
    void canceledAndPredictedSwingsKeepThePreInteractionAnimationAndNativeItemUsed() throws IOException {
        MethodNode start = method("startUseItem");
        MethodNode predicted = method("swingIfPredicted");
        VarInsnNode animation = assertInstanceOf(VarInsnNode.class,
                next(onlyCall(start, STACK, "getInteractAnimation")));
        assertEquals(Opcodes.ASTORE, animation.getOpcode());
        assertEquals(2, calls(start, PLAYER, "swing").size());
        for (MethodInsnNode swing : calls(start, PLAYER, "swing")) {
            assertEquals(SWING_DESCRIPTOR, swing.desc);
            assertEquals(Opcodes.ICONST_0, previous(swing).getOpcode());
            assertLoadedFrom(previous(previous(swing)), animation.var);
        }
        MethodInsnNode canceled = onlyCall(start, EVENT, "isCanceled");
        JumpInsnNode cancelBranch = assertInstanceOf(JumpInsnNode.class, next(canceled));
        MethodInsnNode cancelSwing = calls(start, PLAYER, "swing").getFirst();
        MethodInsnNode shouldSwing = calls(start, EVENT, "shouldSwingHand").getFirst();
        assertTrue(index(start, cancelBranch) < index(start, shouldSwing));
        assertTrue(index(start, shouldSwing) < index(start, cancelSwing));
        assertTrue(index(start, cancelSwing) < index(start, cancelBranch.label));
        JumpInsnNode canceledSwingGate = assertInstanceOf(JumpInsnNode.class, next(shouldSwing));
        assertEquals(Opcodes.IFEQ, canceledSwingGate.getOpcode());
        assertEquals(Opcodes.RETURN, next(canceledSwingGate.label).getOpcode());

        assertEquals(2, calls(start, OWNER, "swingIfPredicted").size());
        assertEquals(2, calls(start, PLAYER, "itemUsed").size());
        for (MethodInsnNode call : calls(start, PLAYER, "itemUsed")) {
            assertEquals("(Lnet/minecraft/world/InteractionHand;)V", call.desc);
        }
        for (MethodNode method : List.of(start, predicted)) {
            List<FieldInsnNode> sources = instructions(method).stream()
                    .filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
                    .filter(field -> field.owner.equals(SWING_SOURCE)).toList();
            assertEquals(1, sources.size());
            assertEquals("PREDICTED", sources.getFirst().name);
            assertEquals(Opcodes.IF_ACMPNE, next(sources.getFirst()).getOpcode());
            assertFalse(instructions(method).stream().anyMatch(node -> node instanceof FieldInsnNode field
                    && field.name.equals("itemInHandRenderer")));
        }
        MethodInsnNode swing = onlyCall(predicted, PLAYER, "swing");
        assertEquals(SWING_DESCRIPTOR, swing.desc);
        assertEquals(Opcodes.ICONST_0, previous(swing).getOpcode());
        assertLoadedFrom(previous(previous(swing)), 2);
        assertTrue(instructions(predicted).stream().anyMatch(node -> node instanceof VarInsnNode variable
                && variable.getOpcode() == Opcodes.ILOAD && variable.var == 3
                && next(variable).getOpcode() == Opcodes.IFEQ));
    }

    private static MethodNode method(String name) throws IOException {
        try (var stream = ClientInteractionMigrationTest.class.getClassLoader().getResourceAsStream(OWNER + ".class")) {
            assertNotNull(stream);
            ClassNode owner = new ClassNode();
            new ClassReader(stream).accept(owner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        }
    }

    private static MethodInsnNode onlyCall(MethodNode method, String owner, String name) {
        List<MethodInsnNode> calls = calls(method, owner, name);
        assertEquals(1, calls.size(), owner + "." + name);
        return calls.getFirst();
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        return instructions(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(owner) && call.name.equals(name)).toList();
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        method.instructions.forEach(result::add);
        return result;
    }

    private static int index(MethodNode method, AbstractInsnNode node) {
        return method.instructions.indexOf(node);
    }

    private static void assertLoadedFrom(AbstractInsnNode node, int slot) {
        VarInsnNode variable = assertInstanceOf(VarInsnNode.class, node);
        assertEquals(Opcodes.ALOAD, variable.getOpcode());
        assertEquals(slot, variable.var);
    }

    private static AbstractInsnNode next(AbstractInsnNode node) {
        do node = node.getNext(); while (node != null && node.getOpcode() < 0);
        assertNotNull(node);
        return node;
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do node = node.getPrevious(); while (node != null && node.getOpcode() < 0);
        assertNotNull(node);
        return node;
    }
}
