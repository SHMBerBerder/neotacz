# Meshopt Codec Fixture

`Box.glb` is the unmodified Khronos glTF Sample Assets Box model.

- Source: https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Box
- Download: https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Box/glTF-Binary/Box.glb
- Copyright: 2017 Cesium.
- License: Creative Commons Attribution 4.0 International, https://creativecommons.org/licenses/by/4.0/legalcode
- Bytes: 1664; SHA-256: `ed52f7192b8311d700ac0ce80644e3852cd01537e4d62241b9acba023da3d54e`.

`GltfMeshoptDecoderTest` compresses its real buffer views with the pinned LWJGL
meshoptimizer codec during the test, without quantization or geometry changes.
The compressed result is then loaded and converted through the production path.
Small codec/filter fixtures in that test likewise use the real native encoders,
not synthetic byte streams that merely resemble compression headers.
