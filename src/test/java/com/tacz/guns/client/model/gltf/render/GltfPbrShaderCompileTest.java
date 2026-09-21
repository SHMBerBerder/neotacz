package com.tacz.guns.client.model.gltf.render;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Compiles real resource includes with the same shaderc options as RenderPearl, without a GPU. */
class GltfPbrShaderCompileTest {
    @Test
    void compilesAllAlphaAndOitStagesForBothDepthConventions() throws IOException {
        compileVariants();
    }

    public static void main(String[] args) throws IOException {
        System.out.println("PBR shaderc: " + compileVariants() + " shader variants passed");
    }

    private static int compileVariants() throws IOException {
        Map<String, ShadercIncludeResult> includes = new HashMap<>();
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) throw new AssertionError("shaderc compiler initialization failed");
        int compiled = 0;
        try (ShadercIncludeResolve resolver = ShadercIncludeResolve.create((user, requested, type, requesting, depth) -> {
            String id = MemoryUtil.memUTF8(requested);
            return includes.computeIfAbsent(id, GltfPbrShaderCompileTest::loadInclude).address();
        }); ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create((user, result) -> { })) {
            for (String phase : List.of("OPAQUE", "MASK", "BLEND", "DEPTH_BOUNDS", "TRANSMITTANCE", "ACCUMULATE")) {
                for (boolean zeroToOne : new boolean[]{false, true}) {
                    for (boolean explicitDepth : new boolean[]{false, true}) {
                        for (String extension : List.of("vsh", "fsh")) {
                            String name = "gltf_pbr_" + phase + "." + extension;
                            long options = Shaderc.shaderc_compile_options_initialize();
                            try {
                                // Mirror GlslCompiler.createBaseShaderOptions, including Vulkan 1.2 SPIR-V target.
                                Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
                                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                                Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
                                Shaderc.shaderc_compile_options_set_generate_debug_info(options);
                                Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
                                Shaderc.shaderc_compile_options_set_include_callbacks(options, resolver, release, 0);
                                if (zeroToOne) define(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
                                if (explicitDepth) define(options, "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE", "");
                                boolean oit = !List.of("OPAQUE", "MASK", "BLEND").contains(phase);
                                define(options, "GLTF_ALPHA_" + (oit ? "BLEND" : phase), "");
                                if (oit) {
                                    define(options, "OIT", "");
                                    define(options, "OIT_" + phase, "");
                                    define(options, "OIT_WAVELET_RANK", "2");
                                    define(options, "OIT_COEFF_COUNT", "8");
                                    define(options, "OIT_COEFF_ATTACHMENT_COUNT", "2");
                                    if (!phase.equals("ACCUMULATE")) define(options, "OIT_ALPHA_ONLY", "");
                                }
                                String source = resource("/assets/tacz/shaders/pbr/gltf_pbr." + extension);
                                long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                                        extension.equals("vsh") ? 0 : 1, name, "main", options);
                                try {
                                    if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                                        throw new AssertionError(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                                    }
                                    if (Shaderc.shaderc_result_get_length(result) == 0) {
                                        throw new AssertionError(name + ": empty SPIR-V result");
                                    }
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
            for (ShadercIncludeResult include : includes.values()) {
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
            return ShadercIncludeResult.calloc().source_name(MemoryUtil.memUTF8(id, false))
                    .content(MemoryUtil.memUTF8(source, false));
        } catch (IOException failure) {
            return ShadercIncludeResult.calloc().source_name(MemoryUtil.memUTF8("", false))
                    .content(MemoryUtil.memUTF8(failure.toString(), false));
        }
    }

    private static String resource(String path) throws IOException {
        if (path.startsWith("/assets/minecraft/shaders/include/")) {
            // FML's server-side JUnit loader masks client resources even from their owning module.
            // Read shader text from that exact loaded game artifact, never a searched fallback JAR.
            var source = VanillaPackResources.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                throw new IOException("Missing Minecraft code source for shader: " + path);
            }
            try {
                var location = source.getLocation().toURI();
                if (!"file".equals(location.getScheme())) {
                    throw new IOException("Minecraft shader source is not a file JAR: " + location);
                }
                Path archive = Path.of(location);
                if (!Files.isRegularFile(archive)) {
                    throw new IOException("Minecraft shader source is not a regular JAR: " + archive);
                }
                try (JarFile jar = new JarFile(archive.toFile())) {
                    var entry = jar.getJarEntry(path.substring(1));
                    if (entry == null) throw new IOException("Missing shader resource in " + archive + ": " + path);
                    try (InputStream input = jar.getInputStream(entry)) {
                        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } catch (URISyntaxException failure) {
                throw new IOException("Invalid Minecraft shader code source", failure);
            }
        }
        try (InputStream input = GltfPbrShaderCompileTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing shader resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
