package com.tacz.guns.client.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class VulkanStencilCompatibilityTest {
    private static final String PIPELINE = "com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline";
    private static final String MIXIN = "com/tacz/guns/mixin/client/VulkanRenderPipelineMixin";
    private static final String CONSTRUCTOR = "(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;JJJJJ"
            + "Lit/unimi/dsi/fastutil/longs/LongList;Ljava/util/List;)V";

    @Test
    void clientRegistersTheCompatibilityMixin() throws IOException {
        try (var input = getClass().getResourceAsStream("/tacz.mixins.json")) {
            assertNotNull(input);
            var clients = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client");
            assertEquals(1, clients.asList().stream()
                    .filter(value -> value.getAsString().equals("client.VulkanRenderPipelineMixin")).count());
        }
    }

    @Test
    void nativeCompilerRetainsTheExactConstructorAndCompleteCreateInfoLocals() throws IOException {
        MethodNode compile = method(read(PIPELINE), "compile");
        assertEquals(1, Arrays.stream(compile.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && call.owner.equals(PIPELINE) && call.name.equals("<init>") && call.desc.equals(CONSTRUCTOR)).count());
        for (String local : new String[]{
                "Lorg/lwjgl/system/MemoryStack;",
                "Lorg/lwjgl/vulkan/VkPipelineRenderingCreateInfoKHR;",
                "Lorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;"}) {
            assertTrue(compile.localVariables.stream().anyMatch(variable -> variable.desc.equals(local)), local);
        }
        assertTrue(read(PIPELINE).fields.stream()
                .anyMatch(field -> field.name.equals("withDepthStencilPipeline") && field.desc.equals("J")));
    }

    @Test
    void repairUsesNativePipelineCompilationAndDeferredDestructionNotAFormatMismatchedFallback() throws IOException {
        ClassNode mixin = read(MIXIN);
        MethodNode compile = method(mixin, "tacz$completeStencilPipeline");
        assertEquals(1, mixin.methods.stream().mapToLong(method -> calls(method, "vkCreateGraphicsPipelines")).sum());
        assertEquals(1, mixin.methods.stream().mapToLong(method -> calls(method, "crashIfFailure")).sum());
        assertEquals(0, calls(compile, "withDepthPipeline"));
        assertEquals(0, calls(compile, "withoutDepthPipeline"));
        MethodNode destroy = method(mixin, "tacz$destroyStencilPipeline");
        assertEquals(1, calls(destroy, "vkDestroyPipeline"));
        assertEquals(0, calls(destroy, "vkDestroyPipelineLayout"));
        assertEquals(0, calls(destroy, "vkDestroyShaderModule"));
    }

    @Test
    void injectionIsRequiredAndDestructionClearsTheHandleBeforeReleasingIt() throws IOException {
        ClassNode mixin = read(MIXIN);
        MethodNode compile = method(mixin, "tacz$completeStencilPipeline");
        var modify = annotation(compile, "Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
        assertEquals(1, value(modify, "index"));
        assertEquals(1, value(modify, "require"));
        assertEquals(1, value(modify, "allow"));
        assertEquals(false, value(modify, "remap"));
        assertEquals("L" + PIPELINE + ";<init>" + CONSTRUCTOR, value((AnnotationNode) value(modify, "at"), "target"));
        MethodNode destroy = method(mixin, "tacz$destroyStencilPipeline");
        var inject = annotation(destroy, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("destroy()V"), value(inject, "method"));
        assertEquals(1, value(inject, "require"));
        assertEquals(1, value(inject, "allow"));
        assertEquals("HEAD", value(((List<AnnotationNode>) value(inject, "at")).getFirst(), "value"));
        var instructions = Arrays.asList(destroy.instructions.toArray());
        int clear = -1;
        int release = -1;
        for (int index = 0; index < instructions.size(); index++) {
            var instruction = instructions.get(index);
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("withDepthStencilPipeline")) {
                assertEquals(Opcodes.LCONST_0, instruction.getPrevious().getOpcode());
                clear = index;
            }
            if (instruction instanceof MethodInsnNode call && call.name.equals("vkDestroyPipeline")) release = index;
        }
        assertTrue(clear >= 0 && release > clear, "The owned native handle must be cleared before deferred release");
        assertTrue(instructions.stream().anyMatch(instruction -> instruction.getOpcode() == Opcodes.LCMP));
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        return Stream.of(method.visibleAnnotations, method.invisibleAnnotations)
                .filter(java.util.Objects::nonNull).flatMap(List::stream)
                .filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value: " + name);
    }

    private static long calls(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.name.equals(name)).count();
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = VulkanStencilCompatibilityTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }
}
