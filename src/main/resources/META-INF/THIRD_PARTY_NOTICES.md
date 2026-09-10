# Third-Party Notices

This runtime artifact redistributes the following libraries.

## LWJGL Meshoptimizer 3.4.1 / meshoptimizer 1.0

- Components: Java binding and platform-scoped meshoptimizer native libraries
- LWJGL copyright: 2012-present Lightweight Java Game Library; BSD 3-Clause
- meshoptimizer copyright: 2016-2025 Arseny Kapoulkine; MIT
- Projects: <https://github.com/LWJGL/lwjgl3>, <https://github.com/zeux/meshoptimizer>
- Bundled licenses: `META-INF/licenses/LWJGL-BSD.txt`, `META-INF/licenses/meshoptimizer-MIT.txt`
- Minecraft supplies LWJGL core; this feature does not embed another copy.

## LWJGL KTX 3.4.1 / KTX-Software 4.4.2

- Components: the LWJGL KTX Java binding and libktx native libraries for
  Linux, macOS, and Windows, on x86-64 and ARM64.
- The Java binding uses the existing `META-INF/licenses/LWJGL-BSD.txt`.
- KTX-Software credits Mark Callow, The Khronos Group Inc., and other
  contributors. Its main license is Apache-2.0, with component-specific
  exceptions; it is not uniformly Apache-licensed or entirely open source.
- Unmodified upstream license and notice texts are under
  `META-INF/licenses/ktx-4.4.2/`. Upstream `NOTICE.md` points to
  `LICENSE.md`; both files are retained together.
- Project: <https://github.com/KhronosGroup/KTX-Software/tree/v4.4.2>

The bundled libktx dependency sources include these notices:

| Component | Copyright / license | Bundled text under `META-INF/licenses/ktx-4.4.2/` |
| --- | --- | --- |
| KTX-Software and dfdutils | Mark Callow, The Khronos Group Inc., contributors; Apache-2.0 | `LICENSE.md`, `LICENSES/Apache-2.0.txt`, `external/dfdutils/LICENSE.adoc` |
| Basis Universal, including its miniz fork | Copyright (C) 2019-2021 Binomial LLC. All Rights Reserved.; Apache-2.0 | `external/basisu/LICENSE` |
| Basis Universal cppspmd SIMD code | Copyright 2020-2022 Binomial LLC; Apache-2.0 | `LICENSES/Apache-2.0.txt` |
| Arm ASTC encoder | Copyright 2020-2023 Arm Limited; Apache-2.0 | `external/astc-encoder/LICENSE.txt` |
| Zstandard bundled with Basis Universal | Copyright (c) 2016-present, Facebook, Inc. All rights reserved.; BSD-3-Clause | `external/basisu/zstd/LICENSE` |
| uthash | Copyright (c) 2003-2010, Troy D. Hanson. All rights reserved.; BSD-1-Clause | `LICENSES/BSD-1-Clause.txt` |
| Khronos OpenGL/EGL headers | Copyright 2007-2020 The Khronos Group Inc.; MIT | `LICENSES/MIT.txt` |
| Ericsson ETC decoder | (C) Ericsson AB 2013. All Rights Reserved.; custom Ericsson Software License Agreement | `LICENSES/LicenseRef-ETCSLA.txt` |

The Ericsson agreement contains purpose and use restrictions and is retained
without reclassifying it as an open-source license. These notices do not
replace the component license terms or grant additional rights.

## Openize Drako 26.3.0

- Component: `com.openize:drako:26.3.0`, embedded in the isolated
  `META-INF/neotacz/draco-worker.jar`.
- Copyright (c) 2024 Openize Pty Ltd; MIT License.
- Bundled license: `META-INF/licenses/Openize-Drako-26.3.0-MIT.txt`.
  This outer-artifact copy is intentional: the worker repackaging excludes
  dependency `META-INF` entries.
- Project: <https://github.com/openize-drako/Openize.Drako-for-Java>
- License source pinned to the upstream 26.3.0 POM commit:
  <https://github.com/openize-drako/Openize.Drako-for-Java/tree/a1fb935d5f3e54acc2d1763ef8caca3f5c066f6f>

Fixed-version source URLs and SHA-256 checksums for these additional license
texts are recorded in `META-INF/licenses/DECODER-LICENSE-SOURCES.md`.

## JglTF 3.0.1

- Components: `jgltf-model`, `jgltf-impl-v1`, `jgltf-impl-v2`
- Copyright: Copyright (c) 2016 Marco Hutter
- License: MIT License
- Project: <https://github.com/javagl/JglTF>
- Bundled license: `META-INF/licenses/JglTF-MIT.txt`

## Jackson 2.22.x

- Components: `jackson-databind` 2.22.1, `jackson-core` 2.22.1,
  `jackson-annotations` 2.22
- Copyright: FasterXML, LLC and Jackson contributors
- License: Apache License 2.0
- Project: <https://github.com/FasterXML/jackson>
- License: <https://www.apache.org/licenses/LICENSE-2.0>

The redistributed Jackson component JARs retain their upstream
`META-INF/LICENSE` and `META-INF/NOTICE` files.
