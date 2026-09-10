# KTX2 Test Fixtures

These binary fixtures are copied without modification from KhronosGroup/KTX-Software,
tag `v4.4.2`, directory `tests/testimages`:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| cyan_rgb_reference_basis.ktx2 | 418 | 2b2ceb5627e3f70969c7d99d5c75d462d156c0df0a431c747e43c9307d6cb131 |
| cyan_rgb_reference_uastc.ktx2 | 4368 | fdca34518a64017fc7c8e266c1fcb92f85cc5f2fb13605ea6c816455c4921c94 |
| alpha_simple_basis.ktx2 | 431 | fc21f2c39480383c7943733a18c5a543e89ecb7f035e9f97d501982a4948c8dd |
| rgba-mipmap-reference-basis.ktx2 | 719 | 267b18c4badb42fb9739ef42b5e5cd9c2d95872e55ae298a82000fb402c33958 |
| color_grid_uastc_zstd.ktx2 | 170512 | 84f2482d6ceda0c71259c62271cced8c3a308f4eb49c5ad55fc9797df5988c26 |

Source: https://github.com/KhronosGroup/KTX-Software/tree/v4.4.2/tests/testimages

The unmodified source reference `alpha_simple.png` is from the same tag's
`tests/srcimages` directory (1315 bytes, SHA-256
`83fb829d507a8711872725909bf85d7e497b831506c1b8b9cceabca0b995883b`).
The upstream `tests/testimages/genktx2` uses it to generate `alpha_simple_basis.ktx2`.
All 64 source pixels have alpha 128; the tests compare the native and derived PNG
alpha against this reference rather than assuming that the image is a gradient.

Copyright 2015-2022 The Khronos Group Inc.
SPDX-License-Identifier: Apache-2.0

The upstream `REUSE.toml` assigns this copyright and license to these image files.
The full license is included in `LICENSE-Apache-2.0.txt`. Fixtures are test resources,
not runtime gunpack assets, and must not be included in the production mod JAR.
