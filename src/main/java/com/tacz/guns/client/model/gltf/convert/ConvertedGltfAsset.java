package com.tacz.guns.client.model.gltf.convert;

import com.tacz.guns.client.model.gltf.runtime.GltfScene;
import com.tacz.guns.client.model.gltf.runtime.GltfMesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public final class ConvertedGltfAsset {
    private final GltfScene runtimeScene;
    private final List<GltfRenderMesh> renderMeshes;
    private final List<GltfPbrMaterialData> materials;
    private final List<GltfImageData> images;
    private final List<GltfAnimationClip> animations;
    private final List<float[]> nodeMorphWeights;
    private final List<float[]> meshDefaultMorphWeights;
    private final List<GltfSceneData> scenes;
    private final int defaultSceneIndex;
    private final int activeSceneIndex;
    private final GltfLodMetadata lods;
    private final GltfLodSelection lodSelection;
    private final int lodLevel;
    private final boolean renderDataDerived;

    public ConvertedGltfAsset(
            GltfScene runtimeScene,
            List<GltfRenderMesh> renderMeshes,
            List<GltfPbrMaterialData> materials,
            List<GltfImageData> images,
            List<GltfAnimationClip> animations,
            List<float[]> nodeMorphWeights,
            List<float[]> meshDefaultMorphWeights,
            List<GltfSceneData> scenes,
            int defaultSceneIndex,
            int activeSceneIndex
    ) {
        this(runtimeScene, renderMeshes, materials, images, animations, nodeMorphWeights,
                meshDefaultMorphWeights, scenes, defaultSceneIndex, activeSceneIndex, GltfLodMetadata.NONE);
    }

    public ConvertedGltfAsset(
            GltfScene runtimeScene, List<GltfRenderMesh> renderMeshes, List<GltfPbrMaterialData> materials,
            List<GltfImageData> images, List<GltfAnimationClip> animations, List<float[]> nodeMorphWeights,
            List<float[]> meshDefaultMorphWeights, List<GltfSceneData> scenes,
            int defaultSceneIndex, int activeSceneIndex, GltfLodMetadata lods
    ) {
        this(runtimeScene, renderMeshes, materials, images, animations, nodeMorphWeights, meshDefaultMorphWeights,
                scenes, defaultSceneIndex, activeSceneIndex, lods, 0, null, false);
    }

    private ConvertedGltfAsset(
            GltfScene runtimeScene, List<GltfRenderMesh> renderMeshes, List<GltfPbrMaterialData> materials,
            List<GltfImageData> images, List<GltfAnimationClip> animations, List<float[]> nodeMorphWeights,
            List<float[]> meshDefaultMorphWeights, List<GltfSceneData> scenes,
            int defaultSceneIndex, int activeSceneIndex, GltfLodMetadata lods, int lodLevel,
            GltfLodSelection selection, boolean renderDataDerived
    ) {
        this.runtimeScene = Objects.requireNonNull(runtimeScene, "runtimeScene");
        this.renderMeshes = List.copyOf(Objects.requireNonNull(renderMeshes, "renderMeshes"));
        if (this.renderMeshes.size() != runtimeScene.meshes().size()) {
            throw new IllegalArgumentException("renderMeshes must be parallel to runtimeScene.meshes");
        }
        this.materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        this.images = List.copyOf(Objects.requireNonNull(images, "images"));
        this.animations = List.copyOf(Objects.requireNonNull(animations, "animations"));
        this.nodeMorphWeights = copyArrayList(nodeMorphWeights, "nodeMorphWeights");
        this.meshDefaultMorphWeights = copyArrayList(meshDefaultMorphWeights, "meshDefaultMorphWeights");
        if (this.nodeMorphWeights.size() != runtimeScene.nodes().size()) {
            throw new IllegalArgumentException("nodeMorphWeights must be parallel to runtimeScene.nodes");
        }
        if (this.meshDefaultMorphWeights.size() != runtimeScene.meshes().size()) {
            throw new IllegalArgumentException("meshDefaultMorphWeights must be parallel to runtimeScene.meshes");
        }
        this.scenes = List.copyOf(Objects.requireNonNull(scenes, "scenes"));
        if (defaultSceneIndex < -1 || defaultSceneIndex >= this.scenes.size()) {
            throw new IllegalArgumentException("defaultSceneIndex out of range: " + defaultSceneIndex);
        }
        if (activeSceneIndex < -1 || activeSceneIndex >= this.scenes.size()) {
            throw new IllegalArgumentException("activeSceneIndex out of range: " + activeSceneIndex);
        }
        this.defaultSceneIndex = defaultSceneIndex;
        this.activeSceneIndex = activeSceneIndex;
        this.lods = Objects.requireNonNull(lods, "lods");
        this.lodLevel = lodLevel;
        if (selection == null) lods.validate(runtimeScene, scenes, materials.size());
        this.lodSelection = selection == null ? new GltfLodSelection(runtimeScene, lods, lodLevel) : selection;
        this.renderDataDerived = renderDataDerived;
    }

    public GltfLodMetadata lods() {
        return lods;
    }

    public int lodLevel() {
        return lodLevel;
    }

    public GltfLodSelection lodSelection() {
        return lodSelection;
    }

    public Set<Integer> selectedNodeIndices() {
        return lodSelection.nodeIndices();
    }

    public Set<Integer> selectedMeshIndices() {
        return lodSelection.meshIndices();
    }

    public Set<Integer> authoredLodMeshIndices() {
        return lodSelection.authoredMeshIndices();
    }

    public Set<Integer> selectedMaterialIndices() {
        Set<Integer> selected = new HashSet<>();
        for (int mesh : selectedMeshIndices()) {
            for (GltfRenderPrimitive primitive : renderMeshes.get(mesh).primitives()) {
                if (primitive.materialIndex() >= 0) selected.add(primitive.materialIndex());
            }
        }
        return Set.copyOf(selected);
    }

    /** Select from the original converted asset; derived variants may have pruned material/image IDs. */
    public ConvertedGltfAsset withLodLevel(int level) {
        if (level < 0) throw new IllegalArgumentException("LOD level must be non-negative");
        if (level == lodLevel) return this;
        if (renderDataDerived || lodLevel != 0) {
            throw new IllegalStateException("Select LOD from the original converted asset, before deriving render data");
        }
        GltfLodSelection selection = new GltfLodSelection(runtimeScene, lods, level);
        List<GltfRenderMesh> meshes = new ArrayList<>(renderMeshes);
        for (int meshIndex : selection.meshIndices()) {
            GltfRenderMesh mesh = meshes.get(meshIndex);
            List<GltfRenderPrimitive> primitives = new ArrayList<>();
            boolean changed = false;
            for (GltfRenderPrimitive primitive : mesh.primitives()) {
                int material = lods.materialAtLevel(primitive.materialIndex(), level);
                changed |= material != primitive.materialIndex();
                primitives.add(material == primitive.materialIndex() ? primitive : new GltfRenderPrimitive(
                        primitive.runtimePrimitive(), primitive.indices(), primitive.texCoords0(), primitive.colors0(), material));
            }
            if (changed) meshes.set(meshIndex, new GltfRenderMesh(mesh.name(), primitives, mesh.defaultMorphWeights()));
        }
        return copyRenderData(meshes, materials, images, level, selection, false);
    }

    public ConvertedGltfAsset withRenderData(List<GltfRenderMesh> meshes, List<GltfPbrMaterialData> materials,
                                             List<GltfImageData> images) {
        return copyRenderData(meshes, materials, images, lodLevel, lodSelection, true);
    }

    private ConvertedGltfAsset copyRenderData(List<GltfRenderMesh> meshes, List<GltfPbrMaterialData> materials,
                                              List<GltfImageData> images, int level,
                                              GltfLodSelection selection, boolean derived) {
        List<GltfMesh> runtimeMeshes = meshes.stream().map(mesh -> new GltfMesh(
                mesh.primitives().stream().map(GltfRenderPrimitive::runtimePrimitive).toList())).toList();
        GltfScene scene = new GltfScene(runtimeScene.nodes(), runtimeMeshes, runtimeScene.skins(), runtimeScene.rootNodes());
        return new ConvertedGltfAsset(scene, meshes, materials, images, animations, nodeMorphWeights,
                meshDefaultMorphWeights, scenes, defaultSceneIndex, activeSceneIndex, lods, level, selection, derived);
    }

    public GltfScene runtimeScene() {
        return runtimeScene;
    }

    public List<GltfRenderMesh> renderMeshes() {
        return renderMeshes;
    }

    public List<GltfPbrMaterialData> materials() {
        return materials;
    }

    public GltfPbrMaterialData materialOrDefault(int materialIndex) {
        return materialIndex < 0 ? GltfPbrMaterialData.defaultMaterial() : materials.get(materialIndex);
    }

    public List<GltfImageData> images() {
        return images;
    }

    public List<GltfAnimationClip> animations() {
        return animations;
    }

    public List<float[]> nodeMorphWeights() {
        return copyArrayList(nodeMorphWeights, "nodeMorphWeights");
    }

    public float[] nodeMorphWeights(int nodeIndex) {
        return nodeMorphWeights.get(nodeIndex).clone();
    }

    public List<float[]> meshDefaultMorphWeights() {
        return copyArrayList(meshDefaultMorphWeights, "meshDefaultMorphWeights");
    }

    public float[] meshDefaultMorphWeights(int meshIndex) {
        return meshDefaultMorphWeights.get(meshIndex).clone();
    }

    public List<GltfSceneData> scenes() {
        return scenes;
    }

    public int defaultSceneIndex() {
        return defaultSceneIndex;
    }

    public int activeSceneIndex() {
        return activeSceneIndex;
    }

    private static List<float[]> copyArrayList(List<float[]> values, String name) {
        Objects.requireNonNull(values, name);
        List<float[]> copy = new ArrayList<>(values.size());
        for (float[] value : values) {
            copy.add(Objects.requireNonNull(value, name + " element").clone());
        }
        return List.copyOf(copy);
    }
}
