# Public Mesh Showcase Builder

This bounded example builder uses the existing Gson/JSONC and Java ZIP helpers.
It does not modify the default pack, the Bolt pack, source models, or an installed
runtime pack. It is not a general model converter or a new runtime loader.

Prerequisites: the project's Java 25 JDK and Node.js. From the repository root,
prepare the project/tool classes and a machine-local runtime classpath:

```sh
./gradlew -I tools/gunpack/gunpack.init.gradle preparePublicMeshPack
```

This writes `build/gunpack-tool/runtime-classpath.txt`. Regenerate it after
moving the checkout or changing dependencies. `PUBLIC_MESH_CLASSPATH` may point
to an equivalent runtime classpath file. The Node helper never launches Gradle
or downloads assets implicitly.

The showcase tests and builder also need separately downloaded source assets
under `build/public-mesh-assets`; these are not required by the mod's normal
`test` or `build` tasks and are not included in the repository:

- Download the four GLBs with IDs `32989`, `32981`, `33034`, and `33029` from
  the [Mega Weapon Pack](https://3dassets.dev/packs/mega-weapon-pack), using
  `https://cdn.3dassets.dev/assets/<id>/v1/model.glb`, and name them `<id>.glb`.
  Retain the source catalog JSON response as `mega-weapon-pack.json`.
- Download the [Service Pistol](https://polyhaven.com/a/service_pistol) 4K
  glTF, BIN and three JPEG dependencies into `service_pistol/`, preserving
  its `textures/` paths. Retain the Poly Haven file-list response from
  `https://api.polyhaven.com/files/service_pistol` as `service_pistol.files.json`.
- Match the exact file names and SHA-256 values in
  [pack.json](../../../gunpacks/public_mesh_showcase/pack.json). Upstream
  catalog snapshots can change; a different hash is rejected, not silently
  accepted. This historical showcase requires the pinned snapshot.

With those local inputs present, run:

```sh
JAVA_HOME=/path/to/project-jdk-25 node tools/gunpack/public_mesh_pack/build.mjs test
JAVA_HOME=/path/to/project-jdk-25 node tools/gunpack/public_mesh_pack/build.mjs build
JAVA_HOME=/path/to/project-jdk-25 node tools/gunpack/public_mesh_pack/build.mjs validate-runtime
```

Output: `build/public-mesh-pack/public_mesh_showcase-1.0.0-local.zip`.
The three gun IDs are `public_mesh_showcase:chassis_sniper`,
`public_mesh_showcase:marksman_rifle`, and `public_mesh_showcase:service_pistol`.
The separate attachment IDs are `public_mesh_showcase:vertical_grip` and
`public_mesh_showcase:suppressor`. Only the two rifles accept these attachments.

`validate` regenerates the expected package from the trusted template and pinned
sources, checks every ZIP entry/hash/CRC and resource reference, and rejects
foreign namespace files or altered tags. `validate-runtime` additionally opens
each actual packaged model through the project's standard JgltfModelLoader and
checks its complete dependency closure. It requires a compiled runtime with
KHR_mesh_quantization support; it does not silently dequantize the source first.
Neither check proves native rendering, geometric attachment fit, or hand motion.

Source rights, declared connector provenance, non-publishing limits, and the
offline/runtime coordinate equation are in `gunpacks/public_mesh_showcase/PROVENANCE.md`
and the generated `public-mesh-manifest.json`. Installing and runtime acceptance
are deliberately separate operations owned by the integration task.
