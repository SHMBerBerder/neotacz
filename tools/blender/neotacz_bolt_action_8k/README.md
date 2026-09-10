# NeoTaCZ Bolt Action Rifle 7.62 8K Adapter

This Blender 5.1 CLI adapter turns Poly Haven's official CC0 8K glTF into a
core-only NeoTaCZ mesh asset. It is intentionally pinned to the official
file hashes and topology. A changed source, a different Blender topology, or an
ambiguous magazine selection fails before export.

The script never writes generated assets inside this repository. Its default
output is `/tmp/neotacz-bolt-action-8k-adapted`; use `--force` to replace an
existing external output directory.

```bash
/Applications/Blender.app/Contents/MacOS/Blender --background \
  --factory-startup \
  --python tools/blender/neotacz_bolt_action_8k/prepare_bolt_action_8k.py -- \
  --input-dir /tmp/neotacz-bolt-action-8k-source \
  --output-dir /tmp/neotacz-bolt-action-8k-adapted \
  --force \
  --validator=auto
```

The output contains:

- `neotacz_bolt_action_rifle_7_62_8k.gltf`
- `neotacz_bolt_action_rifle_7_62_8k.bin`
- `textures/` with the six original byte-identical 8192x8192 JPEGs
- `report.json`, `validator.txt`, and a 1280x720 `preview.png`

No `.blend` is emitted. Blender re-imports the final glTF and renders the
preview in the same run, avoiding a second embedded 300 MiB copy of the JPEGs.

## Adaptation Contract

The sections below describe the original geometry adapter and its initial
three-component mapping, not the subsequent mechanical/contact calibration.
The reproducible calibration profile and current acceptance are described in
`CALIBRATION.md`. The independent gunpack also maps `m95` to `GunVisual` so the complete
body follows the authored draw transform; hand anchors remain independent.

- `bullet_54mm` is removed; body, scope, wrap, trigger, and both bolt pieces
  remain.
- The real magazine is separated from the body only after its 90 imported
  UV-split components, 928 imported vertices, 392 unique positions, 772
  triangles, exact world AABB, and zero boundary polygons all match.
- The 234-vertex, 280-triangle trigger guard is independently recognized and
  excluded.
- `GunVisual` is the only scene root. In Blender it carries Z +90 degrees and
  translation `(0, 0.374779494, -0.031283695)`.
- The model keeps unit scale. Use `1.662808055` as the initial M95
  `render_model.scale`; it is not baked into geometry.
- Output is core glTF 2.0 with TRIANGLES only and no Skin, animation, Morph,
  TANGENT, sparse accessor, camera, or extension.

Target hierarchy:

```text
GunVisual
|- BoltAssemblyVisual
|  |- BoltAssemblyGeometry        (official bolt_b, 1,089 triangles)
|  `- BoltHandleVisual
|     `- BoltHandleGeometry       (official bolt_a, 1,906 triangles)
|- GunBodyGeometry
|- MagazineVisual                 (direct mesh, 772 triangles)
|- ScopeGeometry
|- TriggerGeometry
`- WrapGeometry
```

The current independent pack uses this bridge:

```json
"render_model": {
  "type": "gltf",
  "location": "bolt_action_mesh:models/gltf/bolt_action_rifle_762/neotacz_bolt_action_rifle_7_62_8k.gltf",
  "scale": 1.662808055,
  "node_map": {
    "m95": "GunVisual",
    "m95_bolt": "BoltAssemblyVisual",
    "rotate": "BoltHandleVisual",
    "mag_and_bullet": "MagazineVisual"
  }
}
```

`lefthand_pos` and `righthand_pos` are deliberately absent. The TaCZ Bedrock
rig remains authoritative for both Minecraft arm anchors, the state machine,
attachments, muzzle, and fallback.

## PBR Output

The six official JPEG URIs are preserved under `textures/`. Duplicate
accessories diffuse image entries are merged. Each ARM texture is used by both
`metallicRoughnessTexture` and `occlusionTexture`; the accessories glass keeps
`BLEND` with base-color alpha `0.25`.

The sampler is explicitly `LINEAR` min/mag with `REPEAT` wrapping
(`9729/9729/10497/10497`). This is a deliberate NeoTaCZ compatibility
normalization of the official mipmapped minification sampler. It does not claim
that the current renderer generates or samples mipmaps.

Six decoded RGBA8 base levels require 1.5 GiB before driver overhead. This is a
high-memory asset. Client resource budgets are configured by the user, not by
the gunpack and not by an exact model-name allowlist.

## Validation

### Independent Gunpack Installation

The default-M95 replacement workflow is retired. Build and install the real
`bolt_action_mesh` ZIP using [the mesh gunpack format](../../../docs/mesh-gunpack-format.md).
It contains its own index, data, display, calibrated rig/animation and complete
8K mesh assets. The existing default gunpack supplies explicitly checked shared
resources. No `DefaultPackDebug` protection or Python TOML preflight is required.
`install-calibration.sh` is only a compatibility entry for the ZIP installer;
it no longer bakes or copies files over default resources.

The calibration profile now reads the repository's original default rig and
animation plus the external adapted model; it does not read development assets
installed inside the default pack. The build includes actual files in the ZIP,
so the installed gunpack has no `/tmp` dependency.

### Adapter Checks

The run checks all official input hashes and sizes, output texture byte
identity and 8192 dimensions, the 19,365-triangle result, material-to-primitive
mapping, node uniqueness and geometry ownership, one scene root, supported
feature subset, external buffer size, and the final Blender re-import. When
available, `--validator=auto` runs glTF Transform 4.2.1 backed by the Khronos
validator and writes its full output to `validator.txt`.
