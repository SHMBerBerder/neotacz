# Mesh Gunpack Format

Mesh guns use the existing NeoTaCZ gunpack loader. There is no second loader
and no replacement of default gun resources. A pack is a directory or ZIP
whose **root** contains `gunpack.meta.json`; do not wrap the ZIP in another
folder. Each pack owns a unique lowercase namespace other than `tacz` or
`minecraft`. A gun's ID is `<namespace>:<index filename without .json>`.

## Pack Layout

The runtime requires the existing metadata and referenced game/display assets.
`PROVENANCE.md` and `mesh-pack-manifest.json` below are this example builder's
offline audit files, not new requirements imposed on every existing gunpack.

```text
gunpack.meta.json
PROVENANCE.md
mesh-pack-manifest.json
data/<namespace>/index/guns/<gun>.json
data/<namespace>/data/guns/<gun>.json
assets/<namespace>/gunpack_info.json
assets/<namespace>/lang/en_us.json
assets/<namespace>/lang/zh_cn.json
assets/<namespace>/display/guns/<gun>.json
assets/<namespace>/geo_models/gun/<gun>.json
assets/<namespace>/animations/<gun>.animation.json
assets/<namespace>/models/gltf/<gun>/<model>.gltf OR <model>.glb
assets/<namespace>/models/gltf/<gun>/<buffer>.bin             # if external
assets/<namespace>/models/gltf/<gun>/textures/<image>.jpg     # if external
assets/<namespace>/models/gltf/<gun>/textures/<image>.ktx2    # if external
```

A pack with independent attachment items also uses the existing
`data/<namespace>/index/attachments/`, `data/<namespace>/data/attachments/` and
`assets/<namespace>/display/attachments/` trees. Its referenced attachment tags
and optional Bedrock semantic assets must be packaged or resolve through the
declared template dependencies; these are not a second mesh pack format.

`gunpack.meta.json` is the existing loader metadata, not a new schema:

```json
{
  "namespace": "bolt_action_mesh",
  "dependencies": {"tacz": "[1.1.8-neoforge-mc26.2,)"}
}
```

The index has `display` and `data` resource IDs. Display `model` identifies the
Bedrock rig below `geo_models/`, without `.json`; `animation` identifies the
file below `animations/`, without `.animation.json`. `render_model.location`
is the complete namespaced glTF path, including `.gltf` or `.glb`. Buffers/images use
relative local URIs inside that model directory. No absolute, parent, escaped,
network, encoded traversal or external file URI is permitted by this builder.
GLB can embed buffers and images. Draco data is carried in standard buffer
views; KTX2 images use `KHR_texture_basisu`. Renaming a JPEG/BIN or adding an
extension declaration is not compression.

The Bedrock rig remains the action/attachment/Minecraft-hand authority. Mesh
nodes are rigid body targets through `render_model.node_map`. Do not map the
protected left/right hand anchors. Author pivots, contact trajectories and
temporary hiding into the rig/animation using the existing calibration tool.
Geometry coordinates, shader texture budgets and mechanical limits are not
inferred from a gun's name. Packs cannot raise the client's resource budgets.
`render_model.type = "gltf"` selects the mesh body and automatic 3D-to-2D atlas
icon; it needs no separate icon configuration. The example omits legacy
`lod`, `slot`, `hud` and `hud_empty` gun visuals. The retained `model` and
`texture` describe the compatibility rig, not an alternative visible M95 body.
Existing Bedrock-only packs keep their original model, distance LOD and icons;
they do not need a mesh manifest or a mesh conversion.

## Mesh Attachments

The general `AttachmentDisplay` accepts the same managed `render_model`
location and scale contract as a gun body, without a model-name exception:

```json
{
  "render_model": {
    "type": "gltf",
    "location": "example:models/gltf/attachment/grip.glb",
    "scale": 1.0
  }
}
```

Nonempty attachment `node_map` is rejected. Optional Bedrock `model`/`texture`
and optical metadata can remain as a functional semantic rig, but the declared
mesh owns the visible body. Pending or failed mesh attachments do not draw the
old body. Their inventory icons use the same fixed-default-pose native GUI
atlas and missing placeholder as mesh guns, with no additional icon PNG cache.

Installed mesh coordinates start at the mount origin supplied by the existing
gun attachment anchor. The renderer applies `scale(-s,-s,+s)` without a gun root
or the legacy Bedrock 24/16 offset. Any offset needed by the optical semantic
rig stays in its separate branch. Authors must establish the mesh mount origin
and orientation; bounding-box centering is not mounting or contact calibration.

Quality changes include mesh attachments in the same staged metadata
publication, invalidation and requested CPU/GPU preparation as gun bodies.
Non-mesh attachment indexes remain unchanged. Installation/removal keeps the
existing Z refit screen and server-validated item/slot/allow-list/lock flow;
there is no mesh-specific installation protocol. Two-rifle/two-attachment
installation/removal and inventory checks passed. After sampler/mip changes,
separate Pistol, mip and installed-attachment checks were completed; see the
[acceptance report](public-mesh-acceptance.md). Raw transcripts and screenshots
remain local rather than being included in the source repository.

## Public Mesh Showcase

The independent local pack template is
[`gunpacks/public_mesh_showcase/pack.json`](../gunpacks/public_mesh_showcase/pack.json),
with provenance in
[`gunpacks/public_mesh_showcase/PROVENANCE.md`](../gunpacks/public_mesh_showcase/PROVENANCE.md)
and its builder/check commands in
[`tools/gunpack/public_mesh_pack/README.md`](../tools/gunpack/public_mesh_pack/README.md).
The output is `build/public-mesh-pack/public_mesh_showcase-1.0.0-local.zip`.

The tested package has SHA-256
`4bbdd5c400bd8422c24a3a5f3f1072bb001294112976816a876ee6e077d17ff2`.
It passed 54 offline checks and runtime-loader validation of 43 files.
These checks establish package/loader gates, not Minecraft rendering by
themselves. Generated archives and raw validation receipts remain local.

| Item ID | Public mesh | Existing gameplay/rig template |
| --- | --- | --- |
| `public_mesh_showcase:chassis_sniper` | 3DAssets asset 32989 | M700 |
| `public_mesh_showcase:marksman_rifle` | 3DAssets asset 32981 | MK14 |
| `public_mesh_showcase:service_pistol` | Poly Haven Service Pistol, variant A | Glock 17 |
| `public_mesh_showcase:vertical_grip` | 3DAssets asset 33034 | Vertical Talon grip |
| `public_mesh_showcase:suppressor` | 3DAssets asset 33029 | Vulture suppressor |

The four 3DAssets inputs come from the
[Mega Weapon Pack](https://3dassets.dev/packs/mega-weapon-pack); the textured
pistol comes from [Poly Haven Service Pistol](https://polyhaven.com/a/service_pistol).
The template pins source hashes, asset IDs and coordinate adjustments. Source
mesh licenses are recorded as CC0 by those providers; inherited TACZ game data,
rigs and animation retain their separate CC BY-NC-ND provenance. This mixed
derivative remains a local private test pack, not an all-CC0 redistribution.

The two rifles expose this pack's grip/suppressor attachment tags. The pistol
does not acquire unprovided attachment sockets. Mount values are author/template
metadata, not a standardized glTF socket extension or proof of physical contact.
Retained animations do not automatically synchronize authored glTF clips with
game reloads or calibrate hands. An optional glass scope requiring unsupported
transmission is deliberately not part of the usable attachment set.

The source's standard `KHR_mesh_quantization` is retained and decoded by the
shared runtime attribute rules for POSITION/NORMAL/UV0 and morph
POSITION/NORMAL. This does not imply TANGENT, UV1 or texture-transform support.
The pistol's original minFilter 9987 (`LINEAR_MIPMAP_LINEAR`) is now admitted by
the shared sampler/mip implementation without changing the source asset.
Raw and packaged CPU intake passed original and 2K preparation for all five
usable assets: ten asset cases,
plus one explicit optional-transmission scope skip. The latest-source second
Minecraft pass also shows the Pistol in first person, hotbar and Z refit, with
three 2048x2048 RGBA8 textures containing 12 actual mip levels. The observed
upload sampler retains min 9987, mag 9729 and REPEAT; this is a bounded scene
result, not every per-draw sampler or every visual path. Evidence and accounting
limits are recorded in [Mesh Video Quality](mesh-video-quality.md#current-native-evidence).

Supported samplers have mag NEAREST/LINEAR, min NEAREST/LINEAR or
NEAREST_MIPMAP_LINEAR (9986)/LINEAR_MIPMAP_LINEAR (9987), and independent S/T
REPEAT/CLAMP_TO_EDGE. Min 9984/9985 and MIRRORED_REPEAT remain rejected because
the native API cannot express them exactly; do not rewrite an input's sampling
to bypass rejection. Per-slot samplers enter the material/RenderType identity.
Actual mip levels use role/alpha/wrap-aware CPU filtering, upload and release
their pixels, and count every uploaded level against the GPU budget. MC's
nonzero-dimension prefix ends at 1x1 for square images, at 1x2 for 3x5, and at
the base level for 1x8; it is not an arbitrary rectangular full tail. Mip
storage identity includes role/alpha/wrap and filter versions, while equal
non-mip encoded images may share storage. Details are in
[Mesh Video Quality](mesh-video-quality.md#samplers-and-uploaded-mips).

Builder self-tests and `validate-runtime` are offline gates. The latter opens
packaged models through the runtime loader and checks their dependency closure;
it does not establish final texture filtering, GPU upload, GUI pixels, installed
attachment placement or Z interaction. The README's self-test count is not a
Minecraft acceptance result. Latest-source Pistol/mip, installed attachments,
inventory, third-person and five FIXED/five GROUND item evidence is separate
from these offline checks. Native 512/2K generation changes removed the old
size from owned GPU descriptors; this is not a driver-VRAM measurement. The
17-submission display run averaged 33.9 frames/s, not a claim of a dozen guns
running smoothly. The 512 retest recorded all three held parts in 2,003/2,003
frames; window 2's original 117-frame suppressor gap remains recorded with no
retrospective cause attribution. Existing Bedrock 8x scope checks on AK47 and mesh Bolt do not admit the
excluded transmission scope. See the linked native evidence for exact limits.

## Actual 8K Pack

Template: `gunpacks/bolt_action_mesh/pack.json`.
Gun ID: `bolt_action_mesh:bolt_action_rifle_762`.
Output: `build/gunpacks/bolt_action_mesh-1.0.0-local.zip`.

Build from the adapted source and the verified offline bake:

```sh
./gradlew --offline --no-daemon -I tools/calibration/calibration.init.gradle calibrateAsset \
  -PcalibrationProfile=tools/blender/neotacz_bolt_action_8k/calibration-profile.json \
  -PcalibrationOutput=run/bolt-calibration-evidence/baked \
  -PcalibrationMaxEncodedImageMiB=64 -PcalibrationMaxModelImagesMiB=384
./gradlew --offline --no-daemon -I tools/gunpack/gunpack.init.gradle buildMeshGunpack
./gradlew --offline --no-daemon -I tools/gunpack/gunpack.init.gradle testMeshGunpackTool
```

The builder also accepts `-PmeshSource=<adapted-directory>`,
`-PmeshBaked=<baked-directory>` and `-PmeshPack=<output.zip>`. `/tmp` is a build
input convention only. The ZIP contains the actual glTF, BIN and all six
original 8192 x 8192 JPEGs. The source hashes are pinned in the template;
the builder never re-encodes images or writes back to its inputs.

## Encoded Derivatives

The same Bolt-specific builder can package an already encoded derivative;
it does not become a general gun generator or change calibration/game data:

```sh
./gradlew --offline --no-daemon -I tools/gunpack/gunpack.init.gradle buildMeshGunpack \
  -PmeshSource=<staging-directory> \
  -PmeshAsset=<staging-directory>/encoded/rifle.glb \
  -PmeshAssetProvenance=<staging-directory>/encoding-receipt.json
```

Both optional arguments are required together. The source directory still
contains every pinned original file at the paths in `pack.json`. Put the
derived model in that directory or a subdirectory. Its external dependencies
must stay inside its **own model directory**, including after symlink
resolution. The builder follows only references from that model, not a
recursive scan of the source tree. Only the derived `.gltf`/`.glb` and that
dependency closure enter the ZIP; original 8K files are not redundantly copied.

The encoding receipt has exactly these fields:

```json
{
  "tool": "gltf-transform",
  "version": "4.5.0",
  "options": {"draco": {"method": "edgebreaker", "quantizePosition": 14}},
  "source_files": {"...": "copy the complete source_files object from pack.json"}
}
```

The abbreviated `source_files` above must be replaced with the complete pinned
map. `options` contains structured numeric/boolean settings and short path-free
string values, not shell commands, absolute paths, URLs or credentials. Record
the actual encoder version and all stages/options used. The tool verifies the
original input bytes and receipt source hashes against its trusted template;
the receipt is provenance supplied by the operator, not a signature or proof
that an arbitrary derivative preserves the original shape.

Derived assets use manifest v2 with `asset_mode: "derived"`, `derivation` (the
receipt), and `asset_files` (relative actual payload paths to SHA-256). The
existing `files` table still hashes every packaged file and `source_files`
still identifies the original inputs. The original byte-identical mode stays
manifest v1; its six-JPEG/8192 checks are not applied to v2. Both formats can
be validated and installed by the same tool.

Derived validation reuses the runtime glTF/GLB loader, including required
extension rejection and geometry decoding, but does not render or invoke the
final texture filtering/transcoding stage. Unnamed nodes are valid when not
mapped; each mechanical mapping must still resolve to exactly one named node.

At runtime, unchanged-size KTX2 retains its compressed source and does not need
a same-size PNG. A GPU cache miss first enforces the existing budgets, then uses
libktx to copy RGBA base pixels into an independent NativeImage, destroys the
libktx owner and uploads/releases the image. The copy briefly holds two native
buffers. Actual downsampling still produces a disk-cached PNG. GPU warm hits
avoid retranscoding, but a reload followed by a same-size cold upload does not;
this is neither GPU-compressed upload nor source-mip streaming. All budgets
remain unchanged. P8 addresses the old Bolt's oversized same-size PNG, not the
public Pistol sampler. The successful standard build is complemented by
original-8K stability, repeat cold reload and final-2K
native checks: no per-frame retranscoding and no old 8K descriptor after the
2K return. The [acceptance report](public-mesh-acceptance.md) records the exact
GPU/encoded counts and substantial whole-process cold peak separately. It is
not a promise that many different 8K guns fit in the measured capacity.

Preserve node hierarchy, pivots, mapped names, material semantics, skin/morph
data and the calibrated animation. Do not use an encoder's broad
flatten/join/prune operation as a substitute for targeted compression.
Verify an actual required-extension compressed fixture with no uncompressed
fallback, decoded geometry/images, contact trajectories and final rendering
before claiming compressed runtime support. Container self-tests alone do not
prove KTX2/Draco decode or visual quality.

Install the already-built ZIP while the client is stopped, then start/reload
the normal gunpack loader:

```sh
sh tools/gunpack/install.sh \
  build/gunpacks/bolt_action_mesh-1.0.0-local.zip run/client_a/tacz
```

The installer validates dependencies in the **target** `tacz_default_gun`
directory, then atomically replaces only its own ZIP. It never edits the
default display, data, models, animations or `DefaultPackDebug`. Do not install
original and derived variants simultaneously under different filenames: they
share one namespace/gun ID. The former
`install-calibration.sh` delegates to this ZIP-only installer and requires the
same two arguments. Existing development overrides must be removed through a
separate verified migration; the installer does not guess which files to delete.

## Dependencies And Licensing

This add-on has its own index/data/display/rig/animation/model files. It shares
the default pack's state machine and its default-state-machine dependency,
sounds, ammo plus ammo/shell models and ammo icons, the compatibility rig's UV
texture, and muzzle flash. It does not share the M95 gun LOD, slot or HUD image.
The mod supplies the built-in default rifle animation. The validator resolves
every typed reference in this template, including animation sound effects and
runtime-inserted default sounds. Every required external file is listed in
`mesh-pack-manifest.json.shared_resources`, with its provider. Missing files
fail the build/install; `meta.dependencies` only checks mod versions and does
not enforce gunpack resource dependencies at runtime.

Game data is the existing M95 template, including `tacz:50bmg`; the 7.62 asset
name is not a claim that the template represents real firearm parameters.
The mesh/images are Poly Haven CC0. TACZ-derived rig/animation/game data retain
the default pack's CC BY-NC-ND 4.0 provenance. This mixed derivative is for
local private testing, not an all-CC0 release. `PROVENANCE.md` documents the
origins and the single corrected inherited sound reference.

## Integrity And Limits

The template-specific tool uses the project's lenient Gson reader for JSONC
and Java ZIP APIs. It rejects wrapped roots, duplicate/unsafe entries, invalid
namespaces, foreign/default namespace writes, missing own/shared references,
missing mapped bones/nodes or hand anchors, altered source hashes, original-mode
image dimension/buffer size mismatches, and CRC mismatches. Derived mode also
checks model closure completeness, actual asset hashes and encoding receipts.
`files` covers every
ZIP member except the manifest itself, using size/SHA-256/CRC32. This detects
corruption; it is not a cryptographic signature or a general validator for
arbitrary third-party scripts/extensions.

Entries are sorted, stored without recompression, and carry fixed local DOS
timestamps, making repeated builds byte-identical for identical inputs. The
builder's limits are 128 MiB per entry, 32 MiB per audit JSON and 512 MiB total.
Derived loading also obeys the runtime loader's 64 MiB main-model input limit,
so a large embedded GLB may need external images instead. These
are offline tool limits, not GPU or runtime pack-policy overrides. Six 8K
RGBA8 base levels still represent 1.5 GiB of logical GPU texture payload.
Sharing repeated instances does not make fifteen different image sets free.

Tool classes and source assets are separate from the mod JAR. Full runtime
loading and rendering remain separate acceptance gates from ZIP validation.

For this 8K pack, explicitly configure the client's existing `[resource]`
section with `GltfMaxEncodedImageMiB = 64`, `GltfMaxModelImagesMiB = 384` and
`GltfGlobalGpuTextureMiB = 2048` before starting the client. These are general
client-owned budgets, not model-name exceptions. The resource index is now
lazy rather than a permanent all-pack byte snapshot. A single model read
still has an independent 512 MiB encoded-resource limit. Raising the GPU
budget does not bypass that limit or prove support for fifteen distinct 8K
image sets; a fifteen-instance scene can share one model/texture set.

Video quality is separate from these hard input budgets. See
[`mesh-video-quality.md`](mesh-video-quality.md) for native video settings,
authored `MSFT_lod`, automatic geometry simplification and derived texture
caching. Original source images remain in the gunpack when quality is lowered.

Ordinary runtime conversion permits at most 1,000,000 source vertices and
3,000,000 source indices before selection/simplification, with independent
16,000,000-accessor-component and 64 MiB-per-buffer / 128 MiB-total-buffer
bounds. After authored LOD and eligible simplification, selected node instances
must fit 300,000 emitted vertices / 100,000 triangles. The Draco child decoder
retains its narrower aggregate 250,000-vertex / 300,000-index limit and 256 MiB
heap cap. A small compressed file or a lower texture setting does not bypass
these geometry limits, and a child heap cap is not a process-RSS guarantee.

Mesh world/item-display quality is selected through the mesh quality/LOD
pipeline, not an unrelated Bedrock gun silhouette. `GunLodRenderDistance`
continues to control legacy Bedrock-only displays; it is not a switch back to
the M95 body for this mesh pack.
