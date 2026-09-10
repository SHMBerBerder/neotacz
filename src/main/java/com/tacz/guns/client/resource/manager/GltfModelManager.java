package com.tacz.guns.client.resource.manager;

import com.tacz.guns.GunMod;
import com.tacz.guns.client.model.gltf.loader.GltfLoadException;
import com.tacz.guns.client.model.gltf.loader.GltfLoaderOptions;
import com.tacz.guns.client.model.gltf.loader.GltfResourcePolicy;
import com.tacz.guns.client.model.gltf.loader.JgltfModelLoader;
import com.tacz.guns.client.model.gltf.loader.NormalizedGltfModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Generation-bound resource descriptors and an optional diagnostic model cache.
 * Pack streams are opened only during a requested load, never during reload preparation.
 */
public final class GltfModelManager extends SimplePreparableReloadListener<GltfModelManager.Snapshot> {
    public static final String RESOURCE_ROOT = GltfResourcePolicy.RESOURCE_ROOT;
    public static final Limits DEFAULT_LIMITS = new Limits(
            8_192,
            GltfResourcePolicy.DEFAULT_MAX_RESOURCE_BYTES,
            512L * 1024 * 1024
    );

    private final Limits limits;
    private final JgltfModelLoader loader;
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());
    private final CopyOnWriteArrayList<Runnable> disposalHooks = new CopyOnWriteArrayList<>();

    public GltfModelManager() {
        this(DEFAULT_LIMITS, new JgltfModelLoader());
    }

    GltfModelManager(Limits limits, JgltfModelLoader loader) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    protected Snapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Objects.requireNonNull(resourceManager, "resourceManager");
        Map<Identifier, Resource> discovered = resourceManager.listResources(
                RESOURCE_ROOT,
                GltfModelManager::isManagedResource
        );
        return Snapshot.capture(discovered, limits);
    }

    @Override
    protected void apply(Snapshot snapshot, ResourceManager resourceManager, ProfilerFiller profiler) {
        Objects.requireNonNull(snapshot, "snapshot");
        state.updateAndGet(previous -> new State(snapshot, previous.generation() + 1));
        if (snapshot.managerFailure() != null) {
            GunMod.LOGGER.error("Renderable glTF resources disabled for this reload", snapshot.managerFailure());
        }
        runDisposalHooks();
    }

    /**
     * Loads exactly the requested .gltf or .glb resource on first use. Failures are cached per main model.
     */
    public NormalizedGltfModel getModel(Identifier modelId) throws GltfLoadException {
        return getModel(modelId, true);
    }

    /**
     * Loads without retaining raw parsed assets or failures. Conversion callers own the returned model.
     * jgltf still reads all referenced resources during this call; this is not a streaming parser.
     */
    public NormalizedGltfModel getModelUncached(Identifier modelId) throws GltfLoadException {
        return getModel(modelId, false);
    }

    public NormalizedGltfModel getModelUncached(Identifier modelId, BooleanSupplier cancelled) throws GltfLoadException {
        requireMainModelId(modelId);
        Objects.requireNonNull(cancelled, "cancelled");
        State current = state.get();
        BooleanSupplier retired = () -> cancelled.getAsBoolean() || state.get() != current;
        if (retired.getAsBoolean()) throw new CancellationException("glTF model generation retired");
        LoadResult result = load(current.snapshot(), modelId, retired);
        if (retired.getAsBoolean()) throw new CancellationException("glTF model generation retired");
        if (result.model() != null) return result.model();
        throw result.failure();
    }

    private NormalizedGltfModel getModel(Identifier modelId, boolean cache) throws GltfLoadException {
        requireMainModelId(modelId);
        while (true) {
            State current = state.get();
            LoadResult result = cache
                    ? current.models().computeIfAbsent(modelId, id -> load(current.snapshot(), id))
                    : load(current.snapshot(), modelId);
            // Old pack descriptors may fail after closing. Both their results and errors must be discarded.
            if (state.get() != current) {
                continue;
            }
            if (result.model() != null) {
                return result.model();
            }
            throw result.failure();
        }
    }

    public Optional<GltfLoadException> getFailure(Identifier modelId) {
        requireMainModelId(modelId);
        while (true) {
            State current = state.get();
            LoadResult result = current.models().get(modelId);
            GltfLoadException failure = result == null ? null : result.failure();
            if (failure == null) failure = current.snapshot().failure(modelId);
            if (failure == null) failure = current.snapshot().managerFailure();
            if (state.get() == current) return Optional.ofNullable(failure);
        }
    }

    public Optional<GltfLoadException> getManagerFailure() {
        return Optional.ofNullable(state.get().snapshot().managerFailure());
    }

    /**
     * Reads one resource on demand without retaining it. Missing or unreadable resources return empty.
     * Fixture-owned arrays are copied; runtime reads already have an independent owner.
     */
    public Optional<byte[]> getResourceBytes(Identifier resourceId) {
        Objects.requireNonNull(resourceId, "resourceId");
        while (true) {
            State current = state.get();
            Optional<byte[]> result;
            try {
                byte[] bytes = current.snapshot().readResource(resourceId, current.snapshot().limits.resourceBytes());
                result = Optional.of(bytes.clone());
            } catch (IOException | RuntimeException exception) {
                result = Optional.empty();
            }
            if (state.get() == current) return result;
        }
    }

    public Set<Identifier> getResourceIds() {
        return state.get().snapshot().resourceIds();
    }

    public int getResourceCount() {
        return state.get().snapshot().resourceCount();
    }

    /** Snapshot-owned encoded byte arrays only: zero for runtime descriptors, not total process memory. */
    public long getSnapshotBytes() {
        return state.get().snapshot().totalBytes();
    }

    public long getGeneration() {
        return state.get().generation();
    }

    /**
     * Invalidates parsed models and in-flight callers while retaining descriptors. Hooks dispose GPU derivatives.
     */
    public void clearCache() {
        state.updateAndGet(State::withoutCachedModels);
        runDisposalHooks();
    }

    /** Quality loading must report cleanup failures rather than presenting a successful reload. */
    public void clearCacheForQuality() {
        state.updateAndGet(State::withoutCachedModels);
        RuntimeException failure = runDisposalHooks();
        if (failure != null) throw failure;
    }

    public void addDisposalHook(Runnable hook) {
        disposalHooks.add(Objects.requireNonNull(hook, "hook"));
    }

    public void removeDisposalHook(Runnable hook) {
        disposalHooks.remove(Objects.requireNonNull(hook, "hook"));
    }

    private LoadResult load(Snapshot snapshot, Identifier modelId) {
        return load(snapshot, modelId, () -> false);
    }

    private LoadResult load(Snapshot snapshot, Identifier modelId, BooleanSupplier cancelled) {
        try {
            long[] resolvedBytes = {0};
            // Share canonical resources only within this parse, never across snapshots or model loads.
            Map<Identifier, byte[]> resolvedResources = new HashMap<>();
            byte[] source = readForLoad(snapshot, modelId, resolvedBytes, resolvedResources);
            NormalizedGltfModel model = loader.load(
                    new ByteArrayInputStream(source),
                    uri -> {
                        if (cancelled.getAsBoolean()) throw new CancellationException("glTF model generation retired");
                        return ByteBuffer.wrap(readForLoad(snapshot, resolveResourceId(modelId, uri), resolvedBytes, resolvedResources))
                                .asReadOnlyBuffer();
                    },
                    new GltfLoaderOptions(GltfLoaderOptions.SUPPORTED, cancelled)
            );
            return LoadResult.success(model);
        } catch (IOException | RuntimeException exception) {
            return LoadResult.failure(new GltfLoadException("Failed to load glTF model " + modelId, exception));
        }
    }

    private static byte[] readForLoad(Snapshot snapshot, Identifier id, long[] resolvedBytes,
                                      Map<Identifier, byte[]> resolvedResources) throws IOException {
        byte[] resolved = resolvedResources.get(id);
        if (resolved != null) return resolved;
        long remaining = snapshot.limits.totalBytes() - resolvedBytes[0];
        try {
            byte[] bytes = snapshot.readResource(id, Math.min(snapshot.limits.resourceBytes(), remaining));
            resolvedBytes[0] = Math.addExact(resolvedBytes[0], bytes.length);
            resolvedResources.put(id, bytes);
            return bytes;
        } catch (IOException exception) {
            throw new GltfLoadException("Failed to resolve " + id + " within per-file limit "
                    + snapshot.limits.resourceBytes() + " and remaining model byte limit " + remaining, exception);
        }
    }

    private RuntimeException runDisposalHooks() {
        RuntimeException failure = null;
        for (Runnable hook : disposalHooks) {
            try {
                hook.run();
            } catch (RuntimeException exception) {
                GunMod.LOGGER.error("glTF reload disposal hook failed", exception);
                if (failure == null) failure = new IllegalStateException("glTF resource cleanup failed");
                failure.addSuppressed(exception);
            }
        }
        return failure;
    }

    static boolean isManagedResource(Identifier id) {
        return GltfResourcePolicy.isManagedResource(id);
    }

    static boolean isMainModel(Identifier id) {
        return GltfResourcePolicy.isMainModel(id);
    }

    /**
     * Resolves an external glTF URI within the main model's own directory and namespace.
     */
    public static Identifier resolveResourceId(Identifier modelId, String uri) {
        return GltfResourcePolicy.resolveResourceId(modelId, uri);
    }

    private static void requireMainModelId(Identifier modelId) {
        GltfResourcePolicy.requireMainModelId(modelId);
    }

    /** Descriptor count, per-resource bytes, and cumulative unique-resource bytes per main-model load. */
    public record Limits(int resourceCount, long resourceBytes, long totalBytes) {
        public Limits {
            if (resourceCount <= 0 || resourceBytes <= 0 || totalBytes <= 0) {
                throw new IllegalArgumentException("glTF resource limits must be positive");
            }
        }
    }

    static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), DEFAULT_LIMITS, 0, null);

        private final Map<Identifier, byte[]> resources;
        private final Map<Identifier, Resource> descriptors;
        private final Map<Identifier, GltfLoadException> failures;
        private final Limits limits;
        private final Set<Identifier> resourceIds;
        private final long totalBytes;
        private final GltfLoadException managerFailure;

        private Snapshot(
                Map<Identifier, byte[]> resources,
                Map<Identifier, Resource> descriptors,
                Map<Identifier, GltfLoadException> failures,
                Limits limits,
                long totalBytes,
                GltfLoadException managerFailure
        ) {
            this.resources = Map.copyOf(resources);
            this.descriptors = Map.copyOf(descriptors);
            this.failures = Map.copyOf(failures);
            this.limits = limits;
            Set<Identifier> ids = new HashSet<>(resources.keySet());
            ids.addAll(descriptors.keySet());
            ids.addAll(failures.keySet());
            this.resourceIds = Set.copyOf(ids);
            this.totalBytes = totalBytes;
            this.managerFailure = managerFailure;
        }

        static Snapshot empty() {
            return EMPTY;
        }

        static Snapshot capture(Map<Identifier, Resource> discovered, Limits limits) {
            Objects.requireNonNull(discovered, "discovered");
            Objects.requireNonNull(limits, "limits");
            try {
                Map<Identifier, Resource> descriptors = new HashMap<>();
                for (Map.Entry<Identifier, Resource> entry : discovered.entrySet()) {
                    if (isManagedResource(entry.getKey())) {
                        requireResourceCount(descriptors.size() + 1, limits);
                        descriptors.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "resource"));
                    }
                }
                return new Snapshot(Map.of(), descriptors, Map.of(), limits, 0, null);
            } catch (SnapshotLimitException exception) {
                return failed(exception, limits);
            }
        }

        /** Immutable in-memory test fixture; unlike runtime descriptors these arrays count as owned bytes. */
        static Snapshot fromByteArrays(Map<Identifier, byte[]> source, Limits limits) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(limits, "limits");
            for (Map.Entry<Identifier, byte[]> entry : source.entrySet()) {
                Identifier id = Objects.requireNonNull(entry.getKey(), "resource id");
                Objects.requireNonNull(entry.getValue(), "resource bytes");
                if (!isManagedResource(id)) {
                    throw new IllegalArgumentException("Resource is outside the managed glTF tree: " + id);
                }
            }
            try {
                requireResourceCount(source.size(), limits);
                Map<Identifier, byte[]> resources = new HashMap<>();
                Map<Identifier, GltfLoadException> failures = new HashMap<>();
                long total = 0;
                for (Map.Entry<Identifier, byte[]> entry : source.entrySet()) {
                    Identifier id = entry.getKey();
                    byte[] bytes = entry.getValue();
                    if (bytes.length > limits.resourceBytes()) {
                        failures.put(id, new GltfLoadException("glTF resource exceeds per-file limit: " + id));
                        continue;
                    }
                    total = Math.addExact(total, bytes.length);
                    resources.put(id, bytes.clone());
                }
                return new Snapshot(resources, Map.of(), failures, limits, total, null);
            } catch (SnapshotLimitException exception) {
                return failed(exception, limits);
            }
        }

        private static Snapshot failed(SnapshotLimitException exception, Limits limits) {
            return new Snapshot(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    limits,
                    0,
                    new GltfLoadException("glTF resource snapshot rejected: " + exception.getMessage(), exception)
            );
        }

        GltfLoadException failure(Identifier id) {
            return failures.get(id);
        }

        GltfLoadException managerFailure() {
            return managerFailure;
        }

        byte[] readResource(Identifier id, long maxBytes) throws IOException {
            if (managerFailure != null) {
                throw managerFailure;
            }
            GltfLoadException failure = failures.get(id);
            if (failure != null) {
                throw failure;
            }
            byte[] bytes = resources.get(id);
            if (bytes != null) {
                if (bytes.length > maxBytes) throw new GltfLoadException("glTF resource exceeds remaining byte limit " + maxBytes);
                return bytes;
            }
            Resource descriptor = descriptors.get(id);
            if (descriptor == null) throw new GltfLoadException("Missing glTF resource " + id);
            try (InputStream input = descriptor.open()) {
                if (maxBytes == 0) {
                    if (input.read() != -1) throw new GltfLoadException("glTF model has exhausted its resolved byte limit");
                    return new byte[0];
                }
                return GltfResourcePolicy.readAllBytesLimited(input, maxBytes);
            }
        }

        Set<Identifier> resourceIds() {
            return resourceIds;
        }

        int resourceCount() {
            return resourceIds.size();
        }

        long totalBytes() {
            return totalBytes;
        }

        private static void requireResourceCount(int count, Limits limits) {
            if (count > limits.resourceCount()) {
                throw new SnapshotLimitException("glTF snapshot contains " + count
                        + " resources, limit is " + limits.resourceCount());
            }
        }

    }

    private static final class SnapshotLimitException extends RuntimeException {
        private SnapshotLimitException(String message) {
            super(message);
        }

    }

    private record State(Snapshot snapshot, long generation, ConcurrentHashMap<Identifier, LoadResult> models) {
        private State(Snapshot snapshot, long generation) {
            this(snapshot, generation, new ConcurrentHashMap<>());
        }

        static State empty() {
            return new State(Snapshot.empty(), 0);
        }

        State withoutCachedModels() {
            return new State(snapshot, generation + 1);
        }
    }

    private record LoadResult(NormalizedGltfModel model, GltfLoadException failure) {
        static LoadResult success(NormalizedGltfModel model) {
            return new LoadResult(Objects.requireNonNull(model, "model"), null);
        }

        static LoadResult failure(GltfLoadException failure) {
            return new LoadResult(null, Objects.requireNonNull(failure, "failure"));
        }
    }
}
