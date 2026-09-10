# Draco Decoder Fixtures

Unmodified Khronos glTF Sample Assets, pinned to commit
`9429648735279342b4c32b8745f7904196607379`:

- Box: https://github.com/KhronosGroup/glTF-Sample-Assets/tree/9429648735279342b4c32b8745f7904196607379/Models/Box/glTF-Draco
- RiggedSimple: https://github.com/KhronosGroup/glTF-Sample-Assets/tree/9429648735279342b4c32b8745f7904196607379/Models/RiggedSimple/glTF-Draco

Each directory retains the asset's original LICENSE.md and author metadata.
The Box test decodes
buffer view 0 (118 bytes); the skin test decodes RiggedSimple buffer view 4
(offset 2328, length 2191). Accessor counts/types and unique attribute IDs in
the tests come from the accompanying original glTF files, not from a locally
written encoder. The skin fixture guards against all-zero unsigned joint data.

The decoder dependency is fixed to `com.openize:drako:26.3.0`. Tests exercise
the embedded isolated worker JAR, not an in-process alternate decode path.

SHA-256:

| File | SHA-256 |
| --- | --- |
| box/Box.gltf | 3c46acecdfa90b012ec9052d8a1dfa61358e6d56a9e333504189cc78a2de4d1b |
| box/Box.bin | 610dc6e08aba7c2720c8e4ec0578efd91cf2d88a5e638dab7811a22f0235bf2e |
| rigged-simple/RiggedSimple.gltf | ef345d43419377a4a1c255b1a779723e3d2d3b4321ee33b1f9833840306d5449 |
| rigged-simple/RiggedSimple0.bin | c0196c3e1d642ea4d78898c0b4e9b1644828a2083145264fb5f9b18b0fb29229 |
