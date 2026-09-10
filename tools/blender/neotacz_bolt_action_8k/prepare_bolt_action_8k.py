#!/usr/bin/env python3
"""Fail-closed Blender 5.1 adapter for Poly Haven's official 8K bolt-action rifle."""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable
import bpy
from mathutils import Matrix, Vector
MODEL_FILE = "bolt_action_rifle_7_62_8k.gltf"
BUFFER_FILE = "bolt_action_rifle_7_62.bin"
OUTPUT_STEM = "neotacz_bolt_action_rifle_7_62_8k"
OUTPUT_GLTF = f"{OUTPUT_STEM}.gltf"
OUTPUT_BIN = f"{OUTPUT_STEM}.bin"
RUNTIME_SCALE = 1.662808055
GUN_ALIGNMENT = (0.0, 0.374779494, -0.031283695)
GUN_ROTATION_Z = math.radians(90.0)
MAGAZINE_AABB_MIN = Vector((-0.240729153, -0.009442334, -0.041001320))
MAGAZINE_AABB_MAX = Vector((-0.160735667, 0.009442306, 0.010822311))
MAGAZINE_AABB_EPSILON = 2.0e-7
MAGAZINE_COMPONENT_COUNT = 90
MAGAZINE_IMPORTED_VERTEX_COUNT = 928
MAGAZINE_UNIQUE_POSITION_COUNT = 392
MAGAZINE_TRIANGLE_COUNT = 772
SOURCE_TRIANGLE_COUNT = 19_985
OUTPUT_TRIANGLE_COUNT = 19_365
# This signature keeps the 234-vertex trigger guard out of the magazine selection.
TRIGGER_GUARD_VERTEX_COUNT = 234
TRIGGER_GUARD_TRIANGLE_COUNT = 280
TRIGGER_GUARD_AABB_MIN = Vector((-0.324057102, -0.021490743, -0.011399135))
TRIGGER_GUARD_AABB_MAX = Vector((0.055655599, 0.021490743, 0.051099330))
SOURCE_OBJECTS = dict(scope="bolt_action_rifle_7_62_scope", wrap="bolt_action_rifle_7_62_wrap",
                      body="bolt_action_rifle_7_62", bolt_b="bolt_action_rifle_7_62_bolt_b",
                      bolt_a="bolt_action_rifle_7_62_bolt_a", bullet="bolt_action_rifle_7_62_bullet_54mm",
                      trigger="bolt_action_rifle_7_62_trigger")
MATERIAL_ACCESSORIES = "bolt_action_rifle_7_62_accesories"
MATERIAL_GLASS = "bolt_action_rifle_7_62_accesories_glass"
MATERIAL_MAIN = "bolt_action_rifle_7_62"
EXPECTED_FILES: dict[str, dict[str, Any]] = {
    MODEL_FILE: {"size": 15_369, "md5": "3252e28ee6a32e7e9cc58fef968ff21b", "sha256": "bcf39728cce0d0dcdfa19f1ceafbca538407559968b18c208cba35d1a7adb32b"},
    BUFFER_FILE: {"size": 736_616, "md5": "8fe1b75cc0ffe2e527dd9b66bc0d9517", "sha256": "c9a32b6471ef08b6d901f9c1865a31c22d37b254a3f772a3c3348d0fa8203ed0"},
    "textures/bolt_action_rifle_7_62_accesories_arm_8k.jpg": {"size": 47_965_936, "md5": "17ac4e03e2c05175392bd2dcc955aba1", "sha256": "96a82545f519bf739e6eabf26a7b7ae508eddd828026d4f23c4d0d3c517193f9"},
    "textures/bolt_action_rifle_7_62_accesories_diff_8k.jpg": {"size": 43_900_248, "md5": "4afbd82ac14248818740a277842aa007", "sha256": "591291eddb11d432439fd8331ee3d5e83aed1e86968216ed293c4c0157c3dc1a"},
    "textures/bolt_action_rifle_7_62_accesories_nor_gl_8k.jpg": {"size": 60_297_873, "md5": "b6885a83c56a29b13726692450efab94", "sha256": "496b31e8d84d4d23178e5f4a47c9118f01fc2243fadf418f8e5741e10917039b"},
    "textures/bolt_action_rifle_7_62_arm_8k.jpg": {"size": 55_826_774, "md5": "81c9c5dcab1210225895810919bda89d", "sha256": "9b77e5ba6408cb35dc7cc5033bc4a8d423cbbb07f1255dbe742517063a969709"},
    "textures/bolt_action_rifle_7_62_diff_8k.jpg": {"size": 56_657_384, "md5": "4633229c9b3ac34869f4411ce309e523", "sha256": "a73d1ad78199e804dfba2383070bd5823b2622579fcd1e0210579bdd265a1422"},
    "textures/bolt_action_rifle_7_62_nor_gl_8k.jpg": {"size": 39_275_541, "md5": "11ca4a66429bc741ec434710a5f47f31", "sha256": "4a3b1c460c91e26105fa6be678d7f2fd826122415b47aefb7e6e7ff0d352fd45"},
}
TEXTURE_URIS = [
    "textures/bolt_action_rifle_7_62_accesories_nor_gl_8k.jpg",
    "textures/bolt_action_rifle_7_62_accesories_diff_8k.jpg",
    "textures/bolt_action_rifle_7_62_accesories_arm_8k.jpg",
    "textures/bolt_action_rifle_7_62_nor_gl_8k.jpg",
    "textures/bolt_action_rifle_7_62_diff_8k.jpg",
    "textures/bolt_action_rifle_7_62_arm_8k.jpg",
]
def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("/tmp/neotacz-bolt-action-8k-adapted"))
    parser.add_argument("--force", action="store_true", help="replace an existing external output directory")
    parser.add_argument("--validator", choices=("auto", "required", "skip"), default="auto",
                        help="run gltf-validator or glTF Transform validation when available")
    return parser.parse_args(argv)
def file_digest(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
def file_record(path: Path) -> dict[str, Any]:
    return {"size": path.stat().st_size, "md5": file_digest(path, "md5"),
            "sha256": file_digest(path, "sha256")}
def vector_list(vector: Vector) -> list[float]:
    return [round(float(value), 9) for value in vector]
def almost_equal_vector(left: Vector, right: Vector, epsilon: float = 2.0e-7) -> bool:
    return all(abs(left[axis] - right[axis]) <= epsilon for axis in range(3))
def is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False
def prepare_output_directory(output_dir: Path, input_dir: Path, force: bool) -> Path:
    output_dir = output_dir.expanduser().resolve()
    input_dir = input_dir.expanduser().resolve()
    repository_root = Path(__file__).resolve().parents[3]
    if output_dir == Path(output_dir.anchor):
        raise RuntimeError("Refusing to use a filesystem root as the output directory")
    if output_dir == input_dir or is_relative_to(output_dir, input_dir):
        raise RuntimeError("Output directory must not overwrite the official source directory")
    # Runtime integration must explicitly copy validated /tmp output into an ignored gunpack.
    if is_relative_to(output_dir, repository_root):
        raise RuntimeError(f"Output directory must be outside the repository: {repository_root}")
    if output_dir.exists() and any(output_dir.iterdir()):
        if not force:
            raise RuntimeError(f"Output directory is not empty; pass --force to replace it: {output_dir}")
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "textures").mkdir()
    return output_dir
def jpeg_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        if stream.read(2) != b"\xff\xd8":
            raise RuntimeError(f"Not a JPEG file: {path}")
        while True:
            marker_prefix = stream.read(1)
            if not marker_prefix:
                break
            if marker_prefix != b"\xff":
                continue
            marker = stream.read(1)
            while marker == b"\xff":
                marker = stream.read(1)
            if marker in (b"\xd8", b"\xd9"):
                continue
            length_raw = stream.read(2)
            if len(length_raw) != 2:
                break
            segment_length = int.from_bytes(length_raw, "big")
            if segment_length < 2:
                break
            if marker and marker[0] in {
                0xC0,
                0xC1,
                0xC2,
                0xC3,
                0xC5,
                0xC6,
                0xC7,
                0xC9,
                0xCA,
                0xCB,
                0xCD,
                0xCE,
                0xCF,
            }:
                payload = stream.read(segment_length - 2)
                if len(payload) < 5:
                    break
                height = int.from_bytes(payload[1:3], "big")
                width = int.from_bytes(payload[3:5], "big")
                return width, height
            stream.seek(segment_length - 2, os.SEEK_CUR)
    raise RuntimeError(f"JPEG dimensions were not found: {path}")
def primitive_triangles(document: dict[str, Any], primitive: dict[str, Any]) -> int:
    if primitive.get("mode", 4) != 4:
        raise RuntimeError(f"Expected TRIANGLES primitive, got mode={primitive.get('mode', 4)}")
    if "indices" not in primitive:
        position_accessor = primitive.get("attributes", {}).get("POSITION")
        if position_accessor is None:
            raise RuntimeError("Primitive has neither indices nor POSITION")
        count = document["accessors"][position_accessor]["count"]
    else:
        count = document["accessors"][primitive["indices"]]["count"]
    if count % 3:
        raise RuntimeError(f"TRIANGLES accessor count is not divisible by three: {count}")
    return count // 3
def document_triangles(document: dict[str, Any]) -> int:
    return sum(
        primitive_triangles(document, primitive)
        for mesh in document.get("meshes", [])
        for primitive in mesh.get("primitives", [])
    )
def validate_official_source(input_dir: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    input_dir = input_dir.expanduser().resolve()
    texture_dir = input_dir / "textures"
    if not texture_dir.is_dir():
        raise RuntimeError(f"Missing official textures directory: {texture_dir}")
    actual_texture_names = sorted(path.name for path in texture_dir.glob("*.jpg"))
    expected_texture_names = sorted(Path(uri).name for uri in TEXTURE_URIS)
    if actual_texture_names != expected_texture_names:
        raise RuntimeError(
            f"Expected exactly six official JPEGs in textures/, got {actual_texture_names}"
        )
    records: dict[str, dict[str, Any]] = {}
    for relative_path, expected in EXPECTED_FILES.items():
        path = input_dir / relative_path
        if not path.is_file():
            raise RuntimeError(f"Missing official source file: {path}")
        actual = file_record(path)
        if actual != expected:
            raise RuntimeError(
                f"Official source mismatch for {relative_path}: expected {expected}, got {actual}"
            )
        if relative_path.startswith("textures/"):
            dimensions = jpeg_dimensions(path)
            if dimensions != (8192, 8192):
                raise RuntimeError(f"Expected 8192x8192 JPEG, got {dimensions}: {path}")
            actual["width"] = dimensions[0]
            actual["height"] = dimensions[1]
        records[relative_path] = actual
    source_path = input_dir / MODEL_FILE
    document = json.loads(source_path.read_text(encoding="utf-8"))
    if document.get("asset", {}).get("version") != "2.0":
        raise RuntimeError("Official source is not glTF 2.0")
    unsupported = {
        "extensionsUsed": document.get("extensionsUsed", []),
        "extensionsRequired": document.get("extensionsRequired", []),
        "skins": len(document.get("skins", [])),
        "animations": len(document.get("animations", [])),
    }
    if any(unsupported.values()):
        raise RuntimeError(f"Official source gained unsupported features: {unsupported}")
    for mesh in document.get("meshes", []):
        if mesh.get("weights"):
            raise RuntimeError("Official source unexpectedly contains mesh morph weights")
        for primitive in mesh.get("primitives", []):
            attributes = primitive.get("attributes", {})
            if "TANGENT" in attributes or primitive.get("targets"):
                raise RuntimeError("Official source unexpectedly contains tangent or morph data")
    if document_triangles(document) != SOURCE_TRIANGLE_COUNT:
        raise RuntimeError("Official source topology no longer matches 19,985 triangles")
    node_names = [node.get("name") for node in document.get("nodes", [])]
    expected_nodes = set(SOURCE_OBJECTS.values())
    if len(node_names) != len(expected_nodes) or set(node_names) != expected_nodes:
        raise RuntimeError(f"Official source node set changed: {node_names}")
    source_uris = [image.get("uri") for image in document.get("images", [])]
    if len(source_uris) != 7 or sorted(set(source_uris)) != sorted(TEXTURE_URIS):
        raise RuntimeError(f"Official source image layout changed: {source_uris}")
    if document.get("samplers") != [{"magFilter": 9729, "minFilter": 9987}]:
        raise RuntimeError(f"Official source sampler changed: {document.get('samplers')}")
    return document, records
def clear_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
def imported_mesh(name: str) -> bpy.types.Object:
    object_ = bpy.data.objects.get(name)
    if object_ is None or object_.type != "MESH":
        raise RuntimeError(f"Expected imported mesh object: {name}")
    return object_
def component_bounds(object_: bpy.types.Object, indices: Iterable[int]) -> tuple[Vector, Vector]:
    points = [object_.matrix_world @ object_.data.vertices[index].co for index in indices]
    minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    return minimum, maximum
def connected_components(mesh: bpy.types.Mesh) -> list[list[int]]:
    adjacency = [set() for _ in mesh.vertices]
    for edge in mesh.edges:
        left, right = edge.vertices
        adjacency[left].add(right)
        adjacency[right].add(left)
    components: list[list[int]] = []
    visited: set[int] = set()
    for start in range(len(mesh.vertices)):
        if start in visited:
            continue
        pending = [start]
        visited.add(start)
        component: list[int] = []
        while pending:
            index = pending.pop()
            component.append(index)
            for neighbor in adjacency[index]:
                if neighbor not in visited:
                    visited.add(neighbor)
                    pending.append(neighbor)
        components.append(component)
    return components
def component_triangle_counts(mesh: bpy.types.Mesh, components: list[list[int]]) -> list[int]:
    owner: dict[int, int] = {}
    for component_index, component in enumerate(components):
        for vertex_index in component:
            owner[vertex_index] = component_index
    counts = [0] * len(components)
    for polygon in mesh.polygons:
        owners = {owner[index] for index in polygon.vertices}
        if len(owners) != 1:
            raise RuntimeError("Imported polygon spans disconnected edge components")
        counts[owners.pop()] += len(polygon.vertices) - 2
    return counts
def find_magazine_vertices(body: bpy.types.Object) -> tuple[set[int], dict[str, Any]]:
    mesh = body.data
    components = connected_components(mesh)
    triangle_counts = component_triangle_counts(mesh, components)
    selected_components: list[tuple[int, list[int], Vector, Vector]] = []
    trigger_guard_matches = 0
    for index, component in enumerate(components):
        minimum, maximum = component_bounds(body, component)
        within_magazine = all(
            MAGAZINE_AABB_MIN[axis] - MAGAZINE_AABB_EPSILON <= minimum[axis]
            and maximum[axis] <= MAGAZINE_AABB_MAX[axis] + MAGAZINE_AABB_EPSILON
            for axis in range(3)
        )
        if within_magazine:
            selected_components.append((index, component, minimum, maximum))
        if (
            len(component) == TRIGGER_GUARD_VERTEX_COUNT
            and triangle_counts[index] == TRIGGER_GUARD_TRIANGLE_COUNT
            and almost_equal_vector(minimum, TRIGGER_GUARD_AABB_MIN)
            and almost_equal_vector(maximum, TRIGGER_GUARD_AABB_MAX)
        ):
            trigger_guard_matches += 1
    selected = {
        vertex_index
        for _, component, _, _ in selected_components
        for vertex_index in component
    }
    selected_polygons = [
        polygon for polygon in mesh.polygons if all(index in selected for index in polygon.vertices)
    ]
    boundary_polygons = [
        polygon
        for polygon in mesh.polygons
        if any(index in selected for index in polygon.vertices)
        and not all(index in selected for index in polygon.vertices)
    ]
    points = [body.matrix_world @ mesh.vertices[index].co for index in selected]
    aggregate_minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    aggregate_maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    unique_positions = {
        tuple(round(float((body.matrix_world @ mesh.vertices[index].co)[axis]), 8) for axis in range(3))
        for index in selected
    }
    triangle_count = sum(len(polygon.vertices) - 2 for polygon in selected_polygons)
    stats = {
        "uv_split_component_count": len(selected_components),
        "imported_vertex_count": len(selected),
        "unique_position_count": len(unique_positions),
        "triangle_count": triangle_count,
        "boundary_polygon_count": len(boundary_polygons),
        "aabb_min": vector_list(aggregate_minimum),
        "aabb_max": vector_list(aggregate_maximum),
        "trigger_guard_signature_matches": trigger_guard_matches,
    }
    expected = {
        "uv_split_component_count": MAGAZINE_COMPONENT_COUNT,
        "imported_vertex_count": MAGAZINE_IMPORTED_VERTEX_COUNT,
        "unique_position_count": MAGAZINE_UNIQUE_POSITION_COUNT,
        "triangle_count": MAGAZINE_TRIANGLE_COUNT,
        "boundary_polygon_count": 0,
        "trigger_guard_signature_matches": 1,
    }
    for key, expected_value in expected.items():
        if stats[key] != expected_value:
            raise RuntimeError(f"Magazine topology mismatch for {key}: expected {expected_value}, got {stats[key]}")
    if not almost_equal_vector(aggregate_minimum, MAGAZINE_AABB_MIN):
        raise RuntimeError(f"Magazine minimum AABB changed: {aggregate_minimum}")
    if not almost_equal_vector(aggregate_maximum, MAGAZINE_AABB_MAX):
        raise RuntimeError(f"Magazine maximum AABB changed: {aggregate_maximum}")
    return selected, stats
def separate_magazine(body: bpy.types.Object, selected: set[int]) -> bpy.types.Object:
    bpy.ops.object.select_all(action="DESELECT")
    body.select_set(True)
    bpy.context.view_layer.objects.active = body
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="DESELECT")
    bpy.ops.object.mode_set(mode="OBJECT")
    for vertex in body.data.vertices:
        vertex.select = vertex.index in selected
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.separate(type="SELECTED")
    bpy.ops.object.mode_set(mode="OBJECT")
    if len(body.data.polygons) != 7_644 - MAGAZINE_TRIANGLE_COUNT:
        raise RuntimeError(f"Body polygon count changed after magazine separation: {len(body.data.polygons)}")
    candidates = [
        object_
        for object_ in bpy.context.scene.objects
        if object_.type == "MESH"
        and object_ is not body
        and len(object_.data.polygons) == MAGAZINE_TRIANGLE_COUNT
    ]
    if len(candidates) != 1:
        raise RuntimeError(
            "Expected one 772-triangle separated magazine mesh, got "
            f"{[(item.name, len(item.data.polygons)) for item in candidates]}"
        )
    magazine = candidates[0]
    return magazine
def apply_object_transform(object_: bpy.types.Object) -> None:
    bpy.ops.object.select_all(action="DESELECT")
    object_.select_set(True)
    bpy.context.view_layer.objects.active = object_
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    if object_.matrix_local != Matrix.Identity(4):
        raise RuntimeError(f"Object transform did not bake to identity: {object_.name}")
def set_parent(object_: bpy.types.Object, parent: bpy.types.Object) -> None:
    object_.parent = parent
    object_.matrix_parent_inverse = Matrix.Identity(4)
def build_adapted_scene(input_dir: Path) -> tuple[bpy.types.Object, dict[str, Any]]:
    if bpy.app.version[:2] != (5, 1):
        raise RuntimeError(f"This topology-pinned adapter requires Blender 5.1.x, got {bpy.app.version_string}")
    clear_scene()
    result = bpy.ops.import_scene.gltf(filepath=str(input_dir / MODEL_FILE))
    if "FINISHED" not in result:
        raise RuntimeError(f"Blender failed to import official glTF: {result}")
    if set(bpy.context.scene.objects) != {bpy.data.objects[name] for name in SOURCE_OBJECTS.values()}:
        raise RuntimeError("Blender import object set does not match the official seven-node source")
    source_objects = {key: imported_mesh(name) for key, name in SOURCE_OBJECTS.items()}
    magazine_vertices, magazine_stats = find_magazine_vertices(source_objects["body"])
    magazine = separate_magazine(source_objects["body"], magazine_vertices)
    bpy.data.objects.remove(source_objects["bullet"], do_unlink=True)
    rename = {
        source_objects["scope"]: "ScopeGeometry",
        source_objects["wrap"]: "WrapGeometry",
        source_objects["body"]: "GunBodyGeometry",
        source_objects["bolt_b"]: "BoltAssemblyGeometry",
        source_objects["bolt_a"]: "BoltHandleGeometry",
        source_objects["trigger"]: "TriggerGeometry",
        magazine: "MagazineVisual",
    }
    for object_, name in rename.items():
        object_.name = name
        object_.data.name = f"{name}Mesh"
        apply_object_transform(object_)
    root = bpy.data.objects.new("GunVisual", None)
    root.empty_display_type = "PLAIN_AXES"
    root.location = GUN_ALIGNMENT
    root.rotation_euler = (0.0, 0.0, GUN_ROTATION_Z)
    root.scale = (1.0, 1.0, 1.0)
    bpy.context.scene.collection.objects.link(root)
    bolt_assembly = bpy.data.objects.new("BoltAssemblyVisual", None)
    bolt_assembly.empty_display_type = "PLAIN_AXES"
    bpy.context.scene.collection.objects.link(bolt_assembly)
    set_parent(bolt_assembly, root)
    bolt_handle = bpy.data.objects.new("BoltHandleVisual", None)
    bolt_handle.empty_display_type = "PLAIN_AXES"
    bpy.context.scene.collection.objects.link(bolt_handle)
    set_parent(bolt_handle, bolt_assembly)
    for key in ("scope", "wrap", "body", "trigger"):
        set_parent(source_objects[key], root)
    set_parent(magazine, root)
    set_parent(source_objects["bolt_b"], bolt_assembly)
    set_parent(source_objects["bolt_a"], bolt_handle)
    if not almost_equal_vector(root.location, Vector(GUN_ALIGNMENT), epsilon=1.0e-8):
        raise RuntimeError("GunVisual alignment was not authored exactly")
    if not math.isclose(root.rotation_euler.z, GUN_ROTATION_Z, abs_tol=1.0e-7):
        raise RuntimeError("GunVisual rotation was not authored exactly")
    hierarchy = {
        "GunVisual": sorted(child.name for child in root.children),
        "BoltAssemblyVisual": sorted(child.name for child in bolt_assembly.children),
        "BoltHandleVisual": sorted(child.name for child in bolt_handle.children),
    }
    return root, {"magazine": magazine_stats, "hierarchy": hierarchy}
def hierarchy_objects(root: bpy.types.Object) -> list[bpy.types.Object]:
    result: list[bpy.types.Object] = []
    pending = [root]
    while pending:
        current = pending.pop()
        result.append(current)
        pending.extend(current.children)
    return result
def export_core_gltf(root: bpy.types.Object, output_dir: Path) -> Path:
    output_path = output_dir / OUTPUT_GLTF
    bpy.ops.object.select_all(action="DESELECT")
    for object_ in hierarchy_objects(root):
        object_.select_set(True)
    bpy.context.view_layer.objects.active = root
    result = bpy.ops.export_scene.gltf(
        filepath=str(output_path),
        export_format="GLTF_SEPARATE",
        export_image_format="NONE",
        export_keep_originals=True,
        export_texture_dir="textures",
        export_texcoords=True,
        export_normals=True,
        export_tangents=False,
        export_materials="EXPORT",
        export_unused_images=False,
        export_vertex_color="NONE",
        export_attributes=False,
        export_cameras=False,
        use_selection=True,
        export_yup=True,
        export_apply=False,
        export_animations=False,
        export_skins=False,
        export_morph=False,
        export_lights=False,
        export_gpu_instances=False,
    )
    if "FINISHED" not in result or not output_path.is_file():
        raise RuntimeError(f"Blender glTF export failed: {result}")
    return output_path
def material_definition(name: str) -> dict[str, Any]:
    if name in (MATERIAL_ACCESSORIES, MATERIAL_GLASS):
        normal_index, base_index, arm_index = 0, 1, 2
    elif name == MATERIAL_MAIN:
        normal_index, base_index, arm_index = 3, 4, 5
    else:
        raise RuntimeError(f"Unexpected material: {name}")
    base_factor = [1.0, 1.0, 1.0, 0.25 if name == MATERIAL_GLASS else 1.0]
    result: dict[str, Any] = {
        "name": name,
        "doubleSided": True,
        "normalTexture": {"index": normal_index},
        "occlusionTexture": {"index": arm_index, "strength": 1.0},
        "pbrMetallicRoughness": {
            "baseColorFactor": base_factor,
            "baseColorTexture": {"index": base_index},
            "metallicFactor": 1.0,
            "metallicRoughnessTexture": {"index": arm_index},
            "roughnessFactor": 1.0,
        },
    }
    if name == MATERIAL_GLASS:
        result["alphaMode"] = "BLEND"
    return result
def postprocess_gltf(output_path: Path, input_dir: Path) -> dict[str, Any]:
    document = json.loads(output_path.read_text(encoding="utf-8"))
    if len(document.get("buffers", [])) != 1:
        raise RuntimeError(f"Expected one external buffer, got {document.get('buffers')}")
    original_bin_uri = document["buffers"][0].get("uri")
    if not isinstance(original_bin_uri, str):
        raise RuntimeError("Blender export did not write an external buffer URI")
    original_bin = output_path.parent / original_bin_uri
    canonical_bin = output_path.parent / OUTPUT_BIN
    if original_bin.resolve() != canonical_bin.resolve():
        original_bin.replace(canonical_bin)
    document["buffers"][0]["uri"] = OUTPUT_BIN
    exported_materials = document.get("materials", [])
    names = [material.get("name") for material in exported_materials]
    expected_names = {MATERIAL_ACCESSORIES, MATERIAL_GLASS, MATERIAL_MAIN}
    if len(names) != 3 or set(names) != expected_names:
        raise RuntimeError(f"Blender did not preserve the three official materials: {names}")
    document["materials"] = [material_definition(name) for name in names]
    document["samplers"] = [
        {"magFilter": 9729, "minFilter": 9729, "wrapS": 10497, "wrapT": 10497}
    ]
    document["images"] = [
        {
            "mimeType": "image/jpeg",
            "name": Path(uri).stem,
            "uri": uri,
        }
        for uri in TEXTURE_URIS
    ]
    document["textures"] = [
        {"name": Path(uri).stem, "sampler": 0, "source": index}
        for index, uri in enumerate(TEXTURE_URIS)
    ]
    for key in ("extensionsUsed", "extensionsRequired"):
        if document.get(key):
            raise RuntimeError(f"Blender export unexpectedly used extensions: {document[key]}")
        document.pop(key, None)
    output_path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    copied: dict[str, Any] = {}
    for uri in TEXTURE_URIS:
        source = input_dir / uri
        destination = output_path.parent / uri
        shutil.copyfile(source, destination)
        source_record = file_record(source)
        destination_record = file_record(destination)
        if destination_record != source_record:
            raise RuntimeError(f"Texture copy was not byte-for-byte identical: {uri}")
        width, height = jpeg_dimensions(destination)
        if (width, height) != (8192, 8192):
            raise RuntimeError(f"Copied texture is not 8192x8192: {uri}")
        destination_record["width"] = width
        destination_record["height"] = height
        copied[uri] = destination_record
    return copied
def node_indices_by_name(document: dict[str, Any]) -> dict[str, int]:
    result: dict[str, int] = {}
    duplicates: set[str] = set()
    for index, node in enumerate(document.get("nodes", [])):
        name = node.get("name")
        if not isinstance(name, str):
            continue
        if name in result:
            duplicates.add(name)
        result[name] = index
    if duplicates:
        raise RuntimeError(f"Duplicate glTF node names: {sorted(duplicates)}")
    return result
def descendant_mesh_indices(document: dict[str, Any], node_index: int) -> list[int]:
    meshes: list[int] = []
    pending = [node_index]
    visited: set[int] = set()
    while pending:
        current = pending.pop()
        if current in visited:
            raise RuntimeError("glTF node graph contains a cycle or duplicate descendant path")
        visited.add(current)
        node = document["nodes"][current]
        if "mesh" in node:
            meshes.append(node["mesh"])
        pending.extend(node.get("children", []))
    return meshes
def mesh_triangle_count(document: dict[str, Any], mesh_indices: Iterable[int]) -> int:
    return sum(
        primitive_triangles(document, primitive)
        for mesh_index in mesh_indices
        for primitive in document["meshes"][mesh_index].get("primitives", [])
    )
def validate_output_gltf(output_path: Path, expected_textures: dict[str, Any]) -> dict[str, Any]:
    document = json.loads(output_path.read_text(encoding="utf-8"))
    if document.get("asset", {}).get("version") != "2.0":
        raise RuntimeError("Output is not glTF 2.0")
    for forbidden in ("extensionsUsed", "extensionsRequired", "skins", "animations", "cameras"):
        if document.get(forbidden):
            raise RuntimeError(f"Output contains unsupported {forbidden}: {document[forbidden]}")
    if len(document.get("scenes", [])) != 1:
        raise RuntimeError("Output must contain exactly one scene")
    scene_index = document.get("scene", 0)
    roots = document["scenes"][scene_index].get("nodes", [])
    if len(roots) != 1:
        raise RuntimeError(f"Output scene must have one root, got {roots}")
    names = node_indices_by_name(document)
    required_nodes = {
        "GunVisual",
        "BoltAssemblyVisual",
        "BoltHandleVisual",
        "MagazineVisual",
        "BoltAssemblyGeometry",
        "BoltHandleGeometry",
    }
    missing = sorted(required_nodes - set(names))
    if missing:
        raise RuntimeError(f"Output is missing required nodes: {missing}")
    if names["GunVisual"] != roots[0]:
        raise RuntimeError("GunVisual is not the single scene root")
    assembly = document["nodes"][names["BoltAssemblyVisual"]]
    handle = document["nodes"][names["BoltHandleVisual"]]
    magazine = document["nodes"][names["MagazineVisual"]]
    if names["BoltHandleVisual"] not in assembly.get("children", []):
        raise RuntimeError("BoltHandleVisual is not a child of BoltAssemblyVisual")
    if names["BoltAssemblyGeometry"] not in assembly.get("children", []):
        raise RuntimeError("BoltAssemblyVisual does not directly contain bolt_b geometry")
    if names["BoltHandleGeometry"] not in handle.get("children", []):
        raise RuntimeError("BoltHandleVisual does not directly contain bolt_a geometry")
    if "mesh" not in magazine:
        raise RuntimeError("MagazineVisual must be a direct mesh node")
    target_triangles = {
        "BoltAssemblyVisual_direct_bolt_b": mesh_triangle_count(
            document, [document["nodes"][names["BoltAssemblyGeometry"]]["mesh"]]
        ),
        "BoltHandleVisual": mesh_triangle_count(
            document, descendant_mesh_indices(document, names["BoltHandleVisual"])
        ),
        "MagazineVisual": mesh_triangle_count(document, [magazine["mesh"]]),
    }
    if target_triangles != {
        "BoltAssemblyVisual_direct_bolt_b": 1_089,
        "BoltHandleVisual": 1_906,
        "MagazineVisual": MAGAZINE_TRIANGLE_COUNT,
    }:
        raise RuntimeError(f"Target node geometry mapping changed: {target_triangles}")
    primitive_modes: list[int] = []
    material_triangles = {name: 0 for name in (MATERIAL_ACCESSORIES, MATERIAL_GLASS, MATERIAL_MAIN)}
    material_names = [material.get("name") for material in document.get("materials", [])]
    if len(material_names) != 3 or set(material_names) != set(material_triangles):
        raise RuntimeError(f"Output material set changed: {material_names}")
    for mesh in document.get("meshes", []):
        if mesh.get("weights"):
            raise RuntimeError("Output contains mesh morph weights")
        for primitive in mesh.get("primitives", []):
            mode = primitive.get("mode", 4)
            primitive_modes.append(mode)
            attributes = primitive.get("attributes", {})
            forbidden_attributes = {"TANGENT", "JOINTS_0", "WEIGHTS_0"} & set(attributes)
            if forbidden_attributes or primitive.get("targets"):
                raise RuntimeError(
                    f"Output contains unsupported deformation data: {forbidden_attributes}"
                )
            material_index = primitive.get("material")
            if not isinstance(material_index, int) or not 0 <= material_index < 3:
                raise RuntimeError(f"Primitive has invalid material index: {material_index}")
            material_triangles[material_names[material_index]] += primitive_triangles(document, primitive)
    if set(primitive_modes) != {4}:
        raise RuntimeError(f"Output must use TRIANGLES only: {primitive_modes}")
    total_triangles = document_triangles(document)
    if total_triangles != OUTPUT_TRIANGLE_COUNT:
        raise RuntimeError(f"Expected {OUTPUT_TRIANGLE_COUNT} triangles, got {total_triangles}")
    expected_material_triangles = {
        MATERIAL_ACCESSORIES: 8_550,
        MATERIAL_GLASS: 72,
        MATERIAL_MAIN: 10_743,
    }
    if material_triangles != expected_material_triangles:
        raise RuntimeError(f"Primitive/material mapping changed: {material_triangles}")
    if document.get("samplers") != [
        {"magFilter": 9729, "minFilter": 9729, "wrapS": 10497, "wrapT": 10497}
    ]:
        raise RuntimeError(f"Output sampler is not NeoTaCZ LINEAR_REPEAT: {document.get('samplers')}")
    image_uris = [image.get("uri") for image in document.get("images", [])]
    if image_uris != TEXTURE_URIS or len(set(image_uris)) != 6:
        raise RuntimeError(f"Output must contain six unique official image URIs: {image_uris}")
    textures = document.get("textures", [])
    if len(textures) != 6:
        raise RuntimeError(f"Output must contain six textures, got {len(textures)}")
    for index, texture in enumerate(textures):
        if texture.get("source") != index or texture.get("sampler") != 0:
            raise RuntimeError(f"Texture {index} has unexpected source/sampler: {texture}")
    for uri in image_uris:
        path = output_path.parent / uri
        if not path.is_file() or file_record(path) != {
            key: expected_textures[uri][key] for key in ("size", "md5", "sha256")
        }:
            raise RuntimeError(f"Output image no longer matches the official bytes: {uri}")
        if jpeg_dimensions(path) != (8192, 8192):
            raise RuntimeError(f"Output image is not 8192x8192: {uri}")
    materials = {material["name"]: material for material in document["materials"]}
    for name, material in materials.items():
        arm_index = 2 if name in (MATERIAL_ACCESSORIES, MATERIAL_GLASS) else 5
        pbr = material["pbrMetallicRoughness"]
        if pbr["metallicRoughnessTexture"]["index"] != arm_index:
            raise RuntimeError(f"Material {name} lost its ARM metallic/roughness texture")
        if material["occlusionTexture"]["index"] != arm_index:
            raise RuntimeError(f"Material {name} does not reuse ARM red as occlusion")
    glass = materials[MATERIAL_GLASS]
    if glass.get("alphaMode") != "BLEND":
        raise RuntimeError("Glass material must use BLEND")
    glass_alpha = glass["pbrMetallicRoughness"]["baseColorFactor"][3]
    if not math.isclose(glass_alpha, 0.25, abs_tol=1.0e-9):
        raise RuntimeError(f"Glass alpha must be 0.25, got {glass_alpha}")
    if len(document.get("buffers", [])) != 1 or document["buffers"][0].get("uri") != OUTPUT_BIN:
        raise RuntimeError("Output must reference the canonical external .bin")
    buffer_path = output_path.parent / OUTPUT_BIN
    if buffer_path.stat().st_size != document["buffers"][0].get("byteLength"):
        raise RuntimeError("Output .bin size does not match glTF buffer byteLength")
    if any(accessor.get("sparse") for accessor in document.get("accessors", [])):
        raise RuntimeError("Output must not contain sparse accessors")
    return {
        "scene_root": "GunVisual",
        "node_count": len(document.get("nodes", [])),
        "mesh_count": len(document.get("meshes", [])),
        "material_count": len(document.get("materials", [])),
        "image_count": len(document.get("images", [])),
        "texture_count": len(textures),
        "triangle_count": total_triangles,
        "primitive_modes": sorted(set(primitive_modes)),
        "material_triangles": material_triangles,
        "target_triangles": target_triangles,
        "unsupported_features": [],
    }
def validator_command(mode: str, output_path: Path) -> tuple[list[str] | None, str]:
    if mode == "skip":
        return None, "skipped by --validator=skip"
    direct = shutil.which("gltf-validator")
    if direct:
        return [direct, str(output_path)], "Khronos gltf-validator"
    npx = shutil.which("npx")
    if npx:
        return [npx, "--yes", "@gltf-transform/cli@4.2.1", "validate", str(output_path)], "glTF Transform 4.2.1 using Khronos validator"
    if mode == "required":
        raise RuntimeError("No Khronos glTF validator command is available")
    return None, "validator unavailable"
def run_validator(mode: str, output_path: Path) -> dict[str, Any]:
    command, implementation = validator_command(mode, output_path)
    validator_log = output_path.parent / "validator.txt"
    if command is None:
        validator_log.write_text(implementation + "\n", encoding="utf-8")
        return {"status": "skipped", "implementation": implementation, "log": validator_log.name}
    completed = subprocess.run(
        command,
        cwd=output_path.parent,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=300,
    )
    validator_log.write_text(completed.stdout, encoding="utf-8")
    if completed.returncode != 0:
        raise RuntimeError(
            f"Khronos validation failed with exit {completed.returncode}; see {validator_log}"
        )
    return {
        "status": "passed",
        "implementation": implementation,
        "command": command,
        "exit_code": completed.returncode,
        "log": validator_log.name,
    }
def object_descendant_meshes(object_: bpy.types.Object) -> list[bpy.types.Object]:
    meshes: list[bpy.types.Object] = []
    pending = [object_]
    visited: set[bpy.types.Object] = set()
    while pending:
        current = pending.pop()
        if current in visited:
            raise RuntimeError("Blender re-import hierarchy contains a cycle")
        visited.add(current)
        if current.type == "MESH":
            meshes.append(current)
        pending.extend(current.children)
    return meshes
def scene_mesh_bounds() -> tuple[Vector, Vector]:
    points: list[Vector] = []
    for object_ in bpy.context.scene.objects:
        if object_.type != "MESH" or object_.hide_render:
            continue
        points.extend(object_.matrix_world @ Vector(corner) for corner in object_.bound_box)
    if not points:
        raise RuntimeError("Re-imported scene has no visible mesh bounds")
    minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    return minimum, maximum
def render_workbench_preview(output_dir: Path) -> dict[str, Any]:
    scene = bpy.context.scene
    minimum, maximum = scene_mesh_bounds()
    center = (minimum + maximum) * 0.5
    extent = maximum - minimum
    radius = max(extent.length, 1.0)
    camera_data = bpy.data.cameras.new("VerificationCamera")
    camera = bpy.data.objects.new("VerificationCamera", camera_data)
    scene.collection.objects.link(camera)
    scene.camera = camera
    camera_data.type = "ORTHO"
    camera.location = center + Vector((1.35, -1.0, 0.8)).normalized() * radius * 2.5
    camera.rotation_euler = (center - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera_inverse = camera.matrix_world.inverted()
    projected: list[Vector] = []
    for object_ in scene.objects:
        if object_.type != "MESH" or object_.hide_render:
            continue
        projected.extend(camera_inverse @ (object_.matrix_world @ Vector(corner)) for corner in object_.bound_box)
    width = max(point.x for point in projected) - min(point.x for point in projected)
    height = max(point.y for point in projected) - min(point.y for point in projected)
    aspect = 1280.0 / 720.0
    camera_data.ortho_scale = max(height * 1.25, width * 1.25 / aspect, 0.1)
    for object_ in scene.objects:
        if object_.type != "MESH":
            continue
        if object_.name == "MagazineVisual":
            object_.color = (0.75, 0.19, 0.07, 1.0)
        elif object_.name == "BoltAssemblyGeometry":
            object_.color = (0.12, 0.52, 0.78, 1.0)
        elif object_.name == "BoltHandleGeometry":
            object_.color = (0.95, 0.56, 0.08, 1.0)
        elif object_.name == "WrapGeometry":
            object_.color = (0.32, 0.22, 0.12, 1.0)
        else:
            object_.color = (0.24, 0.27, 0.30, 1.0)
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "OBJECT"
    scene.display.shading.show_shadows = True
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = "WORLD"
    scene.display.shading.background_type = "VIEWPORT"
    scene.display.shading.background_color = (0.035, 0.045, 0.055)
    scene.render.resolution_x = 1280
    scene.render.resolution_y = 720
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    preview = output_dir / "preview.png"
    scene.render.filepath = str(preview)
    bpy.ops.render.render(write_still=True)
    if not preview.is_file() or preview.stat().st_size < 10_000:
        raise RuntimeError(f"Workbench preview was not rendered correctly: {preview}")
    image = bpy.data.images.load(str(preview), check_existing=False)
    dimensions = tuple(image.size)
    bpy.data.images.remove(image)
    if dimensions != (1280, 720):
        raise RuntimeError(f"Preview dimensions changed: {dimensions}")
    return {
        "path": preview.name,
        "width": dimensions[0],
        "height": dimensions[1],
        "size": preview.stat().st_size,
        "sha256": file_digest(preview, "sha256"),
        "engine": "BLENDER_WORKBENCH",
        "aabb_min": vector_list(minimum),
        "aabb_max": vector_list(maximum),
    }
def reimport_and_preview(output_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    clear_scene()
    result = bpy.ops.import_scene.gltf(filepath=str(output_path))
    if "FINISHED" not in result:
        raise RuntimeError(f"Blender failed to re-import adapted glTF: {result}")
    required = ["GunVisual", "BoltAssemblyVisual", "BoltHandleVisual", "MagazineVisual"]
    for name in required:
        matches = [object_ for object_ in bpy.data.objects if object_.name == name]
        if len(matches) != 1:
            raise RuntimeError(f"Blender re-import expected one {name}, got {len(matches)}")
    root = bpy.data.objects["GunVisual"]
    assembly = bpy.data.objects["BoltAssemblyVisual"]
    handle = bpy.data.objects["BoltHandleVisual"]
    magazine = bpy.data.objects["MagazineVisual"]
    if root.parent is not None or assembly.parent != root or handle.parent != assembly or magazine.parent != root:
        raise RuntimeError("Blender re-import did not preserve target hierarchy")
    affected = {
        "BoltAssemblyVisual": sorted(object_.name for object_ in object_descendant_meshes(assembly)),
        "BoltHandleVisual": sorted(object_.name for object_ in object_descendant_meshes(handle)),
        "MagazineVisual": sorted(object_.name for object_ in object_descendant_meshes(magazine)),
    }
    if affected["BoltHandleVisual"] != ["BoltHandleGeometry"]:
        raise RuntimeError(f"BoltHandleVisual geometry changed after re-import: {affected}")
    if affected["MagazineVisual"] != ["MagazineVisual"]:
        raise RuntimeError(f"MagazineVisual is no longer a direct mesh after re-import: {affected}")
    if "BoltAssemblyGeometry" not in affected["BoltAssemblyVisual"]:
        raise RuntimeError(f"BoltAssemblyVisual lost bolt_b geometry after re-import: {affected}")
    triangle_count = sum(len(object_.data.polygons) for object_ in bpy.context.scene.objects if object_.type == "MESH")
    if triangle_count != OUTPUT_TRIANGLE_COUNT:
        raise RuntimeError(f"Blender re-import triangle count changed: {triangle_count}")
    preview = render_workbench_preview(output_path.parent)
    return (
        {
            "status": "passed",
            "blender_version": bpy.app.version_string,
            "triangle_count": triangle_count,
            "mesh_count": sum(1 for object_ in bpy.context.scene.objects if object_.type == "MESH"),
            "target_geometry": affected,
        },
        preview,
    )
def write_report(
    output_dir: Path,
    input_records: dict[str, dict[str, Any]],
    build_stats: dict[str, Any],
    texture_records: dict[str, Any],
    gltf_validation: dict[str, Any],
    validator: dict[str, Any],
    reimport: dict[str, Any],
    preview: dict[str, Any],
) -> Path:
    gltf_path = output_dir / OUTPUT_GLTF
    bin_path = output_dir / OUTPUT_BIN
    encoded_texture_bytes = sum(record["size"] for record in texture_records.values())
    report = {
        "asset": "Poly Haven Bolt Action Rifle 7.62 8K",
        "license": "CC0",
        "source_url": "https://polyhaven.com/a/bolt_action_rifle_7_62",
        "blender_version": bpy.app.version_string,
        "source_files": input_records,
        "output": {
            "directory": str(output_dir),
            "gltf": {"path": OUTPUT_GLTF, **file_record(gltf_path)},
            "buffer": {"path": OUTPUT_BIN, **file_record(bin_path)},
            "textures": texture_records,
            "texture_uris": TEXTURE_URIS,
            "encoded_texture_bytes": encoded_texture_bytes,
            "decoded_rgba8_base_bytes": 6 * 8192 * 8192 * 4,
        },
        "alignment": {
            "gun_visual_translation_blender": list(GUN_ALIGNMENT),
            "gun_visual_rotation_z_degrees_blender": 90.0,
            "model_scale_baked": False,
            "recommended_render_model_scale": RUNTIME_SCALE,
        },
        "node_map": {
            "m95_bolt": "BoltAssemblyVisual",
            "rotate": "BoltHandleVisual",
            "mag_and_bullet": "MagazineVisual",
        },
        "hands": {
            "lefthand_pos": "Bedrock authority; intentionally not mapped",
            "righthand_pos": "Bedrock authority; intentionally not mapped",
        },
        "build": build_stats,
        "gltf_validation": gltf_validation,
        "validator": validator,
        "reimport": reimport,
        "preview": preview,
        "sampler_note": "LINEAR + REPEAT only; mipmaps are not claimed",
    }
    report_path = output_dir / "report.json"
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report_path
def main() -> None:
    args = parse_args()
    input_dir = args.input_dir.expanduser().resolve()
    output_dir = prepare_output_directory(args.output_dir, input_dir, args.force)
    _, input_records = validate_official_source(input_dir)
    root, build_stats = build_adapted_scene(input_dir)
    output_path = export_core_gltf(root, output_dir)
    texture_records = postprocess_gltf(output_path, input_dir)
    gltf_validation = validate_output_gltf(output_path, texture_records)
    validator = run_validator(args.validator, output_path)
    reimport, preview = reimport_and_preview(output_path)
    report_path = write_report(
        output_dir,
        input_records,
        build_stats,
        texture_records,
        gltf_validation,
        validator,
        reimport,
        preview,
    )
    print(
        json.dumps(
            {
                "status": "passed",
                "output_dir": str(output_dir),
                "gltf": str(output_path),
                "report": str(report_path),
                "preview": str(output_dir / preview["path"]),
                "triangles": gltf_validation["triangle_count"],
                "texture_bytes": sum(item["size"] for item in texture_records.values()),
                "validator": validator["status"],
                "reimport": reimport["status"],
            },
            indent=2,
        )
    )
if __name__ == "__main__":
    main()
