# Offline Visual Calibration

This tool produces ordinary Bedrock geometry and animation JSON. It is not loaded by
Minecraft and its classes are not added to the mod JAR. Source resources are never
overwritten. The six original 8K JPEGs are read by the existing glTF loader and are
not modified or re-encoded.

## Public Conventions And Limits

- The terms prismatic/revolute, local joint axis, and lower/upper limits follow
  [OpenUSD joint conventions](https://openusd.org/dev/api/class_usd_physics_joint.html).
  This JSON profile is a project tool format, **not a USD schema or USD importer**.
- Limits are measured visual-asset parameters. There is no assumed universal
  physical travel distance for all firearm models. Their provenance is required.
- Contact influence and offline baking follow the established DCC workflow of
  [Child Of constraints](https://docs.blender.org/manual/en/latest/animation/constraints/relationship/child_of.html).
  The tool uses JOML transforms and the actual project Bedrock interpolators, rather
  than a second interpolation implementation or a runtime per-model exception.
- The tool calibrates one explicit contact point on each Minecraft arm. It does
  not solve finger articulation, elbow IK, collisions, or real-world mechanisms.

## Run

```sh
./gradlew --no-daemon -I tools/calibration/calibration.init.gradle testCalibrationTool
./gradlew --no-daemon -I tools/calibration/calibration.init.gradle calibrateAsset \
  -PcalibrationProfile=/absolute/path/profile.json \
  -PcalibrationOutput=run/calibration-output
```

Image budgets default to 16 MiB per image and 64 MiB per model. Original 8K
assets can explicitly use `-PcalibrationMaxEncodedImageMiB=64` and
`-PcalibrationMaxModelImagesMiB=384`. These numeric limits apply only to
`calibrateAsset`, not the self-test or client. Model-ID-based overrides are retired.
Generated files are `geometry.json`, `animation.json`, and `report.json`.
Paths in `inputs` are relative to the profile file, or may be absolute.

## Profile Version 1

```json
{
  "version": 1,
  "sample_hz": 240,
  "max_contact_error": 0.0005,
  "inputs": {
    "geometry": "source.geo.json",
    "animation": "source.animation.json",
    "gltf": "model.gltf",
    "model_id": "example:models/gltf/model.gltf",
    "render_scale": 1,
    "base_clip": "static_idle",
    "node_map": {"slide": "SlideVisual"}
  },
  "pivots": [{"bone": "slide", "pivot_bedrock_pixels": [0, 8, 0]}],
  "visibility_steps": [{"clip": "reload_empty", "bone": "magazine"}],
  "joints": [{
    "bone": "slide", "kind": "prismatic", "axis": "Z",
    "clips": ["operate"], "source_range": [0, 8], "target_range": [0, 2],
    "unit": "bedrock_pixels", "provenance": "Measured visual mesh extent; not a physical specification"
  }],
  "bind_contacts": [{
    "hand_anchor": "righthand_pos",
    "hand_point_after_arm_model": [-0.312708, 0.753117, 0],
    "target_node": "SlideVisual", "target_point_local": [0, 0, 0],
    "reference_clip": "static_idle", "reference_time": 0
  }],
  "contacts": [{
    "clip": "operate", "hand_bone": "righthand", "hand_anchor": "righthand_pos",
    "hand_point_after_arm_model": [-0.312708, 0.753117, 0],
    "target_node": "SlideVisual", "target_point_local": [0, 0, 0],
    "interval": [0.1, 0.2, 0.8, 0.9]
  }]
}
```

All coordinates and sample times must be finite. Times are seconds. Geometry
pivots and prismatic ranges use Bedrock pixels (16 pixels per rendered model unit).
Revolute ranges use degrees and `kind: "revolute"`, `unit: "degrees"`.
Axes are local source-bone X/Y/Z; pivot calibration does not infer an arbitrary
joint frame from mesh bounds. Model-specific axis/pivot/contact measurements belong
in the data profile, never in Java branches. Ranges must be increasing.

Joint `limit_policy` defaults to `reject`: existing interpolation is retained and
out-of-range sampled motion fails verification. Explicit `"clamp_and_bake"` samples
the original project interpolator, bounds its normalized scalar driver to `[0,1]`,
then maps to the target range and writes linear keys. This option requires a genuine
single-axis channel and continuous pre/post values; multi-axis paths are rejected.
Original keys must already be inside the declared source range. The report records
clamped sample counts and the maximum observed source overshoot. Re-read output is
checked at twice the sample frequency, original key times, and adjacent midpoints.

Optional `visibility_steps` explicitly declares selected scale channels as Boolean
visibility, using the public Bedrock
[discontinuous pre/post keyframe semantics](https://learn.microsoft.com/en-us/minecraft/creator/documents/animations/animationsoverview?view=minecraft-bedrock-stable).
Each source key must be a numeric three-axis array of all zeros or all ones. Scalar,
mixed/nonuniform, intermediate values, existing pre/post objects, missing channels,
and timestamp collisions are rejected. The tool writes LINEAR keys with
`pre=previous state` and `post=current state` (first key uses its own state on both
sides), preventing accidental tiny nonzero scales during hide/show transitions.
Unselected scale channels are untouched. Re-reading verifies sample points plus
the preceding float, exact float, and following float at every boundary. This is
an explicit project-profile choice, not automatic reinterpretation of all scaling.

`target_point_local` is in the named glTF node's local coordinates. The real
`GltfNodeMapBridge` and `GltfGunBodyPose` compute its current world transform, with
the same outer `scale(-s,-s,s)` used by rendering. Hand points include the Minecraft
arm model's bind translation and arm z rotation, but precede the submitter's
`Rz(pi)`. Both sides are compared root-relative, canceling the shared gun/view pose.

`bind_contacts` move leaf hand-anchor pivots in geometry, using the optional
reference animation pose. Bridge binding always happens first in the clean bind
pose. This makes the grip offset static, not repeatedly added by idle/action tracks.
`base_clip` is sampled at time zero before an action clip; action channels override
base channels as the non-blending controller tracks do. It is never applied twice
when sampling the base clip itself.

Contact intervals are `[blendInStart, heldStart, heldEnd, blendOutEnd]`. Influence
uses smoothstep ramps and is exactly one during the held interval. Outside the
interval, the authored trajectory on the calibrated geometry is retained. Same-hand
intervals must not overlap. Active constraints against hidden/singular targets fail
rather than pretending that a collapsed magazine still has a usable contact frame.

The tool retains unmodified rotation/scale and unrelated channels. It samples hand
translation at the requested rate plus original key times and interval boundaries,
then re-parses the output and checks every sample and adjacent midpoint. The report
contains held-contact and blended-trajectory error in rendered model units, plus
mechanical ranges sampled using the actual interpolators. Limits and error tolerance
are fail-closed. An offline PASS does not prove action-transition blending, runtime
rendering, or visual plausibility; those require the real Minecraft frame probe.
Each bind/contact report also exports a `runtime_reference`: the closest mapped
source bone and its clean-bind-local contact point. Multiplying that point by the
actual source world matrix reproduces the bridge target, including descendant-node
bind transforms. A target with no mapped ancestor is rejected for this proof path.
