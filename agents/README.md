# OHOS HAP on Android

在 Android 上直接运行 OpenHarmony 原生 HAP 包。

## 目标

以 ArkUI-X 的 Android 构建体系为基础，对其运行时中模块路由和 record 名解析的关键路径做定向适配，使 Android 设备能够加载并运行 OHOS 原生格式的 HAP 应用。

## 核心思路

ArkUI-X 和 OpenHarmony 共享同一份 arkcompiler 和 ace_engine 核心代码（非 fork，直接指向 OHOS 固定 tag），差异仅在 Android/iOS adapter 层。阻塞点集中在**模块入口路由**和 **record 名格式适配**两个窄点，不需要重写整个运行时。

## 文档索引

详细技术分析见 [SUMMARY.md](SUMMARY.md)，当前已覆盖：

| 类别 | 文档 | 说明 |
|------|------|------|
| 加载流程 | `ohos-script-loading.md` `arkui-x-script-loading.md` `ohos-hap-boot-flow.md` | OHOS / ArkUI-X 完整加载调用链 |
| 构建系统 | `arkui-x-android-build.md` `arkui-x-build-system.md` `libarkui-android-contents.md` | Android .so 构建体系与内容分析 |
| 初始化 | `arkui-x-android-init.md` | Activity.onCreate() → 首帧序列 |
| 核心冲突 | `module-record-mismatch.md` `abc-path-routing.md` | record 名差异根因、三路分支路由 |
| 运行时 | `bundle-vs-esmodule.md` `ecmavm-creation.md` `napi-module-resolution.md` `abc-bytecode-format.md` | 双模式、EcmaVM、模块解析、ABC 格式 |
| 平台抽象 | `platform-abstraction-layer.md` | 7 个平台抽象层差异 |
| Stage 模型 | `stage-model-architecture.md` `ohos-build-pipeline.md` | 组件/生命周期/编译流水线 |
| 渲染 | `arkui-rendering-android.md` | Virtual Rosen + SurfaceView/Skia |
| HAP 样本 | `test-hap-analysis.md` | 测试 HAP 结构、依赖分类、复杂度 |
| 验证评估 | `information-gap-analysis.md` | 交叉验证、信息充分性、缺口分析 |
| 技术方案 | `technical-plan.md` | 完整技术方案、阻塞点分析、替代方案 |

## 当前状态

ArkUI-X 6.1-Release 移植已完成，5 个仓库共 10 个 cherry-pick + 9 个 manual commit。5 个已安装 HAP 中 4 个正常渲染。详见 [PROGRESS.md](PROGRESS.md)。

### 已否决：Plan A（直接调用 libarkui_android.so）
`ExecuteModuleBuffer` 是 hidden 符号，dlsym 不可见；`GetOutEntryPoint` 会添加 bundleName 前缀导致 record 名不匹配。

### 当前方案：基于 ArkUI-X 6.1-Release 构建自定义运行时
5 个仓库定向适配（详见 [ARKUI-X_PATCHES.md](../docs/ARKUI-X_PATCHES.md)），覆盖 ETS 运行时、应用框架、Android 适配器、NAPI 模块管理、构建系统。支持 OHOS SDK 5.0/6.0 双版本 HAP 格式。

## 参考源码

- ArkUI-X: ArkUI-X 源码
- OpenHarmony: OHOS 源码
- 官方文档: ArkUI-X 源码 `docs/zh-cn/`、OHOS 源码 `docs/zh-cn/`
