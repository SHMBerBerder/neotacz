# Decoder License Provenance

Checked on 2026-09-08. These records identify the license texts and binary
artifacts inspected; they are not a legal opinion or a reproducible-build
attestation. The existing JglTF, Jackson, LWJGL, and meshoptimizer notices
remain in `META-INF/THIRD_PARTY_NOTICES.md`.

## Upstream Sources

- LWJGL 3.4.1: <https://github.com/LWJGL/lwjgl3/blob/3.4.1/LICENSE.md>.
  The existing `LWJGL-BSD.txt` preserves this text with a final newline.
- KTX-Software v4.4.2 resolves to commit
  `4d6fc70eaf62ad0558e63e8d97eb9766118327a6`.
  Each local `ktx-4.4.2/<path>` file below is an unmodified copy of
  `https://raw.githubusercontent.com/KhronosGroup/KTX-Software/v4.4.2/<path>`.
  The same paths are pinned at
  <https://github.com/KhronosGroup/KTX-Software/tree/4d6fc70eaf62ad0558e63e8d97eb9766118327a6>.
  `NOTICE.md` is the upstream pointer to the adjacent `LICENSE.md`.
- KTX component attribution evidence: upstream
  [CMake source lists](https://github.com/KhronosGroup/KTX-Software/blob/v4.4.2/CMakeLists.txt),
  [REUSE annotations](https://github.com/KhronosGroup/KTX-Software/blob/v4.4.2/REUSE.toml),
  [Basis Universal attribution](https://github.com/KhronosGroup/KTX-Software/blob/v4.4.2/external/basisu/.reuse/dep5),
  [miniz fork header](https://github.com/KhronosGroup/KTX-Software/blob/v4.4.2/external/basisu/encoder/basisu_miniz.h),
  [cppspmd header](https://github.com/KhronosGroup/KTX-Software/blob/v4.4.2/external/basisu/encoder/cppspmd_sse.h),
  and [uthash header](https://github.com/KhronosGroup/KTX-Software/blob/v4.4.2/lib/uthash.h).
  Basis Universal, Zstandard, ASTC, and dfdutils are identified by the
  snapshots vendored in this fixed KTX release, not by inferred independent
  release numbers. The original license texts are retained separately even
  when multiple components use Apache-2.0.
- Openize Drako: `com.openize:drako:26.3.0` declares MIT in its Maven POM.
  That POM has no SCM tag. The upstream
  [26.3.0 POM commit](https://github.com/openize-drako/Openize.Drako-for-Java/blob/a1fb935d5f3e54acc2d1763ef8caca3f5c066f6f/pom.xml)
  identifies the fixed source snapshot used for the unmodified
  [MIT text](https://raw.githubusercontent.com/openize-drako/Openize.Drako-for-Java/a1fb935d5f3e54acc2d1763ef8caca3f5c066f6f/LICENSE).
  The inspected Maven binary has no LICENSE/NOTICE entry. The enclosing
  NeoTaCZ artifact therefore supplies the license for the embedded worker.

## License Text SHA-256

Paths are relative to `META-INF/licenses/`.

| File | SHA-256 |
| --- | --- |
| `LWJGL-BSD.txt` | `8130c6f15e9f961e74c2b197551e60ef9d00521addc5908255be1cdd43a6c90b` |
| `ktx-4.4.2/LICENSE.md` | `0f92b4ce771585dfebc963ede074a30b4431b9e327911db44097d83ccf372128` |
| `ktx-4.4.2/NOTICE.md` | `4673a3aba01813b595de187a7a6e9e63a3491d55821606fecd9f13a10c188a1d` |
| `ktx-4.4.2/LICENSES/Apache-2.0.txt` | `44b0a56e80b41a1b0a6bd1292515e806539fd97b30be54c2719e6c763190ab57` |
| `ktx-4.4.2/LICENSES/BSD-1-Clause.txt` | `9f213b0e6f1b624e6b16c379486a77368a911f8cec17d9ebe4b859487adc79a4` |
| `ktx-4.4.2/LICENSES/MIT.txt` | `8f25018489d6fe0dec34a352314c38dc146247b7de65735790f4398a92afa84b` |
| `ktx-4.4.2/LICENSES/LicenseRef-ETCSLA.txt` | `8981db93abcb592e59b519efca75a3573af98a6390e93aa45cc0c4c8edb1c819` |
| `ktx-4.4.2/external/basisu/LICENSE` | `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |
| `ktx-4.4.2/external/basisu/zstd/LICENSE` | `2c1a7fa704df8f3a606f6fc010b8b5aaebf403f3aeec339a12048f1ba7331a0b` |
| `ktx-4.4.2/external/astc-encoder/LICENSE.txt` | `494accc32e50eb523a0e384d0ae6d4b702db867a89d6971216760e92b240ee12` |
| `ktx-4.4.2/external/dfdutils/LICENSE.adoc` | `8ee9fdaf365c301bb413656765fc85c5424a807c50e35a8ae622b99f4ea22244` |
| `Openize-Drako-26.3.0-MIT.txt` | `9235ad438b3635548bc5584289ad6cef5b93762771e934ab90fe8990bb1d0857` |

## Inspected Binary Artifacts

The six LWJGL native libraries all contain the version string `v4.4.2`.
The macOS ARM64 library also contains `libktx.4.4.2.dylib` and ETC decoder
symbols, including `decompressBlockETC2` and `decompressBlockAlpha16bit`.
Accordingly the Ericsson ETCSLA is retained, rather than assuming ETC
unpacking was disabled. This inspection does not reconstruct each platform's
compiler flags or a complete binary-level bill of materials.

LWJGL artifacts are from
`https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-ktx/3.4.1/`.
The Drako artifact is from
`https://repo.maven.apache.org/maven2/com/openize/drako/26.3.0/`.

| Artifact | SHA-256 |
| --- | --- |
| `lwjgl-ktx-3.4.1.jar` | `97d38d87352798105ce292e55fb91330477fa58eb2d704c851784d13a040492d` |
| `lwjgl-ktx-3.4.1-natives-linux.jar` | `8dac2d1524a1d4e32169cb1c5363fcccf6613b87db9c37b4a2bbd0da34db14df` |
| `lwjgl-ktx-3.4.1-natives-linux-arm64.jar` | `c45341801d087151f71c26dfab864bcd0f000cbed56720a7f3fe8ab5a4e1ba2d` |
| `lwjgl-ktx-3.4.1-natives-macos.jar` | `c2b33c5327489a30ede8d52f9dcadc48f820c59aefcadbdee3127bfd19594ee4` |
| `lwjgl-ktx-3.4.1-natives-macos-arm64.jar` | `69864e74f2b6492e26129627f67cdb6d94d6864ff12ade54dd86f1eb9aef9445` |
| `lwjgl-ktx-3.4.1-natives-windows.jar` | `ee365564c674454124dbb37e4a8e5e0400f05629d8bf4d58ad355a4d98d595b6` |
| `lwjgl-ktx-3.4.1-natives-windows-arm64.jar` | `b696f6d7dd419247860bf94de2c4d8f0757897d9c879a663e22588c7051e43ce` |
| `drako-26.3.0.jar` | `555bbd28d2168b30e7da7388dbb98428422b92008ab2613c8cd8dd9c5c2e31e2` |
