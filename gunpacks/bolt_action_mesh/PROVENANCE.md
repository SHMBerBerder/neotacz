# Bolt Action Mesh: Local Test Gunpack

This independent `bolt_action_mesh` pack is for local private testing. It does
not replace any `tacz` resource. Do not publish this mixed-license derivative
as CC0 or assume redistribution permission from the mesh license.

## Mesh And Images

Bolt Action Rifle 7.62, Mateusz Sadek, Poly Haven:
https://polyhaven.com/a/bolt_action_rifle_7_62
License: CC0, https://polyhaven.com/license

The default original mode copies the adapted glTF/BIN and all six original
8192 x 8192 JPEG files byte-for-byte. Optional derived mode packages an already
encoded glTF/GLB and only its local dependency files, without also bundling the
original image set. The build checks original input SHA-256 values against
`pack.json` in both modes, and records every packaged file's actual size,
SHA-256 and CRC32. Derived manifests additionally record `asset_files` hashes
and the operator's path-free encoder/version/options/source-hash receipt.
Receipts are provenance declarations, not signed proof of visual equivalence.
No compression, mipmap, GPU-format or runtime-quality claim follows from ZIP
validation alone; real encoded payloads must pass decoder and rendering gates.

## Rig, Animation And Game Template

TACZ Dev Team's default gunpack declares CC BY-NC-ND 4.0:
https://creativecommons.org/licenses/by-nc-nd/4.0/
The calibrated Bedrock rig/animation and M95-derived display/game data retain
that provenance. The gun retains M95 game statistics and `tacz:50bmg` ammo as
an existing game template, not as real-world Bolt Action Rifle 7.62 parameters.
Names, index and paths are independent. The four mechanical node mappings,
render scale and calibrated hand/visibility keys are retained. M95 gun LOD,
slot and HUD visuals are omitted; the mesh provides the body and automatic
atlas icon. The retained Bedrock texture is a compatibility-rig resource.

One broken inherited sound reference is corrected only in the packaged
animation: `tacz:m95/mag_drup_large_drum_dirt` becomes
`tacz:mag_drop_sound/mag_drup_large_drum_dirt`, the existing default-pack file.
The input bake is unchanged; the manifest records this remap. All other
animation values remain unchanged.

## Required Runtime Resources

This is an independent add-on pack, not a self-contained replacement for the
default gunpack. NeoTaCZ's default `tacz` pack must be installed for the shared
state machine, sound effects, ammo/shell models and ammo icons, the
compatibility rig's UV texture and muzzle flash. The NeoTaCZ mod supplies the default rifle
animations. `mesh-pack-manifest.json` lists every checked shared file and its
provider (`default_pack` or `mod`). `gunpack.meta.json.dependencies` checks
mod versions only; it does not install or validate a required gunpack.

Calibration provenance and coordinate conventions:
`tools/blender/neotacz_bolt_action_8k/CALIBRATION.md` in the source checkout.
Calibration report and the input hashes are also included in the pack manifest.
