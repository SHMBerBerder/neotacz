# NeoTACZ 26.3 Port

这是 `SHMBerBerder` 维护的 TACZ 非官方 NeoForge/Minecraft 26.3 移植仓库。

## 项目定位

- 版本：Minecraft 26.3、NeoForge 26.3.0.7-beta、Java 25
- 当前分支为 `26.3`；旧版本保留在 `26.2` 分支。
- 这个仓库非TACZ官方发布仓库
- 项目内部如保留 `tacz` 标识、原名称或原作者字段，仅用于兼容和上游署名，不表示官方维护关系。

## 非官方声明

本项目与 TACZ 官方版无关，不由原 TACZ 团队维护，也不代表官方更新路线、发布节奏或技术支持。
官方版本、反馈入口和文档请以 TACZ 官方渠道为准。

## 移植范围

- 将 TACZ 代码和资源适配到 NeoForge/Minecraft 26.3，使用指定的 SimpleBedrockModel 26.3 子模块。
- 本仓库产物仅代表此移植工程，不应被当作官方 TACZ 版本。

## 构建

克隆时需要取得子模块：

```sh
git clone --branch 26.3 --recurse-submodules https://github.com/SHMBerBerder/neotacz.git
cd neotacz
./gradlew --no-daemon build
```

已有克隆切换版本后运行 `git submodule update --init --recursive`。Windows 使用 `gradlew.bat`。
构建使用 Java 25，包含自动测试；产物位于 `build/libs/`。首次构建需要联网下载依赖。

## 26.3 已知限制

- 已通过 514 项自动测试，并完成限定场景的 macOS 实机检查；不代表与 1.20.1、26.2 的全部功能和 UI 已完全一致。
- 当前配置中的旧版 KubeJS、ShoulderSurfing、Controllable 在 26.3 存在已复现的外部兼容崩溃，不应直接加入运行环境。Iris/AR 的完整外部渲染引擎兼容尚未验证。
- GUI 逻辑宽度为 320 时，NeoForge 模组列表配置按钮越界，Cloth Config 展开侧栏后存在字段遮挡；这两项第三方布局问题尚未修复。
- 全部语言、渲染组合、Windows/Linux、Vulkan 和 8K GPU 驻留或性能未完成实机验证。

## 更新与文档

- [更新记录](CHANGELOG.md)
- [视频设置与画质](docs/mesh-video-quality.md)
- [独立 mesh 枪包格式](docs/mesh-gunpack-format.md)
- [glTF 渲染支持范围](docs/gltf-rendering.md)
- [公开高模与配件实测](docs/public-mesh-acceptance.md)

## 上游致谢

本移植基于 Timeless and Classics Guns: Zero (TACZ)。原作者和贡献者保留其对应署名与许可权利。

## License

- Code: [GNU GPL 3.0](https://www.gnu.org/licenses/gpl-3.0.txt)
- Assets: [CC BY-NC-ND 4.0](https://creativecommons.org/licenses/by-nc-nd/4.0/)
