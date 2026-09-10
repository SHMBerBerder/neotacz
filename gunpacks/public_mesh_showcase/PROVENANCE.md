# Public Mesh Showcase: Local Validation Only

This is a separate `public_mesh_showcase` gunpack. It does not replace the
default pack or the existing Bolt Action Mesh pack.

## Visual Sources

- 3D Assets, Mega Weapon Pack: Chassis Bolt Sniper (32989), Marksman Rifle
  (32981), Vertical Fore Grip (33034), Rifle Suppressor (33029).
  https://3dassets.dev/packs/mega-weapon-pack
  The author declares CC0 1.0, invented designs, AI-generated assets, and
  metre units with +Y up / +Z forward. Geometry has real separate mesh nodes
  and authored rigid clips. Its materials use PBR factors, not image maps.
- Mateusz Sadek / Poly Haven, Service Pistol: CC0 1.0.
  https://polyhaven.com/a/service_pistol
  https://polyhaven.com/license
  The three original 4K JPEGs and BIN are copied without re-encoding. Only
  variant A plus the loaded magazine is selected. The magazine's -0.1 m X
  display-layout offset is removed. Unreferenced original BIN ranges are
  retained; no second pistol variant remains in the scene or mesh table.

`pack.json` pins every source SHA-256. The generated manifest also records
source URLs, author declarations, MD5 verification for the Poly Haven file
list, binary payload hashes, node selection, coordinate transforms, rendered
triangle counts, dependency closure, and the complete output hash/CRC table.

## Coordinate Contract

All output models use metres and render scale 1. Source rifle/attachment
+Z forward is changed to the existing gun-rig -Z forward by Ry(180 degrees).
The Service Pistol's +X forward instead uses Ry(90 degrees). This is an
offline root-node transform: quantized accessors, normals, UVs, materials,
indices, and embedded clips are not decoded, merged, or re-baked.

Let C be the offline rotation, d the gun translation, and
B = diag(-1,-1,1) the runtime mesh basis. Gun points are `B * (C*p + d)`
relative to the live Bedrock root. Attachment points are `B*C*(p-mount)`
relative to their installed anchor. The generated Bedrock pivot is
`rootPivot + 16 * (-g.x, g.y, g.z)` for `g=C*socket+d`.
The anchor stays unrotated, with no legacy attachment 24/16 translation.
The output manifest verifies both sides meet at the same numeric origin.

Source mount/socket numbers come from the author's prose metadata, not
glTF nodes/extras or a standardized glTF connector extension. In particular,
the grip description claims an origin at its mounting face but explicitly
lists mount=(0,0.124,0); this package follows the explicit coordinate. The
numeric coincidence test does not prove geometric fit or contact quality.

The standalone attachments remain separate models/items. Only the two new
rifles accept this pack's own grip and muzzle tags. The optional glass scope
is excluded rather than pretending that transmission optics are supported.

## Game Template Rights And Limits

M700, MK14 and Glock 17 game data, display settings, rig and animations, plus
the grip/suppressor game-effect templates, are derived from the NeoTaCZ
default gunpack. These TACZ materials are CC BY-NC-ND 4.0, not CC0. This mixed
derived package is exclusively a local private compatibility test and must
not be published or represented as an all-CC0 distributable pack.

Gameplay values are game templates, not specifications of the source assets
or real firearms. Shared scripts, audio, ammunition visuals and default hand
animations require the NeoTaCZ default pack/mod; the validator checks each
actual resource, not just the mod-version dependency.

Four copied animation clips contain unresolved legacy sound aliases: M700
draw, MK14 tactical/empty reload, and Glock 17 draw. Only those obsolete
`sound_effects` subtrees are removed from the new package. Their display
definitions already provide complete, existing audio tracks. The manifest
records the exact retired events and retained display tracks; all bone
keyframes and clip durations remain unchanged. Default source files are not edited.

The body follows the copied Bedrock animation rig. Original glTF action
clips remain present but are not automatically played or claimed to match
game reloads. This package does not claim new hand-contact calibration,
mechanical-motion calibration, or physical assembly accuracy. Runtime visual,
installed-attachment, and first/third-person acceptance is a separate gate.
