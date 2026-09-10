# Public Semantics And Asset Calibration

## Standards Boundary

The implementation uses the [glTF 2.0 transformation contract](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#transformations): right-handed coordinates, metre units, and TRS composition. Section 3.5.3 permits omitting meshes on an all-three-axis zero-scaled node and its descendants. This is a permitted implementation practice, not a requirement to treat every singular matrix as visibility. For skins, that optimization applies only when all skin joints are zero-scaled simultaneously.

Partial zero, near-zero and non-finite transformations are not visibility commands. Restoring a valid scale must restore geometry without replacing the renderer. Hidden geometry must not cause the legacy body to be drawn instead.

[KHR_node_visibility](https://github.com/KhronosGroup/glTF/tree/main/extensions/2.0/Khronos/KHR_node_visibility) is a separate ratified extension. This change does not claim to implement that extension or animation pointers. It implements the core zero-scale convention needed by existing Bedrock animation channels.

For mechanical calibration, [OpenUSD prismatic joints](https://openusd.org/dev/api/class_usd_physics_prismatic_joint.html) define an axis and distance limits; [revolute joints](https://openusd.org/dev/api/class_usd_physics_revolute_joint.html) use angular limits in degrees. Their inherited joint frames describe position and orientation relative to each body. These provide public terminology and explicit coordinate/limit conventions. The calibration JSON is a project tool profile, not an OpenUSD file or claimed industry file format.

No public format standard supplies a universal rifle bolt stroke, handle pivot, magazine trajectory or hand grip. Those values must be measured or authored for each asset and labelled with their provenance. Normalizing a source motion into a target range does not turn an authored limit into a manufacturer specification.

## Reusable Method

The offline calibration tool reads the existing geometry and animation through the same Bedrock parser, interpolators and listeners used by the game. It writes ordinary geometry/animation variants. Mechanical source pivots and travel are calibrated in explicit units; contact targets are specified in glTF node-local coordinates. The original node-map bridge remains responsible for rigid component motion.

Hands remain Minecraft arm models driven by the independent Bedrock hand anchors. Contact measurements include the arm model's reset pose and rotation, not merely the hand anchor origin. The contact point is a declared visual surface convention because Minecraft's cuboid arms do not contain anatomical palm/finger joints.

Contact intervals distinguish approach, contact, release and free motion. They are baked to animation keys rather than introducing gun-name checks or mutating runtime animation state. Magazine movement remains a rigid multi-axis path, not an invented single-axis slider.

Explicit visibility states use Bedrock's documented [discontinuous pre/post keyframes](https://learn.microsoft.com/en-us/minecraft/creator/documents/animations/animationsoverview?view=minecraft-bedrock-stable). This prevents an intended visibility switch from passing through a near-zero squeezed mesh. The conversion is opt-in and accepts only three-component all-zero/all-one scale states; it does not reinterpret arbitrary scale animations.

## This Asset's Values

- Axis/pivot seed: the original shared bolt origins `[-0.237148359, 0.050676525, 0]` and adjacent cylindrical geometry support an X-axis joint. This is asset evidence, not a certified mechanical drawing.
- Translation driver: source `0..8.1` Bedrock pixels maps through normalized `0..1` to `0..2.555746623` pixels. The target equals `0.0960629` glTF metres before the existing render scale. It is an authored visual clearance, chosen as the measured cartridge mesh length `0.07685032` times `1.25`, not a standardized rifle stroke.
- Rotation: preserve the authored `0..50.88` degree handle gesture while relocating the pivot. The source Catmull curve overshot to `-3.70347` degrees despite valid endpoint keys. An explicit bounded-driver bake removes that overshoot without weakening runtime matrix validation.
- Contact targets: actual knob outer surface, magazine side surface, and stock/fore-end cross-sections. MC wide and slim arms share the chosen inner distal face point, so the profile does not assume one skin-arm width.
- Four mappings include the whole body, sliding assembly, handle and magazine. Target absolute overrides keep the detached magazine independent of the body's draw motion; the actual protected hand anchors remain outside the map.

The profile is `calibration-profile.json`. Detailed measured candidates are retained in the ignored `run/bolt-calibration-evidence/measurement.json`. The compact profile records the measurements and their non-physical provenance so the calibration does not depend on hidden Java constants.

## Verification Status

Historical calibration acceptance on 2026-09-05 (Minecraft 26.2, macOS), before
the independent gunpack migration:

- `clean test build`: 109 tests passed, including 15 exact-zero/guard/fallback regression tests.
- Generic baker: two independent X/Z-axis fixtures with different scales/parents passed. Visibility boundaries checked at adjacent floating-point values; invalid and non-opted-in inputs retain their rejection/original semantics.
- Actual bake: 20,552 samples at 480 Hz including intermediate checks; maximum blended contact error `0.000369299494` model units, below `0.001`. Increasing sampling fixed the failed 240 Hz check; the tolerance was not relaxed.
- Actual RenderArmEvent and source matrices: 21,579 frames in 20 closed windows, including 21,404 gun frames. All 12 action-contact intervals and both idle contacts passed. Maximum model-space error `0.000124521352`; maximum render-event-space error `0.000124789059`. Idle contacts were checked in 14,442 frames against bind-returning terminal animation states, not inferred from action labels.
- The same glTF renderer survived two complete empty reloads and one interruption during magazine hiding followed by a hotbar switch back to the gun. The captures include 373 hidden frames and 1,954 later unit-scale frames; no gun frame lost its renderer. Nine draw-switch and 166 interruption frames with no gun metadata/arm submission are recorded separately, not treated as renderer proof.
- Original six JPEGs remain 8192 by 8192 and byte-identical across source, adapted, and installed copies. The adapted glTF/BIN are also unchanged. The final JAR contains neither the probe, offline baker, nor this 8K asset.
- The old replacement installer's six fail-fast cases passed at that time. That
  installer and its overwrite-protection prerequisite are now retired; these
  historical results do not validate the independent ZIP installer.

Current packaging and installation use the [independent gunpack flow](../../../docs/mesh-gunpack-format.md).
The profile's four input locations now identify the repository's original rig
and animation, the adapted external glTF and the independent model ID. Its
scale, node mapping, mechanical limits and contact values are unchanged.
The builder corrects one inherited missing sound ID in its output only; it
does not modify any baked transform/visibility values or the bake input.

Calibration and production regression commands:

```sh
./gradlew --offline --no-daemon clean test build
./gradlew --offline --no-daemon -I tools/calibration/calibration.init.gradle testCalibrationTool
```

Machine-readable local evidence is retained under `run/bolt-calibration-evidence/`:
`baked/report.json`, `runtime-contact-report.json`, `captures/`, `screens/`,
`final-build.log`, `final-selftest.log`, `final-install.log`, and `final-asset-hashes.txt`.
The disposable probe is isolated from main/JAR inputs. Its client was deliberately stopped
after recording (Gradle exit 143), and the temporary focus-pause option was restored.

This is point-contact visual calibration, not finger articulation, elbow IK, collision
avoidance, manufacturer range certification, or proof for every possible cross-clip blend.
The naturally interrupted reload was tested; arbitrary custom animation transitions remain
subject to the existing strict matrix checks. A pre-existing missing magazine-drop sound
warning is outside the contact/visibility changes. No new 8K performance claim is made:
historical raw measurements remain local and are not included in this repository.
