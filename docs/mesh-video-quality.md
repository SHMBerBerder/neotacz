# Mesh Video Quality

Minecraft's Video Settings contains **NeoTaCZ Video Settings**. Done saves and
applies the draft; Cancel/Escape discard it; Reset changes only the draft.
The five presets fix all three parameters. Explicitly selecting Custom unlocks
the sliders and inherits the current effective values; clicking a disabled
slider never implicitly changes the preset. Six direct choices sit above three
compact setting rows with short, plain-language descriptions. The selected
preset has a colored underline; keyboard focus has a separate outline.
The layout fits the native 320x240 logical minimum without scrolling, including
English labels. Values are stored in the
client's `tacz-client.toml`, under `[video]`.

The native layout and input were checked in Chinese and English, including
the minimum logical window size. Raw screenshots and historical validation
records remain local; the published [acceptance report](public-mesh-acceptance.md)
summarizes the current model and quality checks.

## Applying A Change

Done uses Minecraft's native resource-loading overlay when the effective quality
changes. Model/LOD/animation preparation runs asynchronously, followed by
render-thread uploads for the selected mesh materials (including temporarily
hidden parts). The progress bar is weighted completed work, not elapsed time or
an estimated number of seconds. A single native image decode/upload batch cannot
be interrupted to redraw the bar.

Completion returns to the original Minecraft Video Settings screen. An unchanged
draft, or changing values and then changing them back, skips both saving and
loading. A preset-label-only change with identical effective quality saves without
reloading. Cancel/Escape discard the draft. During loading, an input-blocking
native screen prevents Escape from leaving a partially prepared setting.

This is a quality-only reload, not a full resource-pack reload: it stages gun
displays and mesh attachment indexes together, reuses non-mesh attachment
indexes, and preserves other pack indexes. Previously requested gun/attachment
loads and current inventory targets are warmed rather than every unused asset.
Requested mesh attachments participate in both CPU preparation and selected
material GPU upload progress. Quality reloads stay asynchronous even if ordinary
lazy loading was disabled. Metadata is staged before saving;
after the complete replacement tables are published, old resources are retired
without keeping a second full 8K set alive. Preparation or upload failures show a
native error screen instead of reporting success. A post-publication failure
retains the complete new metadata tables; it does not roll back old GPU resources.
Disconnecting or opening an unrelated screen prevents stale navigation callbacks.

Native checks cover real progress, unchanged drafts, navigation and a deliberate
GPU-budget failure. See the [acceptance report](public-mesh-acceptance.md) for
measured texture residency and the limits of the tested multi-item scene.

| Preset | Maximum texture edge | Authored LOD | Automatic triangle target |
| --- | ---: | ---: | ---: |
| Smooth | 512 | 3 | 15% |
| Normal | 1024 | 2 | 30% |
| High (default) | 2048 | 1 | 50% |
| Very High | 4096 | 0 | 75% |
| Ultra | 8192 | 0 | 100% |
| Custom | 512..8192, powers of two | 0..4 | 10..100% |

Chinese labels are 流畅、普通、高、极高、超高、自定义, in that order.
Only the presentation changed: existing enum/config keys SMOOTH, LOW, BALANCED,
HIGH, ORIGINAL and CUSTOM retain their original meaning and numbers. The old
Balanced 2K default is now displayed as High, not increased to 4K. Reset still
selects this 2K default. The triangle percentage is a target, not an achieved
reduction or a percentage of visual quality.

### What Interacts

- Texture limits affect the longest uploaded image edge and real RGBA texture
  residency, not screen resolution. An 8192-square RGBA8 base level is 256 MiB;
  the corresponding 2048-square base level is 16 MiB. Requested mips add their
  actual level sizes, about 341.3 MiB and 21.3 MiB respectively for those square
  chains. This is per-image storage, not a
  promise of 16x lower whole-client memory or faster frame rate. Source images
  already below a cap are unchanged.
- Authored LOD selects both model and material alternatives before texture
  scaling. Without an authored chain the level does nothing; levels beyond the
  available chain choose the same final alternative. A low-level 1K material
  stays 1K even with an 8K texture cap. Raising the cap cannot recover details
  that were removed by the selected model or material.
- The automatic triangle target applies only to eligible rigid mesh primitives
  without authored geometry LOD. Authored LOD meshes are not simplified again;
  skin/morph primitives are preserved. Thus a model entirely in those protected
  categories sees no geometry change from this slider. Error and boundary limits
  can also produce the same result at different targets. Mixed models can still
  simplify their eligible unprotected parts.
- Texture and eligible geometry reductions target different costs and can both
  help. They do not remove draw calls, rig animation, all decoding work, or other
  world-rendering costs. Increasing a setting that is not the current bottleneck
  need not measurably change frame rate.
- These options do not write Minecraft's world distance, display resolution,
  filtering or mipmap settings. Mesh PBR uses each authored texture slot's
  supported sampler and generates mips when that sampler requests them;
  vanilla filtering/mipmap choices do not replace it. Other global settings can
  still affect overall frame cost and visibility.

These are engineering presets, not an industry-wide quality scale. An image
smaller than the maximum is never enlarged. Geometry ratio and texture size
are independent. For declared mesh guns, all existing body entry points use the
mesh's selected quality, including first person, third person/player previews,
ground and FIXED displays/back-mounted guns. The legacy display-level Bedrock
distance LOD is not loaded or drawn for these guns. Non-mesh guns retain that
older distance-LOD path.

Declaring `render_model.type = "gltf"` makes the mesh authoritative for the gun
body. A pending or failed mesh does not turn into its rig's placeholder body,
another gun's old LOD, or a legacy gun icon. The Bedrock rig still owns operation
animations, hands and functional/attachment anchors; it is not a backup mesh.

Mesh attachments use the same loader and quality snapshot through the general
`AttachmentDisplay.render_model.location`/`scale` contract. Mounted geometry
starts at the existing attachment anchor without a gun root or legacy 24/16
offset. Optional Bedrock metadata remains the optical/functional semantic rig,
not a fallback visible attachment body. Attachment `node_map` is rejected.
Installing/removing these items still uses the existing Z refit UI and
server-validated messages; quality settings do not introduce an installation
protocol. See [the pack format](mesh-gunpack-format.md#mesh-attachments).

## Inventory Icons

Compressed Bolt and public showcase runtime checks cover model-generated icons
and quality reloads. These do not establish performance for many different guns
or every material; see the [acceptance report](public-mesh-acceptance.md).

Mesh guns and mesh attachment items submit a fixed default-pose mesh to Minecraft's native GUI item
atlas. Normal/creative inventory slots, the hotbar and the mesh HUD reuse the
resulting 2D item region. There is no additional screenshot texture cache, GPU
readback or per-frame icon PNG encoding. Enchanted items retain Minecraft's
animated-item handling for foil.

The icon excludes live bolt/reload poses, embedded animation sampling, hands
and functional effects. A common three-quarter view fits the actual projected
mesh bounds; the fitted frame cancels vanilla ItemTransform's half-block
translation (including NO_TRANSFORM) to avoid clipping the atlas slot. Static bodies reuse prepared geometry, while
animated/mapped bodies prepare their default-pose icon geometry once.

Resource/quality replacement and pending-to-ready transitions change a stable
identity token in the native atlas key. That token does not reference the
renderer or obsolete CPU assets. GUI extraction only schedules background work,
even if ordinary lazy loading is disabled. Pending or failed mesh icons show the
missing-texture placeholder, not a legacy gun or attachment texture; non-mesh items
retain their existing icons.

Icons share the selected body materials and texture cache. There is no separate
forced-low-resolution icon tier: opening many distinct Ultra-quality guns
can still reach the global texture budget. Native atlas use avoids repeated
mesh drawing for stable cached items; it does not remove asset preparation or
all material residency costs.

## Authored Levels

[`MSFT_lod`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Vendor/MSFT_lod/README.md)
is a completed Microsoft vendor extension, not glTF core. Its node and material
`ids` list lower-quality alternatives in order. NeoTaCZ selects the requested
level, clamped to the available chain, and prioritizes authored geometry over
automatic simplification. Node IDs remain stable for animation, hand/rig
bindings and joint lookup. Unselected branches are not drawn; selected material
references determine the resident texture set.

If the highest node's bind transform cannot be safely inverted, its original
branch is retained rather than producing an invalid lower transform. Private
node mappings must still affect the selected geometry or its weighted joints;
ineffective mappings are rejected, never repaired by moving shared skin joints.

This implementation chooses a fixed level per quality generation, not a
distance-driven progressive loader. `MSFT_screencoverage` is an optional hint,
not a required switching algorithm. Unknown unsupported extensions still fail
closed instead of being silently ignored. Malformed LOD references are rejected.

## Automatic Geometry

The loader uses [meshoptimizer 1.0](https://meshoptimizer.org/v1), through the
LWJGL 3.4.1 binding, for rigid primitives without authored LOD. Its method builds
on [quadric error metrics](https://www.cs.cmu.edu/~garland/quadrics/) and supports
attribute-aware error. Normal, UV and color attributes participate in the error
metric; primitive boundaries are locked. The normalized combined geometry and
attribute error limit is `0.001` over the primitive's referenced vertex subset,
not a pixel-error, maximum surface-distance or perceptual guarantee.

Only indices change; source vertex attributes, node hierarchy, mechanical part
partitions and material boundaries remain. Surface contact points are not locked;
the error metric does not constitute a calibrated contact constraint. The
requested ratio is best effort: boundaries and error limits may retain more
triangles. No permissive seam collapse, small
component pruning, vertex relocation or cross-part merging is enabled. Skin and
morph primitives retain their original indices because a rest-pose error metric
does not bound animated deformation. They still benefit from texture scaling.
Simplification happens on load, never inside the per-frame draw loop.

## Input And Selected Draw Budgets

The converter accepts at most 1,000,000 source vertices and 3,000,000 source
indices across the asset, including unselected scenes/LODs and geometry before
simplification. Independent limits still apply: 16,000,000 accessor components,
64 MiB per buffer, 128 MiB total buffer bytes, plus morph, image and resource
budgets. Compressed byte size alone does not establish that an input fits.

After scene/authored LOD selection and eligible simplification, a separate
selected-draw check permits at most 300,000 emitted vertices / 100,000 triangles.
It counts expanded indices for every selected node instance, not just unique
meshes, and runs before image derivation or GPU publication. The larger input
capacity does not promise that an Ultra/original or protected skin/morph model
will fit the draw budget. A requested reduction may retain too much geometry;
such a model still needs an authored LOD or offline adjustment.

## Compressed Inputs

The shared loader normalizes the following supported extensions before ordinary
accessor conversion. It preserves node, rig and material references, and applies
the existing geometry/resource budgets to the decoded result rather than
treating small compressed files as inexpensive runtime models.

- [`KHR_draco_mesh_compression`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_draco_mesh_compression/README.md): indexed TRIANGLES and mapped attribute IDs are decoded by a bundled, short-lived Java worker. At most one worker runs for the client, with a 256 MiB heap cap, 16 MiB direct-memory cap, and a 60-second deadline including queue time. Aggregate decode requests are limited to 250,000 vertices / 300,000 indices, narrower than the ordinary 1M/3M source budget. Input/output bounds, counts and types are checked; cancellation or timeout terminates the process and removes its private temporary directory. This isolates decoder heap allocations from the client JVM, not an OS sandbox or a 256 MiB total-process RSS guarantee.
- [`EXT_meshopt_compression`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Vendor/EXT_meshopt_compression/README.md): bounded native bufferView decoding supports ATTRIBUTES, TRIANGLES and INDICES, plus NONE/OCTAHEDRAL/QUATERNION/EXPONENTIAL filters. Compressed ranges, parent views, strides, decoded lengths and EXT bitstream versions are checked before allocation; temporary native buffers are freed on every path. Compression decoding is separate from the later meshoptimizer quality simplification.
- [`KHR_texture_basisu`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_texture_basisu/README.md): the extension's image source is selected and validated as KTX2, then enters the texture derivation path below. Invalid Basis data is not silently hidden by selecting a PNG fallback.

Only successfully normalized compression declarations are consumed. `MSFT_lod`
and standard `KHR_mesh_quantization` are also supported. Quantization covers
base POSITION/NORMAL/TEXCOORD_0 and morph POSITION/NORMAL, with the declared
component, normalized and alignment rules checked. It preserves authored
numeric ranges and transforms rather than guessing scale from accessor min/max.
The extension must appear in both extensionsUsed and extensionsRequired.
`KHR_meshopt_compression`, `KHR_texture_transform`, extension materials, UV1+,
sparse accessors and explicit base/morph tangents still fail closed. Supporting
quantization or a decoder does not make arbitrary exporter presets compatible.

## Texture And Memory Lifecycle

### Samplers And Uploaded Mips

Magnification supports NEAREST and LINEAR. Minification supports NEAREST,
LINEAR, NEAREST_MIPMAP_LINEAR (9986) and LINEAR_MIPMAP_LINEAR (9987). S and T
independently support REPEAT or CLAMP_TO_EDGE. The current native sampler API
cannot express nearest-mip selection or mirrored repeat exactly, so
NEAREST_MIPMAP_NEAREST (9984), LINEAR_MIPMAP_NEAREST (9985) and MIRRORED_REPEAT
remain explicit errors, not aliases. Source sampler declarations are not changed.

Each image slot's sampler participates in the immutable material/RenderType key;
it is not mutable state on a shared GPU image. Mip storage identity includes
the filtering version, native-prefix version, level count, material role, alpha
parameters and S/T wrap. Equal non-mip encoded images can still share GPU storage
despite different supported samplers, which remain independently bound.

Mipmapped uploads generate real CPU-filtered RGBA8 levels, submit each level,
then release its pixels. At most the current and next CPU images coexist. Color
is filtered in linear light, ORM stays linear, normal vectors are renormalized,
and transparent/MASK images use alpha weighting and approximate base coverage
preservation. S/T wrap controls filter boundaries. Failures release temporary
pixels and newly owned GPU resources; GPU admission counts the sum of all
actual levels before decoding rather than just base-level bytes.

MC 26.2's upload allocator uses `width >> level` and `height >> level` without
clamping either to one. The uploaded/viewable chain is therefore the legal
nonzero prefix: square textures reach 1x1; 3x5 has 3x5 and 1x2; 1x8 has only its
base level. This is not a promise of the full mathematical tail for rectangular
or non-power-of-two textures. The persistent quality cache still stores the
resized base PNG; GPU mip generation does not retain another CPU pyramid or
stream the original KTX2 mip chain.

The Service Pistol's original 9987 sampler is retained. Its raw/pack CPU rerun
and a latest-source 2K Minecraft first-person, refit and hotbar check have passed
within the recorded scene below. That does not validate every sampler, asset,
view, lighting condition or concurrent texture set.

Selected textures are resized before GPU upload. Filtering respects glTF color
roles: base color/emissive RGB in linear light with sRGB storage, linear ORM
channels, and renormalized normal vectors. Transparent base color uses alpha
weighting; MASK coverage is approximated for the material cutoff/factor, subject
to the discrete output pixels and varying vertex alpha.

Derived PNGs use a content-addressed cache in
`cache/neotacz/gltf-textures` inside the game directory. Keys include the source
content, role, dimensions, sampler, alpha parameters and filter version. The
key also includes the KTX decoder version. The cache is bounded to 1 GiB and
uses atomic writes and corruption validation. A derived entry must be a valid
PNG within the upload path's 64 MiB encoded limit; invalid entries are rebuilt.
Source gunpack files are never rewritten or deleted. Returning to Ultra
reads the source at its original dimensions. An unchanged-size KTX2 remains
compressed in the selected asset and is decoded only on a GPU cache miss; it
does not need a same-size PNG and is not uploaded as a compressed GPU format.

### KTX2/Basis Path

The current path accepts ETC1S with BasisLZ, and UASTC with no supercompression
or with Zstd. Images must be non-array, non-cubemap 2D textures with source edges
between 4 and 8192, divisible by four. Legal partial mip pyramids are accepted.
DFD color/channel/alpha metadata must match the material role: sRGB color,
linear non-color data, RGB/RGBA for color/normal/metallic-roughness, and red or
red-green also allowed for standalone occlusion. Premultiplied alpha,
non-default orientation/swizzle and animated KTX2 images are rejected.

Before native allocation, the reader checks level/section ranges, DFD and KVD
metadata, ETC1S global data and individual RGB/alpha slices. Every UASTC inflated
level size must equal its 4x4 block count times 16; an arbitrary Zstd expansion
claim is rejected. Encoded input is limited to 64 MiB, DFD plus KVD to 1 MiB
and 1024 KVD entries, and the total RGBA mip output to 384 MiB. These are
separate input/metadata/output bounds, not a whole-process memory ceiling.

When downsizing is required, LWJGL KTX/libktx transcodes all supplied mips to
RGBA32 and filters the base level; it does not directly select an existing 2K
mip. One cold image's libktx ownership is active at a time. The source native
texture is destroyed before derived PNG encoding, and that smaller PNG uses
the content-addressed disk cache.

An unchanged-size KTX2 instead retains its selected compressed source without
transcoding or writing a PNG during quality preparation. On a GPU cache miss,
encoded/header/role and complete uploaded-level budgets are checked before the
same libktx decoder runs. Base pixels are copied into an independently owned
NativeImage, then the libktx owner is immediately destroyed before RGBA8 upload
and mip generation. NativeImage cannot borrow libktx memory because the owners
use different freeing operations. Both buffers briefly coexist during the copy;
upload completion or failure releases the owned pixels.

An 8192-square full RGBA mip pyramid is about 341.3 MiB of output alone. Its
UASTC block pyramid can add about 85.3 MiB. The unchanged-size path also briefly
holds a separate 256 MiB 8K base copy; encoded/Java data and decoder workspace
have additional costs. The downsample path instead adds filtering and PNG
encoding work. Neither
341 MiB nor the 384 MiB output budget is a claim about total cold peak memory.
The 64 MiB encoded PNG check bounds the published payload, not all temporary
native encoder allocations. Real downsamples still have to fit that output
limit, but an admitted same-size KTX2 is no longer rejected solely because a
lossless PNG representation would be too large. No input, decoded-output or
GPU budget was raised for this change.

Warm downsample disk-cache hits skip Basis transcoding, but parsing can still
read the original encoded image. Same-size GPU-cache hits also skip transcoding;
after reload retires that GPU owner, a same-size cold upload transcodes again,
with no cross-reload PNG cache to avoid it. Downsampled images no longer retain
their original large image/data URI; unchanged-size images intentionally retain
their compressed source. There is no BC/ASTC GPU-compressed upload or mip
streaming here; GPU memory savings come from smaller actual RGBA8 images.

Quality changes publish a new immutable quality snapshot, invalidate old model
generations and background gun/mesh-attachment work, release owned GPU textures,
and replace their client metadata together. This may briefly redraw the held gun
and installed mesh attachments; it is not a seamless
dual-resident transition. Unused original encoded images and decoded pixels are
not retained by the completed low-resolution asset. GPU uploads use the actual
lower dimensions, not just a texture sampling bias.

With lazy loading enabled, render/input getters never wait for background asset
work. They expose only completed model/animation state; pending first-person
models may briefly be absent. Declared mesh world bodies also remain absent
while pending; only non-mesh items retain the existing icon fallback. Animation
initialization is retried by the normal frame path once
ready. Explicitly disabling lazy loading keeps ordinary gun loads synchronous;
mesh attachment getters and GUI extraction still only schedule background work,
and a quality change through the loading overlay remains asynchronous.
Retired work checks the existing display invalidation and resource generation
between primitives, textures and decode/resize/encode stages. A single native
operation or cache write cannot be preempted; cancellation does not mean an
instantaneous zero-memory transition.

Cold loading still reads encoded glTF/GLB references and temporarily decodes an
original image. A warm downsample disk-cache hit skips original image decoding, but the
current glTF parser may still read original encoded references. This is not a
streaming GLB implementation and does not eliminate all loading-time peaks.
The resource manager's lazy index owns no all-pack image byte snapshot; bounded
per-model reads and hard resource budgets remain independently enforced.

Six uncompressed RGBA8 base textures require 1536 MiB at 8K, 96 MiB at 2K, or
24 MiB at 1K. These are logical texture payloads, not whole-process RAM figures
or driver residency measurements. Fifteen repeated instances can share textures;
fifteen different texture sets cannot. Mips, additional materials, framebuffers
and game memory are separate costs.

## Why Not A Renderer Rewrite

meshoptimizer's stable attribute-aware simplifier is a smaller, testable fit for
the current MC renderer than adopting an entire meshlet/virtual-geometry engine.
Recent cluster LOD work also requires a compatible streaming and rasterization
backend. KTX2 is useful for mip storage and access, but the container alone does
not provide GPU compression or prove BC/ASTC support in this backend; the current
Basis implementation intentionally reuses the verified RGBA8 lifecycle. No claim
is made that this implementation is universally fastest.

Native resources are bundled for macOS, Windows and Linux, x64 and ARM64, without
duplicating Minecraft's LWJGL core or native-module manifests. Runtime acceptance
must distinguish actual local platform execution from cross-platform packaging.

## Current Native Evidence

Native acceptance used macOS ARM64, Minecraft 26.2 and NeoForge 26.2. The
[acceptance report](public-mesh-acceptance.md) records the tested assets,
measurements and limits. Raw logs, screenshots, frame data and historical
reports remain local and are not included in the source repository.

The public showcase checks cover three guns, two mesh attachments, Z refit
installation/removal, first/third person, player preview, inventory icons,
ground and fixed displays. The Pistol retained its original minFilter 9987;
its three 2K RGBA8 textures had 12 mip levels. Queried upload samplers are not
proof of every per-draw binding. Existing Bedrock 8x scopes were also checked
on an AK47 and mesh Bolt, not a new mesh transmission/glass scope.

The 2K -> 512 -> 2K transactions removed the old-size texture descriptors.
Logical GPU texture accounting changed from 184,549,536 to 11,534,496 bytes
and back. A 60-second scene with five fixed and five ground items plus a held
gun averaged **33.9 frames/s**, with p95 frame interval **32.478 ms**.
Those ten items reuse three guns and two attachments; this is not proof that
a dozen distinct high-resolution guns run smoothly. An earlier 512 capture
had a 117-frame suppressor submission gap; a later 2,003-frame retest did not
reproduce it. The cause of that earlier gap remains unknown.

The unchanged-size KTX2 fix passed an additional original-8K/reload/final-2K
regression. Its 60-second 8K and final-2K windows recorded 8,684 and 9,909
successful body submissions, respectively, once per frame and no false returns.
All 61 stable 8K snapshots kept the transcode counter at six; only the next
8K reload increased it to 12. The final 2K window had no 8K descriptor left.
The repeat-8K loading overlay recorded actual progress 0 to 1, completed and
returned to Video Settings; both 2K return transactions also succeeded.

Stable live libktx owners and owned NativeImages were zero, not cold-load
allocation peaks. The measured whole-client RSS includes a fixed 8 GiB JVM
heap and other Minecraft resources; the 8K-ending sample is neither final-2K
memory nor VRAM. Cold upload still briefly overlaps libktx and copied pixels.
Unit tests, package checks and these bounded native observations do not
establish all samplers, materials, platforms or multi-gun memory capacity.
