# NeoTaCZ CC0 Rifle Adapter

This narrow Blender CLI adapter turns Robin Lamb's CC0 rifle into a glTF 2.0
core-only NeoTaCZ demo asset.

It creates a rigid three-joint skin:

- `BodyVisual`: static visual body.
- `BoltVisual`: visible bolt proxy driven by the TaCZ `bolt` bone.
- `MagazineVisual`: source magazine component driven by the TaCZ `magazine` bone.

The adapted Blend file contains `NeoTaCZ_BindPreview_DO_NOT_EXPORT` for DCC
inspection. The GLB deliberately exports no animation because the TaCZ Bedrock
state machine is authoritative and `node_map` supplies the live joint poses.

Download the input described in [SOURCE.md](SOURCE.md) separately. The ignored
`source/` and `output/` directories are not part of the repository. Run from the
repository root:

```bash
/Applications/Blender.app/Contents/MacOS/Blender --background --python \
  tools/blender/neotacz_cc0_rifle/prepare_rifle.py -- \
  --input tools/blender/neotacz_cc0_rifle/source/rifle.blend \
  --output-blend tools/blender/neotacz_cc0_rifle/output/neotacz_cc0_rifle.blend \
  --output-glb tools/blender/neotacz_cc0_rifle/output/neotacz_cc0_rifle.glb \
  --report tools/blender/neotacz_cc0_rifle/output/report.json
```

Use this display fragment for the AK-47 integration smoke test:

```json
"render_model": {
  "type": "gltf",
  "location": "tacz:models/gltf/gun/neotacz_cc0_rifle.glb",
  "scale": 0.015625,
  "node_map": {
    "bolt": "BoltVisual",
    "magazine": "MagazineVisual"
  }
}
```

This is a historical local smoke-test fragment, not a modification of the
packaged default AK-47 resource. New integrations should use an
[independent mesh gunpack](../../../docs/mesh-gunpack-format.md) and its own
namespace. Current LOD support is described in the
[rendering guide](../../../docs/gltf-rendering.md).

`0.015625` is the in-game calibrated AK-47 smoke-test scale. The Blender file
keeps the 42-unit Bedrock-pixel-style working size; the display scale converts
that authoring size at render time.

## Validation

The generated GLB is intentionally limited to the NeoTaCZ core subset: triangle
primitives, factor-based PBR materials, one four-influence skin set, and no
extensions, sparse accessors, compressed geometry, or embedded animation.

```bash
npx --yes @gltf-transform/cli@4.2.1 validate \
  tools/blender/neotacz_cc0_rifle/output/neotacz_cc0_rifle.glb

./gradlew --offline --no-daemon test \
  --tests '*Gltf*' \
  --tests '*GunDisplayRenderModelConfigTest' \
  --console=plain
```

The OpenGameArt source includes a whole-rifle `fire` clip. It is not exported:
TaCZ remains the animation authority, while `bolt` and `magazine` are copied to
the named glTF joints by `node_map`.
