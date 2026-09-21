package com.tacz.guns.client.model.functional;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.server.packs.VanillaPackResources;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class BeamShaderCompileTest {
    @Test
    void ordinaryShaderRetainsCombinedAlphaCutoffAndNoFog() throws IOException {
        String shader = resource("/assets/tacz/shaders/core/laser_beam.fsh");
        int fade = shader.indexOf("color *= vertexColor;");
        int cutoff = shader.indexOf("if (color.a < 0.1)");
        int modulator = shader.indexOf("color *= ColorModulator;");
        assertTrue(fade >= 0 && cutoff > fade && modulator > cutoff);
        assertFalse(shader.contains("fog.glsl"));
        assertFalse(shader.contains("Sampler2"));
    }

    @Test
    void compilesBothBeamTypesAndAllOitStagesWithNativePipelineDefines() throws IOException {
        assertEquals(64, compileVariants());
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Beam shaderc: " + compileVariants() + " shader variants passed");
    }

    private static int compileVariants() throws IOException {
        var ordinaryOit = BeamRenderer.LaserBeamRenderState.LASER_BEAM_OIT;
        var entityOit = BeamRenderer.LaserBeamRenderState.LASER_BEAM_ENTITY_OIT;
        List<RenderPipeline> pipelines = List.of(
                BeamRenderer.LaserBeamRenderState.getLaserBeam().pipeline(),
                ordinaryOit.depthBoundsPipeline(), ordinaryOit.transmittancePipeline(), ordinaryOit.accumulatePipeline(),
                BeamRenderer.LaserBeamRenderState.getLaserBeamEntity().pipeline(),
                entityOit.depthBoundsPipeline(), entityOit.transmittancePipeline(), entityOit.accumulatePipeline());
        Map<String, ShadercIncludeResult> includes = new HashMap<>();
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) throw new AssertionError("shaderc compiler initialization failed");
        int compiled = 0;
        try (var resolver = ShadercIncludeResolve.create((user, requested, type, requesting, depth) -> {
            String id = MemoryUtil.memUTF8(requested);
            return includes.computeIfAbsent(id, BeamShaderCompileTest::loadInclude).address();
        }); var release = ShadercIncludeResultRelease.create((user, result) -> { })) {
            for (RenderPipeline pipeline : pipelines) {
                for (boolean zeroToOne : new boolean[]{false, true}) {
                    for (boolean explicitDepth : new boolean[]{false, true}) {
                        for (ShaderType stage : List.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) {
                            String extension = stage == ShaderType.VERTEX ? "vsh" : "fsh";
                            var shader = pipeline.getShaders().get(stage);
                            String source = resource("/assets/" + shader.getNamespace() + "/shaders/" + shader.getPath() + "." + extension);
                            long options = Shaderc.shaderc_compile_options_initialize();
                            try {
                                Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
                                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                                Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
                                Shaderc.shaderc_compile_options_set_generate_debug_info(options);
                                Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
                                Shaderc.shaderc_compile_options_set_include_callbacks(options, resolver, release, 0);
                                pipeline.getShaderDefines().flags().forEach(flag -> define(options, flag, ""));
                                pipeline.getShaderDefines().values().forEach((key, value) -> define(options, key, value));
                                if (zeroToOne) define(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
                                if (explicitDepth) define(options, "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE", "");
                                long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                                        stage == ShaderType.VERTEX ? 0 : 1, pipeline.getLocation() + "." + extension, "main", options);
                                try {
                                    if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                                        throw new AssertionError(pipeline.getLocation() + ": " + Shaderc.shaderc_result_get_error_message(result));
                                    }
                                    assertTrue(Shaderc.shaderc_result_get_length(result) > 0);
                                    compiled++;
                                } finally {
                                    Shaderc.shaderc_result_release(result);
                                }
                            } finally {
                                Shaderc.shaderc_compile_options_release(options);
                            }
                        }
                    }
                }
            }
        } finally {
            for (var include : includes.values()) {
                MemoryUtil.memFree(include.source_name());
                MemoryUtil.memFree(include.content());
                include.free();
            }
            Shaderc.shaderc_compiler_release(compiler);
        }
        return compiled;
    }

    private static void define(long options, String key, String value) {
        Shaderc.shaderc_compile_options_add_macro_definition(options, key, value);
    }

    private static ShadercIncludeResult loadInclude(String id) {
        String[] location = id.split(":", 2);
        try {
            String source = resource("/assets/" + location[0] + "/shaders/include/" + location[1]);
            return ShadercIncludeResult.calloc().source_name(MemoryUtil.memUTF8(id, false)).content(MemoryUtil.memUTF8(source, false));
        } catch (IOException failure) {
            return ShadercIncludeResult.calloc().source_name(MemoryUtil.memUTF8("", false)).content(MemoryUtil.memUTF8(failure.toString(), false));
        }
    }

    private static String resource(String path) throws IOException {
        if (path.startsWith("/assets/minecraft/")) {
            // FML server-side tests mask client resources; use the exact loaded game artifact.
            var source = VanillaPackResources.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) throw new IOException("Missing Minecraft code source: " + path);
            try (var jar = new JarFile(Path.of(source.getLocation().toURI()).toFile())) {
                var entry = jar.getJarEntry(path.substring(1));
                if (entry == null) throw new IOException("Missing Minecraft shader: " + path);
                try (var input = jar.getInputStream(entry)) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (URISyntaxException failure) {
                throw new IOException("Invalid Minecraft code source", failure);
            }
        }
        try (InputStream input = BeamShaderCompileTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing beam shader: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
