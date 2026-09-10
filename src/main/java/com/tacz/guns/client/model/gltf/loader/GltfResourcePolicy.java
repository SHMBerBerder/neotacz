package com.tacz.guns.client.model.gltf.loader;

import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;

/** Shared resource-pack boundary for every glTF resolver. */
public final class GltfResourcePolicy {
    public static final String RESOURCE_ROOT = "models/gltf";
    public static final long DEFAULT_MAX_RESOURCE_BYTES = 64L * 1024 * 1024;

    private static final int COPY_BUFFER_BYTES = 16 * 1024;
    private static final Set<String> MANAGED_EXTENSIONS = Set.of(
            ".gltf", ".glb", ".bin", ".png", ".jpg", ".jpeg", ".ktx2"
    );

    private GltfResourcePolicy() {
    }

    public static boolean isManagedResource(Identifier id) {
        Objects.requireNonNull(id, "id");
        String path = id.getPath();
        if (!path.startsWith(RESOURCE_ROOT + "/") || !hasCanonicalSegments(path)) {
            return false;
        }
        for (String extension : MANAGED_EXTENSIONS) {
            if (path.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMainModel(Identifier id) {
        Objects.requireNonNull(id, "id");
        String path = id.getPath();
        return path.startsWith(RESOURCE_ROOT + "/") && (path.endsWith(".gltf") || path.endsWith(".glb"));
    }

    public static void requireMainModelId(Identifier modelId) {
        Objects.requireNonNull(modelId, "modelId");
        if (!isMainModel(modelId)) {
            throw new IllegalArgumentException("glTF main model must be a .gltf or .glb under "
                    + RESOURCE_ROOT + ": " + modelId);
        }
    }

    /** Resolves an external URI within the main model's own directory and namespace. */
    public static Identifier resolveResourceId(Identifier modelId, String uri) {
        requireMainModelId(modelId);
        Objects.requireNonNull(uri, "uri");
        if (uri.isBlank() || uri.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid glTF resource URI: " + uri);
        }

        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid glTF resource URI: " + uri, exception);
        }
        if (parsed.isAbsolute() || parsed.getRawAuthority() != null) {
            throw new IllegalArgumentException("glTF resource URI must be relative: " + uri);
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("glTF resource URI may not contain query or fragment: " + uri);
        }

        String relativePath = parsed.getRawPath();
        if (relativePath == null || relativePath.isEmpty() || relativePath.startsWith("/")
                || relativePath.indexOf('%') >= 0 || relativePath.contains("//")) {
            throw new IllegalArgumentException("Invalid glTF resource path: " + uri);
        }

        Deque<String> segments = new ArrayDeque<>();
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("glTF resource URI escapes the model directory: " + uri);
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("glTF resource URI does not name a resource: " + uri);
        }

        String modelPath = modelId.getPath();
        String directory = modelPath.substring(0, modelPath.lastIndexOf('/'));
        Identifier resolved = Identifier.fromNamespaceAndPath(
                modelId.getNamespace(),
                directory + "/" + String.join("/", segments)
        );
        if (!isManagedResource(resolved)) {
            throw new IllegalArgumentException("Unsupported external glTF resource type: " + uri);
        }
        return resolved;
    }

    public static byte[] readAllBytesLimited(InputStream input, long limit) throws IOException {
        Objects.requireNonNull(input, "input");
        if (limit <= 0) {
            throw new IllegalArgumentException("glTF resource byte limit must be positive");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            try {
                total = Math.addExact(total, read);
            } catch (ArithmeticException exception) {
                throw new GltfLoadException("glTF resource byte count overflow", exception);
            }
            if (total > limit) {
                throw new GltfLoadException("glTF resource exceeds per-file byte limit " + limit);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean hasCanonicalSegments(String path) {
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }
}
