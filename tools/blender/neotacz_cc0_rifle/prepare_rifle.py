#!/usr/bin/env python3
"""Build the CC0 rifle demo asset for NeoTaCZ with Blender's Python API."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
import sys
from pathlib import Path

import bpy
from mathutils import Vector


EXPECTED_SOURCE_SHA256 = "3276f7088f508d72a5b85f05ea1597e80f475792a628bd395e265c2a705b0bd4"
TARGET_MUZZLE_Y_PX = 21.325
TARGET_LENGTH_PX = 42.0
MAGAZINE_VERTEX_COUNT = 12


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-blend", required=True, type=Path)
    parser.add_argument("--output-glb", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    return parser.parse_args(argv)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def connected_components(mesh: bpy.types.Mesh) -> list[list[int]]:
    adjacent = [set() for _ in mesh.vertices]
    for edge in mesh.edges:
        left, right = edge.vertices
        adjacent[left].add(right)
        adjacent[right].add(left)

    components = []
    visited = set()
    for start in range(len(mesh.vertices)):
        if start in visited:
            continue
        pending = [start]
        visited.add(start)
        component = []
        while pending:
            index = pending.pop()
            component.append(index)
            for neighbor in adjacent[index]:
                if neighbor not in visited:
                    visited.add(neighbor)
                    pending.append(neighbor)
        components.append(component)
    return components


def bounds(mesh: bpy.types.Mesh, indices: list[int]) -> tuple[Vector, Vector, Vector]:
    points = [mesh.vertices[index].co for index in indices]
    minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    center = sum(points, Vector()) / len(points)
    return minimum, maximum, center


def find_magazine(mesh: bpy.types.Mesh, components: list[list[int]]) -> list[int]:
    candidates = []
    for component in components:
        minimum, maximum, center = bounds(mesh, component)
        size = maximum - minimum
        if (
            len(component) == MAGAZINE_VERTEX_COUNT
            and center.z < -0.25
            and 1.5 < size.z < 3.0
            and 1.5 < center.y < 3.5
        ):
            candidates.append(component)
    if len(candidates) != 1:
        raise RuntimeError(f"Expected one magazine component, found {len(candidates)}")
    return candidates[0]


def configure_material(name: str, color: tuple[float, float, float, float], metallic: float, roughness: float) -> bpy.types.Material:
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    principled = next(node for node in material.node_tree.nodes if node.type == "BSDF_PRINCIPLED")
    principled.inputs["Base Color"].default_value = color
    principled.inputs["Metallic"].default_value = metallic
    principled.inputs["Roughness"].default_value = roughness
    return material


def transform_source_mesh(mesh: bpy.types.Mesh) -> tuple[float, Vector, Vector]:
    points = [vertex.co.copy() for vertex in mesh.vertices]
    minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    source_length = maximum.y - minimum.y
    if source_length <= 0.0:
        raise RuntimeError("Source rifle has no positive barrel-axis extent")
    factor = TARGET_LENGTH_PX / source_length
    center_x = (minimum.x + maximum.x) * 0.5
    for vertex in mesh.vertices:
        vertex.co.x = (vertex.co.x - center_x) * factor
        vertex.co.y = (vertex.co.y - maximum.y) * factor + TARGET_MUZZLE_Y_PX
        vertex.co.z *= factor
    mesh.update()
    transformed = [vertex.co for vertex in mesh.vertices]
    new_minimum = Vector(tuple(min(point[axis] for point in transformed) for axis in range(3)))
    new_maximum = Vector(tuple(max(point[axis] for point in transformed) for axis in range(3)))
    return factor, new_minimum, new_maximum


def make_armature(magazine_center: Vector, bolt_center: Vector) -> bpy.types.Object:
    armature = bpy.data.armatures.new("NeoTaCZRig")
    armature_object = bpy.data.objects.new("NeoTaCZRig", armature)
    bpy.context.scene.collection.objects.link(armature_object)
    bpy.context.view_layer.objects.active = armature_object
    armature_object.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")

    body = armature.edit_bones.new("BodyVisual")
    body.head = (0.0, 0.0, 0.0)
    body.tail = (0.0, 4.0, 0.0)

    magazine = armature.edit_bones.new("MagazineVisual")
    magazine.head = magazine_center
    magazine.tail = magazine_center + Vector((0.0, 3.0, 0.0))
    magazine.parent = body

    bolt = armature.edit_bones.new("BoltVisual")
    bolt.head = bolt_center
    bolt.tail = bolt_center + Vector((0.0, 3.0, 0.0))
    bolt.parent = body

    bpy.ops.object.mode_set(mode="OBJECT")
    return armature_object


def add_armature_binding(mesh_object: bpy.types.Object, armature: bpy.types.Object) -> None:
    mesh_object.parent = armature
    mesh_object.matrix_parent_inverse = armature.matrix_world.inverted()
    modifier = mesh_object.modifiers.new(name="NeoTaCZRig", type="ARMATURE")
    modifier.object = armature


def create_bolt(center: Vector, material: bpy.types.Material, armature: bpy.types.Object) -> bpy.types.Object:
    half = Vector((0.65, 2.4, 0.8))
    vertices = [
        (center.x + sx * half.x, center.y + sy * half.y, center.z + sz * half.z)
        for sx, sy, sz in (
            (-1, -1, -1),
            (1, -1, -1),
            (1, 1, -1),
            (-1, 1, -1),
            (-1, -1, 1),
            (1, -1, 1),
            (1, 1, 1),
            (-1, 1, 1),
        )
    ]
    faces = [
        (0, 1, 2, 3),
        (4, 7, 6, 5),
        (0, 4, 5, 1),
        (1, 5, 6, 2),
        (2, 6, 7, 3),
        (4, 0, 3, 7),
    ]
    mesh = bpy.data.meshes.new("BoltVisualMesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.materials.append(material)
    mesh.update()
    bolt = bpy.data.objects.new("BoltGeometry", mesh)
    bpy.context.scene.collection.objects.link(bolt)
    group = bolt.vertex_groups.new(name="BoltVisual")
    group.add(list(range(len(mesh.vertices))), 1.0, "REPLACE")
    add_armature_binding(bolt, armature)
    return bolt


def create_preview_action(armature: bpy.types.Object) -> str:
    action = bpy.data.actions.new("NeoTaCZ_BindPreview_DO_NOT_EXPORT")
    armature.animation_data_create()
    armature.animation_data.action = action
    bolt = armature.pose.bones["BoltVisual"]
    magazine = armature.pose.bones["MagazineVisual"]
    bolt.rotation_mode = "XYZ"
    magazine.rotation_mode = "XYZ"

    bolt.location = (0.0, 0.0, 0.0)
    bolt.keyframe_insert(data_path="location", frame=1)
    bolt.location.y = -4.0
    bolt.keyframe_insert(data_path="location", frame=4)
    bolt.location = (0.0, 0.0, 0.0)
    bolt.keyframe_insert(data_path="location", frame=7)

    magazine.location = (0.0, 0.0, 0.0)
    magazine.rotation_euler = (0.0, 0.0, 0.0)
    magazine.keyframe_insert(data_path="location", frame=10)
    magazine.keyframe_insert(data_path="rotation_euler", frame=10)
    magazine.location.z = -3.0
    magazine.rotation_euler.x = math.radians(-35.0)
    magazine.keyframe_insert(data_path="location", frame=18)
    magazine.keyframe_insert(data_path="rotation_euler", frame=18)
    magazine.location = (0.0, 0.0, 0.0)
    magazine.rotation_euler = (0.0, 0.0, 0.0)
    magazine.keyframe_insert(data_path="location", frame=26)
    magazine.keyframe_insert(data_path="rotation_euler", frame=26)
    bpy.context.scene.frame_start = 1
    bpy.context.scene.frame_end = 26
    bpy.context.scene.frame_set(1)
    return action.name


def read_glb_json(path: Path) -> dict:
    raw = path.read_bytes()
    if len(raw) < 20 or raw[:4] != b"glTF":
        raise RuntimeError("Export did not produce a valid GLB header")
    version, total_length = struct.unpack_from("<II", raw, 4)
    if version != 2 or total_length != len(raw):
        raise RuntimeError("Exported GLB header length/version mismatch")
    json_length, json_type = struct.unpack_from("<II", raw, 12)
    if json_type != 0x4E4F534A:
        raise RuntimeError("Exported GLB first chunk is not JSON")
    return json.loads(raw[20 : 20 + json_length].decode("utf-8").rstrip(" \t\r\n\x00"))


def validate_glb(document: dict) -> dict:
    extensions = sorted(set(document.get("extensionsUsed", [])) | set(document.get("extensionsRequired", [])))
    if extensions:
        raise RuntimeError(f"NeoTaCZ demo GLB must be extension-free, got {extensions}")
    if document.get("animations"):
        raise RuntimeError("NeoTaCZ demo GLB must not export embedded animations")

    names = [node.get("name", "") for node in document.get("nodes", [])]
    for expected in ("BodyVisual", "BoltVisual", "MagazineVisual"):
        if names.count(expected) != 1:
            raise RuntimeError(f"Expected one glTF node named {expected}, got {names.count(expected)}")
    if not document.get("skins"):
        raise RuntimeError("NeoTaCZ demo GLB must contain a skin")

    attributes = []
    primitive_modes = []
    for mesh in document.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            attributes.append(sorted(primitive.get("attributes", {}).keys()))
            primitive_modes.append(primitive.get("mode", 4))
    if not any("JOINTS_0" in item and "WEIGHTS_0" in item for item in attributes):
        raise RuntimeError("NeoTaCZ demo GLB must contain JOINTS_0 and WEIGHTS_0")
    if any(mode != 4 for mode in primitive_modes):
        raise RuntimeError(f"NeoTaCZ supports TRIANGLES only, got modes {primitive_modes}")
    return {
        "node_names": names,
        "skin_count": len(document.get("skins", [])),
        "mesh_count": len(document.get("meshes", [])),
        "material_count": len(document.get("materials", [])),
        "primitive_attributes": attributes,
        "primitive_modes": primitive_modes,
        "animations": len(document.get("animations", [])),
        "extensions": extensions,
    }


def main() -> None:
    args = parse_args()
    source = args.input.resolve()
    source_hash = sha256(source)
    if source_hash != EXPECTED_SOURCE_SHA256:
        raise RuntimeError(f"Unexpected source SHA-256: {source_hash}")

    for path in (args.output_blend, args.output_glb, args.report):
        path.resolve().parent.mkdir(parents=True, exist_ok=True)

    bpy.ops.wm.open_mainfile(filepath=str(source))
    source_actions = sorted(action.name for action in bpy.data.actions)
    gun = bpy.data.objects.get("Gun")
    if gun is None or gun.type != "MESH":
        raise RuntimeError("Source must contain the expected Gun mesh")

    for object_ in list(bpy.data.objects):
        if object_ != gun:
            bpy.data.objects.remove(object_, do_unlink=True)
    gun.name = "RifleBody"
    gun.parent = None
    for modifier in list(gun.modifiers):
        gun.modifiers.remove(modifier)
    for group in list(gun.vertex_groups):
        gun.vertex_groups.remove(group)

    mesh = gun.data
    components = connected_components(mesh)
    magazine_indices = find_magazine(mesh, components)
    original_material_indices = [polygon.material_index for polygon in mesh.polygons]
    factor, transformed_minimum, transformed_maximum = transform_source_mesh(mesh)

    metal = configure_material("RifleMetal", (0.055, 0.065, 0.075, 1.0), 0.72, 0.28)
    wood = configure_material("RifleWood", (0.34, 0.14, 0.055, 1.0), 0.0, 0.58)
    bolt_material = configure_material("BoltMetal", (0.16, 0.18, 0.20, 1.0), 0.88, 0.20)
    mesh.materials.clear()
    mesh.materials.append(metal)
    mesh.materials.append(wood)
    for polygon, old_index in zip(mesh.polygons, original_material_indices):
        polygon.material_index = 1 if old_index == 2 else 0

    body_indices = [index for index in range(len(mesh.vertices)) if index not in set(magazine_indices)]
    body_group = gun.vertex_groups.new(name="BodyVisual")
    body_group.add(body_indices, 1.0, "REPLACE")
    magazine_group = gun.vertex_groups.new(name="MagazineVisual")
    magazine_group.add(magazine_indices, 1.0, "REPLACE")

    magazine_center = sum((mesh.vertices[index].co for index in magazine_indices), Vector()) / len(magazine_indices)
    bolt_center = Vector((-3.15, -4.2, 8.5))
    armature = make_armature(magazine_center, bolt_center)
    add_armature_binding(gun, armature)
    bolt = create_bolt(bolt_center, bolt_material, armature)
    preview_action = create_preview_action(armature)

    bpy.ops.wm.save_as_mainfile(filepath=str(args.output_blend.resolve()), check_existing=False)

    bpy.ops.object.select_all(action="DESELECT")
    for object_ in (armature, gun, bolt):
        object_.select_set(True)
    bpy.context.view_layer.objects.active = armature
    bpy.ops.export_scene.gltf(
        filepath=str(args.output_glb.resolve()),
        export_format="GLB",
        use_selection=True,
        export_cameras=False,
        export_lights=False,
        export_extras=False,
        export_texcoords=True,
        export_normals=True,
        export_tangents=False,
        export_materials="EXPORT",
        export_draco_mesh_compression_enable=False,
        export_animations=False,
        export_skins=True,
        export_influence_nb=4,
        export_all_influences=False,
        export_morph=True,
        export_morph_normal=True,
        export_morph_tangent=False,
        export_try_sparse_sk=False,
        export_try_omit_sparse_sk=False,
    )

    glb_summary = validate_glb(read_glb_json(args.output_glb.resolve()))
    report = {
        "source": str(source),
        "source_sha256": source_hash,
        "source_actions": source_actions,
        "removed_objects": ["Armature", "Hand1", "Hand2"],
        "component_vertex_counts": sorted((len(component) for component in components), reverse=True),
        "magazine_vertex_count": len(magazine_indices),
        "body_vertex_count": len(body_indices),
        "source_to_bedrock_pixel_scale": factor,
        "transformed_bounds": {
            "minimum": list(transformed_minimum),
            "maximum": list(transformed_maximum),
        },
        "bones": ["BodyVisual", "BoltVisual", "MagazineVisual"],
        "preview_action": preview_action,
        "embedded_animation_exported": False,
        "output_blend": str(args.output_blend.resolve()),
        "output_glb": str(args.output_glb.resolve()),
        "output_glb_sha256": sha256(args.output_glb.resolve()),
        "glb": glb_summary,
    }
    args.report.resolve().write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("NEOTACZ_RIFLE_REPORT=" + json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
