# glTF 渲染研究与 NeoForge 26.2 适配边界

本文档记录 neotacz 当前 glTF 枪身与附件渲染 V1 的真实能力、限制和后续验证口径。结论按证据分级：

- **已在本项目实现**：来自当前仓库源码、单测覆盖或最近构建证据。
- **计划验证**：代码路径存在或工程方向成立，但还需要 Minecraft 客户端 runtime、截图或 profile 证明。
- **研究但不纳入 V1**：来自一手资料和大型引擎实践，作为后续路线参考，不进入首版运行时承诺。

当前 V1 是 **opt-in 枪身动态 renderer**：display JSON 显式配置 `render_model.type = "gltf"` 后，mesh 是该枪的枪身视觉权威，不再用旧 Bedrock 枪身、距离 LOD 或旧枪图标代替失败或未就绪的 mesh。未声明 mesh 的枪仍走原 Bedrock 路径；mesh 枪的 Bedrock rig 继续负责操作动画、附件定位、功能渲染器、手臂、抛壳、枪口火焰和文本显示。`node_map` 只额外提供少量机械节点的单向 transform bridge；它不是通用静态 `BakedModel` 导入器，也不是 CAD/NURBS、点云、Nanite、meshlet runtime 或全骨架 retarget 系统。

## 当前源码适配状态

结论是已有可用的枪身 mesh 加载链，但不能称为任意 mesh/glTF 已完整适配。

| 范围 | 当前边界 |
| --- | --- |
| 容器与资源 | glTF 2.0 的 `.gltf` / `.glb`、内嵌资源、受路径和预算约束的外部 BIN/PNG/JPEG/KTX2 |
| 分级与画质 | `MSFT_lod` 节点/材质固定档位；无作者分级的刚体网格算法简化；降采样持久缓存与代际失效 |
| 几何与动画 | TRIANGLES、UV0、COLOR0、四权重 JOINTS0/WEIGHTS0、POSITION/NORMAL morph、TRS/weights 三种插值；CPU 动态变形有预算限制 |
| 量化属性 | 标准 `KHR_mesh_quantization` 已实现 POSITION/NORMAL/UV0 与 morph POSITION/NORMAL；不扩展其它语义支持面 |
| 压缩输入 | `KHR_texture_basisu` 的 ETC1S/BasisLZ、UASTC/可选 Zstd；隔离子进程解码 `KHR_draco_mesh_compression`；原生解码 `EXT_meshopt_compression` |
| 材质与拒绝边界 | 核心 metallic-roughness PBR；仍不支持扩展材质、`KHR_texture_transform`、`KHR_meshopt_compression`、UV1+、显式 TANGENT、稀疏 accessor 等路径 |
| 操作与手位 | Bedrock rig 仍是操作动画和 MC 手/附件锚点权威；glTF 窄桥不等同全骨架自动重定向 |
| 第三人称与展示 | 已有第一人称、第三人称/角色预览、地面、FIXED 展示与背枪入口统一使用声明的 mesh；mesh 不加载旧 display-level Bedrock `lod`，质量选择使用自身 `MSFT_lod`/简化结果 |
| GUI 图标 | 普通/创造物品栏、快捷栏及 mesh HUD 使用原生 GUI item atlas；固定默认姿态 mesh 只在缓存需要更新时提交 |
| mesh 附件 | 通用 AttachmentDisplay `render_model.location`/`scale`，独立附件物品、安装提交与原生 atlas；拒绝附件 `node_map`；两枪/两附件有 Z 安装卸载记录，最新源版本另有已安装主体截图 |
| 失败 | 不支持或无效资源明确拒绝；mesh 枪身保持不可用，GUI 显示缺失占位，不画另一把旧枪；画质加载页不会将构造或上传失败报告为成功 |

当前 Bolt Action Rifle 样本是刚体机械节点绑定实例，不能用单个样本证明全部 skin/morph/第三方扩展兼容。选中低 LOD 的实际蒙皮正权重影响已有桥接检查：断开的高模映射会被拒绝，不会为了让映射有效而随意移动共享关节。

此前 Bolt 的 `lod.model` 引用旧 M95，且 `GunLodRenderDistance=0` 强制选中旧 LOD，造成角色预览与第一人称外形不一致。当前修复位于公共 display/枪身提交路径，不是物品栏特判：`usesMeshRenderModel()` 使旧 LOD getter 不再加载模型，并在所有现有枪身分支阻止旧主体兜底。非 mesh 枪的距离 LOD 不变；这不是新增原本不存在的左手独立渲染入口，也不表示所有视角已完成实机视觉验证。

### 普通与创造物品栏图标

当前实现复用 MC 26.2 `GuiItemAtlas` 路线：`TaczCustomItemModel` 提取 GUI snapshot，special model 层将静态 mesh 画进原生带深度图集，普通物品栏、创造物品栏和快捷栏显示缓存的 2D 区域。mesh HUD 也走同一 item 图标路径。没有另建截图纹理缓存，不做每帧 GPU 回读或图标 PNG 编码；带附魔闪光的物品仍遵循 MC animated-item 行为。

图标使用 mesh 默认 bind/default morph 姿态，不采样实时枪机、换弹或嵌入动画首关键帧；排除手臂和功能效果。统一三分之四视角按实际投影包围范围居中缩放，覆盖默认物品变换以避免 atlas 裁切。静态主体复用现有几何，带映射或动画的主体只额外准备一次默认姿态几何。

缓存身份包含资源/画质代际及 pending-to-ready 变化，稳定帧不生成新键；键 token 不反向持有 renderer/CPU 资产。GUI 提取始终只调度异步加载，不因关闭普通 lazy loading 而同步解码。未就绪或失败的 mesh 使用缺失占位而非旧槽位贴图；非 mesh 枪保留原 slot 图标。图标与枪身共享所选画质的材质/纹理，没有单独的强制低清图标档，因此 Original 下创造栏多把不同枪仍可能触及全局纹理预算。

压缩 Bolt 的原生 atlas、角色预览、第三人称、FIXED 路由与画质重载已有限定场景实机验收；后续公开三枪与两附件的结果见 [实测报告](public-mesh-acceptance.md)。这些结果不代表全部 glTF、视角组合、多把独立枪或复杂材质已通过视觉和性能验收。原始运行日志、截图和历史验收记录在本地留存，不随仓库发布。

## 本项目证据

主要源码入口：

- `src/main/java/com/tacz/guns/client/resource/pojo/display/gun/GunRenderModelConfig.java`
- `src/main/java/com/tacz/guns/client/resource/GunDisplayInstance.java`
- `src/main/java/com/tacz/guns/client/resource/pojo/display/attachment/AttachmentDisplay.java`
- `src/main/java/com/tacz/guns/client/resource/index/ClientAttachmentIndex.java`
- `src/main/java/com/tacz/guns/client/resource/GltfRenderModelLoader.java`
- `src/main/java/com/tacz/guns/client/resource/manager/GltfModelManager.java`
- `src/main/java/com/tacz/guns/client/model/bedrock/BedrockPart.java`
- `src/main/java/com/tacz/guns/client/model/bedrock/BedrockModel.java`
- `src/main/java/com/tacz/guns/client/model/gltf/loader/GltfResourcePolicy.java`
- `src/main/java/com/tacz/guns/client/model/gltf/loader/JgltfModelLoader.java`
- `src/main/java/com/tacz/guns/client/model/gltf/loader/GltfCompressionNormalizer.java`
- `src/main/java/com/tacz/guns/client/model/gltf/loader/GltfDracoDecoder.java`
- `src/main/java/com/tacz/guns/client/model/gltf/loader/GltfMeshoptDecoder.java`
- `src/main/java/com/tacz/guns/client/model/gltf/quality/Ktx2ImageHeader.java`
- `src/main/java/com/tacz/guns/client/model/gltf/quality/Ktx2ImageDecoder.java`
- `src/main/java/com/tacz/guns/client/model/gltf/quality/GltfTextureVariants.java`
- `src/main/java/com/tacz/guns/client/model/gltf/convert/GltfAssetLimits.java`
- `src/main/java/com/tacz/guns/client/model/gltf/convert/GltfMeshAttributeRules.java`
- `src/main/java/com/tacz/guns/client/model/gltf/convert/JgltfRuntimeConverter.java`
- `src/main/java/com/tacz/guns/client/model/gltf/runtime/GltfVertexDeformer.java`
- `src/main/java/com/tacz/guns/client/model/gltf/runtime/GltfAnimationSampler.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfNodeMapBridge.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfGunBodyPose.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfGunBodyRenderer.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfGuiIconRenderer.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfPbrSamplers.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfTextureMipmaps.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/UploadOnlyTexture.java`
- `src/main/java/com/tacz/guns/client/renderer/item/TaczCustomItemModel.java`
- `src/main/java/com/tacz/guns/client/renderer/item/AttachmentItemRenderer.java`
- `src/main/java/com/tacz/guns/client/model/gltf/render/GltfPreparedGeometry.java`
- `src/main/resources/assets/tacz/shaders/pbr/gltf_pbr.vsh`
- `src/main/resources/assets/tacz/shaders/pbr/gltf_pbr.fsh`

覆盖性测试入口：

- `src/test/java/com/tacz/guns/client/model/gltf/loader/JgltfModelLoaderTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/convert/GltfAssetLimitsTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/convert/GltfMeshQuantizationTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/convert/JgltfRuntimeConverterTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/runtime/GltfRuntimeMathTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/render/GltfNodeMapBridgeTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/render/GltfGunBodyRendererSupportTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/render/GltfPbrMaterialFactorsTest.java`
- `src/test/java/com/tacz/guns/client/model/gltf/render/GltfTextureMipmapsTest.java`
- `src/test/java/com/tacz/guns/client/resource/manager/GltfModelManagerTest.java`
- `src/test/java/com/tacz/guns/client/resource/pojo/display/gun/GunDisplayRenderModelConfigTest.java`
- `src/test/java/com/tacz/guns/client/resource/ClientAssetsManagerTest.java`
- `src/test/java/com/tacz/guns/client/resource/pojo/display/attachment/AttachmentDisplayRenderModelTest.java`
- `src/test/java/com/tacz/guns/client/resource/AttachmentMeshLifecycleTest.java`
- `src/test/java/com/tacz/guns/client/resource/AttachmentQualityOverlayTest.java`
- `src/test/java/com/tacz/guns/client/renderer/item/AttachmentItemRendererTransformTest.java`

当前验证状态：新增压缩路径有真实 ETC1S、UASTC、Zstd、alpha/mip fixture，以及解码错误、取消、预算、缓存和资源所有权测试；GUI 默认姿态和身份键也有测试。完整 Gradle gate、当前 JAR 与 MC runtime 结果以对应实施报告为准。自动测试执行原生解码不等于 Minecraft 客户端图集、视觉或帧时间已经验收。

## render_model JSON

默认行为：不写 `render_model`，或写空对象导致 `type` 默认 `bedrock`，都会走原 Bedrock 枪械主体路径。

```json
{
  "model_type": "default",
  "model": "tacz:geo_models/gun/rifle.geo.json",
  "texture": "tacz:textures/gun/rifle.png",
  "render_model": {
    "type": "gltf",
    "location": "tacz:models/gltf/rifle/rifle.glb",
    "scale": 0.0625,
    "animation": "idle",
    "loop_animation": true,
    "animation_speed": 1.0,
    "node_map": {
      "bolt": "BoltVisual",
      "magazine": "MagazineVisual"
    }
  }
}
```

字段边界：

- `type` 只接受 `bedrock` 或 `gltf`。
- `location` 仅在 `gltf` 时必填，且必须指向 `models/gltf/**.gltf` 或 `models/gltf/**.glb`。
- `scale` 必须有限，范围 `(0, 1024]`。
- `animation` 可选，但不能是空白字符串；glTF animation 本身不定义自动播放和状态机。
- `loop_animation` 只允许为 `true`；选择动画时若为 `false`，renderer 构造会 fail closed。V1 不把 stop-at-end 或 clamp 写成支持能力。
- `animation_speed` 只影响当前 V1 枪身动画采样。
- `node_map` 可选，仅在 `type:gltf` 时生效；方向是 exact Bedrock source bone name -> exact glTF target node name，大小写敏感，最多 8 项，目标一对一。

### 附件 display

附件复用 `AttachmentDisplay.render_model` 和同一受管 glTF 加载、量化、LOD、简化及纹理派生链，不按附件 ID 或模型名分支。最小声明如下：

```json
{
  "render_model": {
    "type": "gltf",
    "location": "example:models/gltf/attachment/grip.glb",
    "scale": 1.0
  }
}
```

附件非空 `node_map` 明确拒绝。可保留 Bedrock `model`/`texture` 和 scope/sight 等元数据，作为光学与功能 semantic rig；它不是可见附件主体的备用外形。mesh 未就绪或失败时不画旧附件主体，GUI 使用缺失占位。附件图标与枪身使用同一原生 atlas、固定默认姿态和代际/就绪 token，不另建 PNG 缓存。

安装提交从调用方提供的 mount origin 开始，只施加 `scale(-s,-s,+s)`，不套枪身 root，也不加旧 Bedrock `24/16` 偏移。光学 semantic rig 的旧偏移留在它自己的独立分支，不传给 mesh。锚点仍来自枪械 rig/附件配置，不由模型包围盒猜测；这不意味着已自动校准枪口、握持接触或瞄准线。

Z 改装入口继续使用既有 `RefitKey`、`GunRefitScreen` 及安装/卸载服务端消息，保留允许安装、槽位、物品和锁定校验，没有另建 mesh 安装协议。画质事务同时替换枪械与 mesh 附件元数据，退休旧附件资源，并等待已请求附件的 CPU 准备和所选材质 GPU 上传；非 mesh 附件索引复用原对象。公开包两枪与两个附件已有 Z 安装/卸载和库存变更检查；sampler/mip 改动后的 Pistol、缺失 GRIP view 完整居中和已安装附件结果另有独立检查，摘要见 [实测报告](public-mesh-acceptance.md)。

公开来源的三枪、两个独立附件模板和坐标约定见 [枪包格式](mesh-gunpack-format.md#public-mesh-showcase)。可选玻璃瞄具依赖未支持的 transmission，不列入可用附件。

## `node_map` 窄桥

`node_map` 的用途是让枪机、弹匣等少数机械可见部件跟随 TaCZ/Bedrock 动画，同时让 glTF 保持高精 mesh、PBR、Skin 和 Morph 的视觉职责。它不是自动同名绑定，也不是 IK、重定向、附件桥、枪口/手位替代或 visibility 同步。

示例：

```json
{
  "render_model": {
    "type": "gltf",
    "location": "tacz:models/gltf/rifle/rifle.glb",
    "scale": 0.0625,
    "node_map": {
      "bolt": "BoltVisual",
      "magazine": "MagazineVisual",
      "additional_magazine": "ExtendedMagazineVisual"
    }
  }
}
```

权威边界：

- Bedrock rig 仍是 TaCZ 状态机、附件定位、枪口、手位和功能锚点的权威；声明 mesh 后它不是备用枪身。
- glTF skeleton/node hierarchy 负责高精视觉、Skin、Morph 和未冲突的嵌入动画。
- bridge 只写入 mapped glTF node 的世界矩阵；不桥接 Bedrock `visible`、附件、枪口、手位、文本、laser 或 stencil scope。
- 未映射 glTF 子节点继承 mapped parent 的新世界矩阵；如果 parent 和 child 都被映射，child 使用自己的绝对 override，不叠加两次 delta。

fail-closed 条件：

- `node_map` 只能用于 `type:gltf`，最多 8 项，key/value 不能空白，不能有重复 target。
- Bedrock source 必须存在、不能是 `root`，source 与路径祖先名称都必须唯一；循环 parent 链和超过实现上限的路径会被拒绝。
- `magazine` 和 `additional_magazine` 是允许同步的机械节点；附件、枪口、手位、相机、瞄具视角、抛壳、laser、adapter、动态 `text_show`、`*_pos`、`refit_*_view` 等功能/锚点 source 及其祖先受保护。
- glTF target 必须按 name 精确命中单个 node，并且实际影响 selected scene 的 rigid geometry，或被发射顶点以正权重使用的 active skin joint；缺失、重名歧义、目标重复、未使用 joint 和对最终顶点无效的 target 都会在 renderer attach 阶段拒绝。
- selected glTF animation 如果对 mapped target 或其 glTF ancestor 写入 `translation`、`rotation`、`scale`，会被拒绝，避免两个系统同时争夺同一个姿态权威。`weights` 仍允许，mapped descendant 的本地 TRS 也允许。
- bind transform 非有限，或桥接公式需要求逆的 Bedrock bind 不可逆时，在 attach 阶段拒绝；运行时 current transform 非有限或产生不可提交几何时在首个 collector submission 前失败，声明 mesh 的枪身保持不可用，不切换成旧 Bedrock 外形。

矩阵语义：

```text
A = scale(-s, -s, +s)
Gdesired = inverse(A) * Bcurrent * inverse(Bbind) * A * Gbind
correction = inverse(Bbind) * A * Gbind
per-submit: Gdesired = inverse(A) * Bcurrent * correction
```

其中 `Bbind` 和 `Bcurrent` 是排除 Bedrock `root` 后的 source root-relative world；`Gbind` 是 target glTF node 的完整 bind world；`A` 是 renderer 外层已经使用的 glTF model -> Bedrock/render basis/scale。这个形式保留 bind pose 外观，并允许 Bedrock 与 glTF 层级不完全同构。

性能与运行时 caveat：

- 一旦配置非空 `node_map`，即使 glTF 本身没有 animation，也需要 per-submit 采样 mapped pose 并重新准备几何；不能复用纯静态模型的构造期 cache。
- `node_map` 复制的是 Bedrock source 的世界空间动画 delta，不会推断两套骨架的语义；对应机械件的 rest pivot、轴向和预期运动必须由资产作者预先对齐，否则旋转可能绕错枢轴或让相邻蒙皮关节出现撕裂。
- CPU skin/morph/bridge 是否可接受必须靠真实 MC 场景 profile 判断，至少看 render thread time、allocation/GC、动态实例数和首帧纹理 upload。
- 第一人称路径会在 TaCZ 状态机更新后取 Bedrock pose；非第一人称只镜像当时宿主路径暴露的 Bedrock pose，新桥不额外创建第二套状态求值器。

## 当前已实现能力

### 资源加载

- 支持 `.gltf` JSON 和 `.glb` 二进制容器。
- 支持 glTF 内嵌 `data:` URI。
- 支持外部 `.bin`、`.png`、`.jpg`、`.jpeg`、`.ktx2`，但路径必须在主模型同一 namespace 下、相对主模型目录解析，并仍位于主模型目录和 `models/gltf` 管理树内。
- 拒绝绝对 URI、带 authority 的 URI、query、fragment、反斜杠、百分号编码、越出主模型目录的路径，以及非管理扩展名。
- `GltfModelManager` reload 时捕获按代际隔离的懒资源索引，不再常驻全枪包 encoded 字节。枪身构建走 uncached 解析，转换完成后不保留原始 parsed model；显式 clear 或 reload 增代并释放 glTF GPU 资源。

资源预算：

- snapshot 管理资源数默认上限 `8192`。
- 单个 glTF 管理资源默认上限 `64 MiB`。
- 单模型主文件与外链累计读取上限 `512 MiB`，不再把所有枪包的字节合并常驻为 snapshot。
- 动态 PBR 贴图 encoded payload 上限 `64 MiB`，单边尺寸上限 `8192`，全局 glTF 纹理逻辑预算默认 `512 MiB`，可配置 `1..2048 MiB`。
- converter 输入门禁为全资产 source vertices `1,000,000`、source indices `3,000,000`，包含未选场景/LOD 和简化前几何；另有 accessor components `16,000,000`、单 buffer `64 MiB`、累计 buffer `128 MiB` 及 morph/图像等独立预算，不能只看顶点数。
- 完成 scene/作者 LOD 选择和允许的算法简化后，再限制 selected draw 为 `300,000` emitted vertices / `100,000` triangles，按每个选中 node 实例的展开索引计数，而不是只统计唯一 mesh。检查发生在图像派生与 GPU 发布之前。输入预算放宽不保证 Original、受保护 skin/morph 或简化不足的资产能通过最终 draw 预算。

这些是 V1 防护预算，不是通用实时渲染硬上限，也不是性能许可。真实帧时间还必须按三角数、primitive 数、材质数、RenderType 数、贴图尺寸、动画实例数和 render thread 分配量实测。

### 压缩资源的归一化

加载器仅消费已经成功归一化的扩展声明，然后让原有 accessor、材质、节点和预算检查继续运行。压缩不是绕过几何限制或忽略语义的许可；资源文件不被改写。

- [`KHR_draco_mesh_compression`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_draco_mesh_compression/README.md)：读取 TRIANGLES、indices 和 unique attribute ID 映射，解码到原 accessor 的类型和布局，再保留现有 node、rig、skin 与 material 索引。当前不接受 Draco strip 或无 indices primitive。解码在随包 worker JAR 的短生命周期 Java 子进程执行，全客户端同时最多一个 worker，使用 `-Xmx256m`、`MaxDirectMemorySize=16m` 和包含等待时间的 60 秒期限。Draco 解码请求累计最多 `250,000` vertices / `300,000` indices，比普通输入预算更窄，不能沿用 `1M/3M` 作为 worker 能力。输入/输出计数与字节预算校验，取消/超时终止进程并清理私有临时目录。这隔离输入驱动的 decoder 堆分配，不是 OS 沙箱，也不是整个子进程 RSS 被限制为 256 MiB 的承诺。
- [`EXT_meshopt_compression`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Vendor/EXT_meshopt_compression/README.md)：在普通 accessor 读取前原生展开 `ATTRIBUTES`、`TRIANGLES`、`INDICES` bufferView，支持 `NONE`、`OCTAHEDRAL`、`QUATERNION`、`EXPONENTIAL` 过滤。输入范围、父 bufferView、stride、count 乘积、解码长度与当前 EXT 位流版本均先检查；解码状态非零即拒绝，临时 native buffer 在所有路径释放。这与后续画质算法简化、下述属性量化是不同层次，不代表支持 `KHR_meshopt_compression`。
- [`KHR_texture_basisu`](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_texture_basisu/README.md)：使用扩展的 `source`，校验 `image/ktx2` 和 KTX2 magic，再交给下面的角色感知派生链；不静默选择原 PNG fallback 掩盖损坏的 Basis 数据。

### KTX2/Basis 纹理与冷加载峰值

当前 CPU 路径支持 ETC1S + BasisLZ、UASTC 无超级压缩或带 Zstd，限非数组、非 cubemap 的二维图，源宽高为 `4..8192` 的 4 倍数。允许合法的部分 mip 链，不强制完整 pyramid。颜色图 DFD 必须为 sRGB/BT709，normal、metallic-roughness、occlusion 为 linear/unspecified；颜色与 normal/MR 需要 RGB/RGBA，独立 occlusion 允许 red/red-green。只接受非预乘 alpha、默认或 `rd` orientation、默认或 `rgba` swizzle；动画 KTX2 与其它格式明确拒绝。

在 libktx 分配之前，Java 检查文件/level 区间、mip 数量、DFD channel/transfer/alpha、KVD 条目、ETC1S SGD 及各 RGB/alpha slice。UASTC 每级声明的解压大小必须等于块数乘 16，不能把 Zstd 声明的任意大输出交给 native。encoded 输入最多 64 MiB，DFD+KVD 最多 1 MiB/1024 KVD 项，完整 RGBA mip 输出最多 384 MiB；这些分别约束输入、元数据和输出，不是总内存上限。

`Ktx2ImageDecoder` 使用 LWJGL KTX/libktx 转成 RGBA32，单图许可覆盖从创建到 `ktxTexture2_Destroy` 的完整 native 所有权。画质确实缩小时，仍转码文件中的全部 mip、过滤 base level、销毁原图并编码派生 PNG，磁盘缓存命中可跳过这次源转码。不是直接选取现成 2K mip。64 MiB 派生 PNG 上限在落盘前校验，编码器临时内存另计。

目标尺寸不变时保留选中图像的压缩 KTX2 源，不在画质准备阶段转码或写 PNG。实际 GPU cache miss 先通过 encoded/header/role 与全部上传层级预算，再由同一个 libktx decoder 生成 RGBA；base pixels 复制到独立 `NativeImage` 后立即销毁 libktx owner，随后复用 RGBA8 上传/逐级 mip 及像素释放链。复制期间两个 native buffer 同时存在，不能写成零复制或无冷加载峰值。GPU cache hit 不再转码；资源/画质 reload 退休该 GPU 所有者后，下次同尺寸上传仍要冷转码，并没有跨 reload 的同尺寸 PNG 缓存。

完整 8192 方形 mip 链的 RGBA 输出约 341.3 MiB，这只是输出；UASTC 块链约 85.3 MiB、encoded/Java 数据和 decoder 工作区另计。同尺寸路径复制 8K base 时还短暂增加约 256 MiB；降采样路径另有过滤和 PNG 编码开销，不能称总冷峰值只有 341 MiB。降采样缓存键包含内容、角色、尺寸、alpha/sampler、过滤和 decoder 版本；暖命中不等于解析免读 encoded 字节。降采样后的图像不再持有原大图/data URI；同尺寸图像有意保留自己的压缩源。所有既有预算不加大，也不做 BC/ASTC GPU 压缩上传或 mip streaming。

同尺寸 KTX2 修复针对旧 Bolt：合法约 61 MB ORM KTX2 被强制转成同尺寸 PNG 后超过 64 MiB，并非 Pistol sampler 再次失败。当前机制已有先失败后通过的回归测试、标准构建及原 8K/返回 2K 实机：两个 60 秒窗口分别 8,684/9,909 帧，每帧一次主体提交、零 false；8K 稳定窗口转码计数不增长，切回 2K 后旧 8K descriptor 已退出。GPU 逻辑容量、编码资源和整进程 RSS 的不同口径见 [实测报告](public-mesh-acceptance.md)，不将单枪结果推广为多把 8K 枪的容量承诺。

### glTF core 语义

已实现的 core 语义：

- glTF 2.0 版本检查和 JSON schema 校验。
- buffer、bufferView、accessor 的 offset、stride、componentType、type、count 和 normalized 读取。
- indices 支持 unsigned byte、unsigned short、unsigned int；无 indices 时生成顺序索引。
- node hierarchy、active/default scene、TRS 和 matrix。单个 node 不能同时使用 matrix 和 TRS；动画目标 node 也不能使用 matrix。
- node world transform 会覆盖 active scene roots，也会保留不在 active roots 下但 skin 可能引用的层级。
- 负 determinant transform 会在展开三角时反转 winding。

标准 `KHR_mesh_quantization` 已覆盖本 renderer 支持的 base `POSITION`、`NORMAL`、`TEXCOORD_0` 和 morph `POSITION`/`NORMAL`。声明必须同时出现在 `extensionsUsed` 与 `extensionsRequired`；整数类型、normalized 和 4 字节顶点元素对齐按对应语义检查，法线使用有符号 normalized 数据。位置/UV 保留作者的数值范围与既有变换，不根据 accessor min/max 猜测反量化缩放。此支持不包括 `TANGENT`、UV1 或 `KHR_texture_transform`；依赖这些语义的量化导出仍会拒绝。

明确拒绝：

- 支持列表为 `MSFT_lod`、`KHR_texture_basisu`、`KHR_draco_mesh_compression`、`EXT_meshopt_compression`、上述受限 `KHR_mesh_quantization`；未支持的 `extensionsUsed` 或 `extensionsRequired` 仍 fail closed，特别是 `KHR_meshopt_compression`、`KHR_texture_transform` 和其它扩展材质不能据此宣称兼容。
- `sparse` accessor。当前实现明确拒绝，避免 JGlTF 基础数据路径丢失 offset/stride 语义后产生错误结果。
- primitive `mode` 非 `TRIANGLES`。当前 V1 不支持 points、lines、strip 或 fan，也不做运行时自动转换。
- 缺少 primitive `TEXCOORD_0` 的纹理材质、`TEXCOORD_1+` 材质绑定、`JOINTS_1/WEIGHTS_1`、不成对的 `JOINTS_0/WEIGHTS_0`。
- 任何 base `TANGENT` 或 morph `TANGENT`。当前 vertex format 不提交显式 tangent，V1 选择 fail closed，避免忽略 handedness 或 morph delta 后静默错渲染。
- morph `NORMAL` 但 base primitive 没有 `NORMAL`。
- 同一 node 关联多个 mesh。
- morph target 中除 `POSITION`、`NORMAL` 以外的语义。

### PBR 材质与 shader

已实现：

- glTF core metallic-roughness 材质解析，包括 `baseColorFactor`、`metallicFactor`、`roughnessFactor`、`emissiveFactor`、`normalTexture.scale`、`occlusionTexture.strength`、`alphaMode`、`alphaCutoff`、`doubleSided`。
- 贴图槽包括 base color、metallic-roughness、normal、occlusion、emissive。
- base color、emissive、PBR 参数因子通过 1x1 RGBA8 动态纹理传入 shader；精度为 UNORM8，`normalScale` 当前映射到 `[0, 4]`。
- 资产输入接受 PNG/JPEG 和上述受限 KTX2/Basis；真正降采样的 KTX2 使用派生 PNG，同尺寸 KTX2 保留压缩源。GPU 上传入口按实际格式校验，再在缓存缺失且预算通过后解码/上传，不忽略 material role。
- 图片 header、decoded byte 预算和稳定动态 ID 在 texture source 构造时预计算；重复材质绑定不会每帧重扫或哈希完整图片。首次实际使用仍在 render thread 同步 decode/upload，可能造成首帧卡顿。
- sampler 支持 mag `NEAREST`/`LINEAR`；min `NEAREST`、`LINEAR`、`NEAREST_MIPMAP_LINEAR` (`9986`)、`LINEAR_MIPMAP_LINEAR` (`9987`)；S/T 两轴分别选择 `REPEAT` 或 `CLAMP_TO_EDGE`。每个纹理槽的 sampler 进入不可变 material/RenderType key，不修改共享图像的采样状态，也不改写资产采样声明。
- 自定义 shader 实现 direct-light GGX，结合 Minecraft lightmap、环境光和 fog。
- `OPAQUE`、`MASK`、`BLEND` 映射到独立 pipeline；`BLEND` 使用透明混合且关闭深度写入。

`NEAREST_MIPMAP_NEAREST` (`9984`)、`LINEAR_MIPMAP_NEAREST` (`9985`) 与 `MIRRORED_REPEAT` 仍明确拒绝：当前 MC 原生 sampler API 不能精确表达这些状态，不以近似采样冒充支持。Service Pistol 保留原始 `9987`，原始/打包资产的 CPU 链和最新源版本 2K 实机样例已有证据：三张 `2048x2048` RGBA8 图、每张 12 级 mip，上传 sampler 查询为 `9987/9729`、双轴 REPEAT、maxLod 1000；第一人称、快捷栏与 Z 改装截图对应同一轮。上传 sampler 观测不是逐 draw 绑定证明，也不扩大为所有 sampler 或场景通过，见 [当前实机证据](mesh-video-quality.md#current-native-evidence)。

mipmapped 图像在实际 GPU 上传时逐级 CPU 过滤，复用颜色线性光、ORM 线性通道、normal 归一化、透明 alpha 加权与 MASK 覆盖近似规则，按 S/T wrap 处理边界。最多同时持有当前与下一层 CPU 图像，每层提交后释放；失败路径也释放像素和新 GPU 所有者。GPU 预算在 decode 前按实际全部层级 RGBA8 字节之和检查，不只计算 base level。

层级是 MC 26.2 分配器 `width >> level` / `height >> level` 均非零的合法前缀，而非任意长宽比的完整数学尾链：方形纹理到 `1x1`，`3x5` 为 `3x5 -> 1x2`，`1x8` 只有 base level。生成 mip 的存储身份包含 role、alpha 参数、wrap、层数及过滤/前缀版本；非 mip 的相同 encoded 内容仍可共享 GPU 图像，采样状态由各材质槽独立绑定。派生磁盘缓存保存画质缩放后的 base PNG，上传 mip 不等于 KTX2 源 mip streaming 或另一套持久像素缓存。

明确不是完整 glTF PBR：

- 没有 glTF IBL、环境反射、clearcoat、transmission、volume、sheen 等扩展材质。
- 没有 glTF 级阴影实现。
- 没有 glTF 标准 tone mapping。
- 当前 vertex format 不传显式 tangent 到 shader；converter 对 base/morph `TANGENT` fail closed。normal map 仅对没有显式 tangent 的资产使用 derivatives/UV/normal 重建 cotangent frame。
- 因子纹理是 MC 26.2 RenderType/uniform 约束下的工程折中，不等于无限精度 material uniform。

### skin、morph、animation

已实现：

- CPU morph 支持 `POSITION`、`NORMAL` delta；显式 `TANGENT` delta 在 V1 fail closed。
- CPU linear blend skinning。当前仅支持 `JOINTS_0/WEIGHTS_0`，每顶点最多 4 influences；权重会校验有限、非负、非零并归一化。
- 处理顺序为 morph 后 skin，符合 glTF morph 在 skinning 前应用的语义。
- `inverseBindMatrices` 可选；未提供时按 identity 处理；提供时 count 必须大于等于 `skin.joints`，只读取前 N 个 joint 对应矩阵。
- `skin.joints[]` 的局部 joint index 和 node index 区分处理。
- animation 支持 target path `translation`、`rotation`、`scale`、`weights`。
- interpolation 支持 `STEP`、`LINEAR`、`CUBICSPLINE`；rotation LINEAR 使用 slerp 并归一化，CUBICSPLINE output 按 in tangent、value、out tangent 三元组处理。

当前限制：

- 没有 GPU skin/morph。
- 没有多权重集。
- 非循环动画不纳入 V1 支持面；选择 animation 且 `loop_animation:false` 时 renderer 构造明确拒绝。
- 没有把 glTF animation 接成 TacZ 行为状态机；V1 只支持 `render_model.animation` 指定的枪身 clip 采样。
- `node_map` 开启后，selected clip 不能对 mapped target 或其 glTF ancestor 写入 TRS；`weights` 和 mapped descendant TRS 仍按 glTF animation 语义采样。
- CPU skin/morph/animated geometry 路径已由单测覆盖，真实高精动态 mesh、多实例枪包和帧时间仍需 MC runtime profile。

### 枪身动态 renderer 集成

已实现：

- `GunDisplayInstance` 先构造操作用 Bedrock rig，再挂载 glTF body renderer；声明 mesh 后，仅完整、可用的 mesh 模型能从 getter 发布，挂载失败记录 warn，不暴露占位 Bedrock 枪身。
- `BedrockGunModel` 保持附件缓存、功能渲染器、手部、枪口、抛壳、文本、laser 和 adapter 可见性逻辑。
- 第一人称渲染中，功能渲染器先通过 Bedrock 路径采集；若存在 custom body renderer，则枪身主体由 glTF retained submit 替换。
- custom body 失败时禁用该 renderer，同帧避免叠画；此后的 mesh 主体仍不可用，不在下一帧画旧 Bedrock。普通未声明 mesh 的枪保持其原路径。
- resource generation 已失效的 renderer 在 custom-body 路由前原子撤销，安装 renderer 前再次复检 generation；旧 renderer 不可跨资源代际继续提交。
- mounted attachment 保留原安装/功能入口，但声明 glTF 的附件在 mount origin 提交自己的 mesh；独立附件物品与 GUI 也使用同一资源链。可选 Bedrock 光学 semantic rig 独立提交，不作为可见主体兜底。枪身 custom body 与 integrated scope 的完整模板裁剪仍需专项验证。
- `node_map` 支持少量显式 Bedrock source 到 glTF target 的 transform 同步。它只用于枪机、弹匣等机械可见节点；Bedrock 功能锚点、附件、枪口和手位仍由原路径独占。
- 未选择 animation 的静态几何在 renderer 构造时准备并复用；选择 animation 后的 morph/skin/三角展开仍是 CPU per-submit 路径，必须用真实多实例场景 profile。
- 配置非空 `node_map` 时，即使没有 glTF animation，也会进入 per-submit 几何准备，因为 mapped Bedrock pose 每帧可能改变。

透明排序：

- `BLEND` primitive 会在捕获的 model-view 空间内按三角 back-to-front 排序。
- `OPAQUE` 和 `MASK` 排在 `BLEND` 前。
- 同材质/同 RenderType 内三角顺序是 V1 能力边界。
- 跨 `BLEND` 材质、跨 RenderType 或跨实例的全局透明顺序没有保证；Minecraft batching 会让跨材质/实例顺序只能 best effort。

## V1 capability matrix

表内历史 AK/Bolt 的实机证据只证明各自报告版本；当前源码能力不自动继承这些视觉结论。新增量化、公开三枪包及 mesh 附件仍以本轮独立验收为准。

| 能力 | 当前状态 | V1 策略 | 已有证据/仍缺 |
| --- | --- | --- | --- |
| `render_model` opt-in | 已在本项目实现 | 不写即 Bedrock 默认；`type:gltf` 才启用枪身动态 renderer | config 单测；本地 `run/client_a` AK smoke 视觉通过，未改 packaged default AK |
| `.gltf` / `.glb` | 已在本项目实现 | 主模型只允许 `models/gltf/**.gltf|.glb` | loader 单测；真实 Blender 5.1 GLB 加载与第一人称渲染通过 |
| 外部引用 / data URI | 已在本项目实现 | data URI 支持；外部资源限同 namespace、主模型目录内相对路径和管理扩展 | resolver/manager 单测；历史 GLB 样本为内嵌 buffer、无外部 URI |
| PNG / JPEG / KTX2 Basis | 已在本项目实现 | PNG/JPEG 直接或降采样；KTX2 同尺寸保留压缩源并在 GPU miss 转码，缩小时派生 PNG | 真实 fixture；第三轮 Bolt 原 8K 稳定上传、再次冷重载及返回 2K 退出旧 descriptor，非通用材质穷举 |
| GUI mesh 图标 | 已在本项目实现 | 固定默认姿态送原生 2D atlas，代际/就绪身份键，不生成另一套截图纹理 | GUI frame/identity 单测；普通/创造物品栏、快捷栏及HUD截图；多把独立mesh性能未验 |
| mesh 枪身权威 | 已在本项目实现 | 现有枪身入口不加载旧 Bedrock LOD，不以旧主体/旧图标代替不可用 mesh | 路由测试、第一/第三人称与角色预览截图、GROUND/FIXED成功提交计数；范围见验收报告 |
| accessor stride/normalized | 已在本项目实现 | 统一展开为内部数组并校验有限值 | converter 单测 |
| 资产安全预算 | 已在本项目实现 | 输入 1M vertices / 3M indices，独立 byte/components 上限；LOD/简化后 selected draw 300k emitted vertices / 100k triangles；Draco 更窄 | `GltfAssetLimits`、simplifier 与 Draco 门禁 |
| sparse accessor | 已在本项目实现为拒绝 | fail closed，不静默降级 | converter 负例单测 |
| mesh primitive | 已在本项目实现 | `TRIANGLES` only；无 indices 生成顺序索引 | converter/renderer 单测 |
| strip/fan/points/lines | 已在本项目实现为拒绝 | 不自动转 triangle list，不纳入 V1 runtime | converter 负例单测 |
| glTF extensions | 支持 `MSFT_lod`、BasisU、Draco、`EXT_meshopt_compression`、受限 `KHR_mesh_quantization` | 压缩先有界归一化，量化按已实现语义校验；其它扩展仍拒绝 | loader/decoder/converter/LOD 测试；Draco+KTX2 Bolt 的 2026-09-08 实机证据不证明新增量化路径 |
| texture sampler / mip | 受限原生 sampler 与真实逐级上传已实现 | mag 9728/9729；min 9728/9729/9986/9987；S/T 各自 REPEAT/CLAMP；拒绝 9984/9985/MIRRORED_REPEAT；非零尺寸 mip 前缀 | 自动测试；Pistol 2K/12级 mip 的 GPU descriptor、上传 sampler 和 6,194 帧样例，不代表全部采样组合 |
| PBR core MR | 已在本项目实现 | direct-light GGX + MC lightmap/ambient/fog 的受限近似 | shader/单测；factor-only PBR 实机可见，贴图/normal/复杂材质仍待视觉验证 |
| tangent | 已在本项目实现为拒绝边界 | base/morph `TANGENT` fail closed；无显式 tangent 的 normal map 使用 derivative TBN | converter 负例单测；normal map runtime 待验 |
| alpha OPAQUE/MASK | 已在本项目实现 | 独立 pipeline | renderer/shader 代码；视觉待验 |
| alpha BLEND | 已在本项目实现但受限 | 同材质三角排序；跨 BLEND 材质不保证 | renderer support 单测；复杂透明待验 |
| skin `JOINTS_0/WEIGHTS_0` | 已在本项目实现 | CPU LBS，最多 4 influences | runtime/converter 单测 |
| `JOINTS_1+` | 已在本项目实现为拒绝 | fail closed | converter 负例单测 |
| morph `POSITION/NORMAL` | 已在本项目实现 | CPU morph 后 CPU LBS；morph `NORMAL` 要求 base `NORMAL`，morph `TANGENT` fail closed | converter/runtime 单测 |
| animation STEP/LINEAR/CUBICSPLINE | 已在本项目实现 | 秒为单位，严格递增时间，rotation slerp | runtime/converter 单测 |
| 非循环 animation | 已在本项目实现为拒绝 | 选择 animation 且 `loop_animation:false` 时 fail closed；不承诺 stop-at-end | renderer/config 单测 |
| `node_map` | 已在本项目实现 | 最多 8 个 exact source->target transform override；无自动同名、无 visibility、无通用 retarget | config/bridge/renderer 单测；AK shoot/reload smoke 有动作差异，但静态截图不单独隔离 bolt/magazine 矩阵 |
| Bedrock 附件/功能桥 | 已在本项目实现 | 枪身与声明 mesh 的附件各自负责可见主体；操作、手位和功能锚点仍归 Bedrock | 历史 AK smoke 不证明新附件路径；附件/瞄具完整矩阵仍待专项验证 |
| 独立 mesh 附件 | 已在本项目实现 | 通用 display 配置、无 root 的 mount origin、共享 GUI atlas 与质量事务；附件拒绝 node_map | Z、代际替换及 FIXED/GROUND/手持证据；512 复测三者均 2003/2003，window 2 的 117 帧缺口保留且原因未定 |
| 通用 BakedModel glTF 导入 | 研究但不纳入 V1 | 当前不是 V1 实现目标 | 不应写成已实现 |
| LOD | 视频质量配置 + 作者 `MSFT_lod` + rigid 自动简化 | 按配置代际选档，非逐帧距离 streaming | 见 `mesh-video-quality.md` 与对应版本验收记录 |
| streaming / meshlet / Nanite | 研究但不纳入 V1 | 只作为后续高精 mesh 资料 | V1 不设验收 |
| GPU-driven / GPU skin-morph | 研究但不纳入 V1 | CPU 正确且 profile 证明瓶颈后再评估 | V1 不设验收 |
| MC 客户端启动/资源/shader | 历史版本部分实机通过 | 历史启动、世界和 PBR shader link 证据不代替本轮 atlas/压缩/mesh 权威路由验收 | `run/client_a` 历史日志与截图；本轮 runtime 另记 |
| MC 真实 glTF 枪包视觉 | 第一人称样例通过 | 本地 AK smoke 的 idle/shoot/reload 已截图；第三人称、远距 glTF LOD、透明重叠和多实例 profile 未验证 | 见 `tools/blender/neotacz_cc0_rifle/TEST_EVIDENCE.md` |

## 大型游戏高精度 mesh 机制

成熟实时引擎不会把任意高精 mesh 直接塞进运行时 draw path。常见机制是离线转换、运行时预算和 profile 驱动的组合。

LOD：

- 成熟做法是多套 mesh/material 随距离或屏幕尺寸切换。
- 当前实现：视频设置控制固定档位，作者 `MSFT_lod` 优先，无作者分级的 rigid primitive 用 meshoptimizer 简化；不实现动态距离/屏幕覆盖切换。见 `mesh-video-quality.md`。

Streaming：

- 大型引擎会按视野、距离、mip、mesh page 或 cluster residency 控制内存。
- 当前实现：懒资源索引、低分辨率派生贴图缓存和代际 GPU 释放；仍不做通用几何/GLB streaming。

Meshlets / clusters：

- meshlet/cluster 用于 GPU culling、mesh shader、cluster bounds 和更细粒度提交。
- 对 MC V1 的结论：适合作为 meshoptimizer/gltfpack 等离线工具链资料；不进入 Java-first 首版 runtime。

Virtual geometry / Nanite：

- Nanite 类方案是专用虚拟几何子系统，包含 cluster hierarchy、streaming、自动 LOD、culling 和专用 raster path。
- 对 MC V1 的结论：不适合作为 NeoForge 26.2 Java-first glTF 首版目标。

GPU-driven 和 GPU skin/morph：

- 代表机制包括 GPU culling、indirect draw、mesh shader、compute skin/morph、骨骼纹理或 SSBO/UBO。
- 对 MC V1 的结论：当前 CPU skin/morph 更可控。只有当 MC runtime profile 证明 render thread 或 CPU 变形是瓶颈，且 RenderType/shader 数据通路稳定后才应升级。

## 离线转换要求

必须离线转换或简化：

- CAD/NURBS/STEP/IGES：先离线 tessellate 成三角网格，再导出 glTF core；运行时不解析曲面。
- 扫描点云：先离线重建、抽稀、法线生成、UV/材质整理，再进入 glTF；V1 不渲染点云。
- 超高面数资产：先离线 decimate、合并材质、生成 mip/贴图预算，必要时准备手工 LOD。
- `KHR_meshopt_compression`、`KHR_texture_transform`、clearcoat、transmission、volume、sheen 等未支持扩展：先离线导出为支持语义。不能只删除声明而保留需要该扩展解释的数据。`KHR_mesh_quantization` 无需一概去除，但必须满足上文已实现语义范围。
- Draco、`EXT_meshopt_compression`、KTX2/BasisU 可在上文受限合同内作为 required extension；超过对应尺寸、解码内存、类型或几何预算的压缩资产仍须离线调整。
- 超出输入 byte/components 或 `1M` vertices / `3M` indices 的资产必须先离线调整；输入合规但 LOD/允许的简化后仍超过 `300k` emitted vertices / `100k` triangles 的选中模型也会拒绝。减少材质数不能替代几何预算检查。

需要实测而不是写死硬上限：

- render thread CPU time、GPU frame time、P95/P99 frame time。
- 每枪三角数、vertex 数、primitive 数、材质/RenderType 数。
- 动态贴图 decoded bytes、upload 次数、cache 命中率。
- skin/morph 动态实例数、每帧 Java 分配量和 GC。
- alpha BLEND 重叠场景的视觉稳定性。

不要臆造统一三角形硬上限。首版可给资产包推荐预算，但必须来自目标机器、真实 MC 场景和 profile 数据。

## 拒绝的虚假降级

- 忽略 `extensionsUsed/Required` 后继续渲染并声称成功。
- 忽略 `sparse` accessor，或把缺失 base/override 数据当零填充。
- 把 `TRIANGLE_STRIP`、`TRIANGLE_FAN`、points、lines 当 triangle list 直接读。
- 忽略作者的 per-slot sampler，或把未支持的 `9984`/`9985`/`MIRRORED_REPEAT` 静默替换成其它采样状态。
- 只读取 baseColor 就声称支持 PBR。
- 把 metallic-roughness 贴图当 RGB 颜色图，或忽略 G roughness / B metallic 通道语义。
- 把 sRGB 和 linear 数据统一当同一种颜色空间。
- 把 morph target 当绝对 position。
- 用 node index 替代 skin joint index。
- 用普通 lerp 静默替代 `CUBICSPLINE` 或 quaternion slerp。
- 把 `node_map` 写成全骨架 retarget、IK、自动同名绑定或附件/枪口/手位桥。
- 在 selected animation 中同时驱动 mapped target/ancestor TRS，又让 Bedrock bridge 覆盖同一节点。
- 把 Bedrock visibility 当成 glTF node visibility 或 Morph 权重同步。
- 把同材质三角排序写成跨材质透明全局正确。
- 把非循环动画静默 clamp 到末尾并写成 V1 支持。
- 把 CPU 单测/Gradle 构建或真实 native fixture 解码通过写成 Minecraft 客户端 runtime 已验证。
- 把 mesh 加载失败画成另一把 Bedrock 枪，或把旧距离 LOD 当成 mesh 自身的低档。
- 把 KTX2 文件变小等同于当前 RGBA8 GPU 驻留变小，或把全 mip RGBA 输出预算当总冷加载峰值。

## 后续验证清单

历史 AK 样例完成过 Khronos 结构验证、Blender 重导入、本地第一人称
idle/shoot/reload 截图和 Gradle 构建。这些历史结果不证明本次视频设置、
MSFT_lod、降采样及接触表面保持已通过实机验收；本次结果以当前验收报告为准。
以下仍是独立门禁：

- 用 Khronos Sample Renderer/Sample Viewer 做视觉参考，对照 PBR、normal、alpha、skin、morph 和 animation。
- MC 客户端验证：手持第一人称、第三人称、地面实体、展示框、改装界面、附件切换、瞄具安装、枪口/抛壳/手臂、`node_map` 枪机/弹匣同步、reload 后资源释放。
- 压力场景验证：多实例枪械、BLEND 重叠材质、较大贴图、动画循环与非循环、资源包 reload、mesh 失败/未就绪占位与恢复。
- 新路径验证：普通/创造栏及快捷栏 2D atlas 缓存与代际失效、旧距离 LOD 禁用后的各视角、真实 Draco/EXT_meshopt/KTX2 枪包冷暖加载、峰值与取消。
- Profile 指标：CPU render thread time、GPU frame time、P95/P99、alloc/GC、decoded texture bytes、dynamic texture cache size、primitive/material/RenderType 分布。

## 一手资料入口

- glTF 2.0 Specification：https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
- Khronos glTF Validator：https://github.com/KhronosGroup/glTF-Validator
- Khronos glTF Sample Renderer：https://github.com/KhronosGroup/glTF-Sample-Renderer
- Khronos glTF Sample Assets：https://github.com/KhronosGroup/glTF-Sample-Assets/blob/main/Models/Models.md
- Khronos glTF Assets Browser：https://github.khronos.org/glTF-Assets/
- NeoForge BakedModel 文档：https://docs.neoforged.net/docs/1.21.1/resources/client/models/bakedmodel
- NeoForge Custom Model Loaders 文档：https://docs.neoforged.net/docs/1.21.4/resources/client/models/modelloaders/
- Godot RetargetModifier3D：https://docs.godotengine.org/en/stable/classes/class_retargetmodifier3d.html
- Ozz Animation：https://github.com/guillaumeblanc/ozz-animation
- Ozz blend sample：https://github.com/guillaumeblanc/ozz-animation/blob/master/samples/blend/README.md
- Unreal Engine Animation Retargeting：https://dev.epicgames.com/documentation/en-us/unreal-engine/animation-retargeting-in-unreal-engine
- Gleicher 1998 Retargetting Motion to New Characters：https://graphics.cs.wisc.edu/Papers/1998/Gle98/
- JOML Anti Patterns and Best Practices：https://github.com/JOML-CI/JOML/wiki/Anti-Patterns-and-Best-Practices
- JOML project：https://github.com/JOML-CI/JOML
- Unity LODGroup 文档：https://docs.unity3d.com/6000.1/Documentation/Manual/class-LODGroup.html
- Unity draw call 优化文档：https://docs.unity3d.com/6000.5/Documentation/Manual/optimizing-draw-calls.html
- Unreal Engine Nanite 文档：https://dev.epicgames.com/documentation/unreal-engine/nanite-virtualized-geometry-in-unreal-engine
- Nanite SIGGRAPH 2021 author project page：https://www.wihlidal.com/projects/nanite-deepdive/
- DirectX Mesh Shader 规范：https://microsoft.github.io/DirectX-Specs/d3d/MeshShader.html
- NVIDIA Turing Mesh Shaders：https://developer.nvidia.com/blog/introduction-turing-mesh-shaders/
- AMD GPUOpen Mesh Shaders：https://gpuopen.com/learn/mesh_shaders/mesh_shaders-from_vertex_shader_to_mesh_shader/
- meshoptimizer：https://github.com/zeux/meshoptimizer
- meshoptimizer 文档：https://meshoptimizer.org/v1.html
- Performance Comparison of Meshlet Generation Strategies, JCGT 12(2)：https://jcgt.org/published/0012/02/01/
- End-to-End Compressed Meshlet Rendering：https://onlinelibrary.wiley.com/doi/10.1111/cgf.15002
- Google Filament material model：https://google.github.io/filament/Materials.md.html
- SIGGRAPH 2012 PBR course / Disney BRDF：https://blog.selfshadow.com/publications/s2012-shading-course/
- SIGGRAPH 2013 PBR course：https://blog.selfshadow.com/publications/s2013-shading-course/

## 代表作品、论文与公开实现

- Khronos glTF 2.0 Specification：core mesh、accessor、material、skin、animation、morph 语义的首要来源。
- [KHR_texture_basisu](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_texture_basisu/README.md)、[KHR_draco_mesh_compression](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_draco_mesh_compression/README.md)、[EXT_meshopt_compression](https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Vendor/EXT_meshopt_compression/README.md)：压缩扩展的格式、引用及语义合同；本地受限实现仍以上述预算与拒绝边界为准。
- Khronos glTF Validator：资产合法性、stats 和 CI 验证入口。
- Khronos glTF Sample Renderer / Sample Assets：PBR、skin、morph、animation 的公开参考对照。
- Godot RetargetModifier3D、Unreal Animation Retargeting、Gleicher 1998：都把 retarget 视为显式骨架/姿态映射问题；neotacz V1 只借鉴“明确映射和权威边界”，不实现通用角色动作迁移。
- Ozz Animation：公开 skeletal animation runtime/toolset 参考；neotacz V1 不引入它的 C++ runtime，但保留“离线处理、运行时轻量采样、少量 blend/override”的工程方向。
- JOML：当前 Java 矩阵实现依据；bridge 代码保持显式 destination 参数和可复用矩阵，避免 per-frame 隐式分配扩大。
- Brent Burley, "Physically-Based Shading at Disney"：artist-friendly PBR 和 Disney BRDF 代表论文。
- Brian Karis, "Real Shading in Unreal Engine 4"：实时 PBR 工程化参考。
- Google Filament：公开实时 PBR renderer/material model 参考。
- Epic Nanite / SIGGRAPH 2021 deep dive：virtual geometry、cluster hierarchy、streaming 和自动 LOD 代表公开作品。
- DirectX Mesh Shader、NVIDIA Turing Mesh Shaders、AMD GPUOpen Mesh Shaders：meshlet、cluster、GPU geometry pipeline 代表资料。
- meshoptimizer/gltfpack：离线 mesh 优化、简化、vertex/index cache、meshlet 数据的公开工具链。
- JCGT meshlet 生成策略对比与 End-to-End Compressed Meshlet Rendering：代表 meshlet 聚类质量、数据组织、压缩和 GPU-driven 解码研究；只作为后续架构资料，不是 V1 已实现能力。

## 当前结论

V1 已经从“研究计划”推进到“受限 glTF 枪身与附件 renderer”：受限 core/属性量化、MSFT_lod、Draco/EXT_meshopt 归一化、KTX2/Basis 派生、原生 GUI atlas、mesh 主体权威路由、PBR 受限 shader/sampler 与 mip 上传、CPU morph/LBS、三种动画插值、输入/选中 draw 双预算和枪身窄 `node_map` bridge 都有源码及测试入口。未声明 mesh 的旧枪/附件兼容路径仍保留，但不作为声明 mesh 的备用外形。它仍不是完整 glTF viewer、通用 BakedModel importer、全骨架 retarget、GPU 压缩/streaming、跨材质透明全局正确或 AAA/Nanite runtime。

第二轮限定证据覆盖三枪/两附件、Pistol 2K/mip、512 与 2K 旧尺寸 descriptor 退出、物品栏/第三人称/地面/展示和既有 Bedrock 8x 瞄具回归。51 个 descriptor 的逻辑字节为 512 档 `11,534,496`、2K 档 `184,549,536`，不是 VRAM。展示场景 17 次主体/附件提交每帧、2,035 帧零 false，但实际约 `33.9 FPS`、p95 帧间隔 `32.478 ms`，不能写成“十几把枪流畅”。512 重测已稳定，仍保留原 117 帧缺口且不倒推其原因；既有 scope 通过不代表 mesh transmission 可用。第三轮补齐了 P8 原 8K KTX、暖 GPU 命中、再次冷重载与返回 2K 的限定实机验证；8K 冷加载依然有显著整进程峰值。完整来源与测量口径见 [当前实机证据](mesh-video-quality.md#current-native-evidence) 与 [正式验收报告](public-mesh-acceptance.md)。
