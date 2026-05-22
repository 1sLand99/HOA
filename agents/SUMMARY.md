# OHOS HAP on Android — 技术调研文件索引

> **当前状态** (2026-05-21): ArkUI-X 6.1-Release 移植完成，5 个仓库定向适配，5 个已安装 HAP 中 4 个正常渲染。详见 [PROGRESS.md](PROGRESS.md)。

## 项目目标

在 Android 上直接运行 OpenHarmony 原生 HAP 包，参考 ArkUI-X 构建方案和 OHOS 原生运行机制。

## 参考源码

- ArkUI-X: ArkUI-X 源码 (build.sh at root)
- OpenHarmony: OHOS 源码

---

## 文件索引

### 核心加载流程

- [arkui-x-script-loading.md](arkui-x-script-loading.md) — ArkUI-X 完整的 6 层调用链，RunScriptBuffer 路径，Plan A 失败分析
- [ohos-script-loading.md](ohos-script-loading.md) — OpenHarmony 原生 8 阶段加载流程，双路径分流 (Path A OhmUrl / Path B 文件路径)
- [ohos-hap-boot-flow.md](ohos-hap-boot-flow.md) — OHOS 完整启动流程 (AppSpawn → MainThread → JsRuntime → ArkCompiler → JsUIAbility)

### 构建系统

- [arkui-x-android-build.md](arkui-x-android-build.md) — ArkUI-X APK 结构和 libarkui_android.so 构建分析，符号导出，关键源码路径
- [arkui-x-android-init.md](arkui-x-android-init.md) — Activity.onCreate() 到首帧的 8 阶段初始化序列
- [jni-bridge-analysis.md](jni-bridge-analysis.md) — **JNI 桥接层完整分析**：两个 JNI 渠道、LoadModule/DispatchOnCreate 双路径、参数转换链、instanceName 格式

### 核心不兼容点

- [why-patch-needed.md](why-patch-needed.md) — **为什么需要改代码**：代码相同但运行时条件不同（三路分支命中、Bundle/ES Module 模式、双维度 record 差异），不改代码的替代方案权衡
- [module-record-mismatch.md](module-record-mismatch.md) — OHOS HAP 与 ArkUI-X 的 ABC record 命名差异，Plan A 失败根因
- [abc-path-routing.md](abc-path-routing.md) — ParseAbcPathAndOhmUrl 三路分支路由，GetOutEntryPoint 平台差异
- [srcEntry-to-entrypoint-chain.md](srcEntry-to-entrypoint-chain.md) — **srcEntry→entryPoint 完整转换链**：OHOS vs ArkUI-X 逐行源码追踪、record 名双维度差异（bundleName + src/main/）、Patch 策略调整

### 运行时子系统

- [bundle-vs-esmodule.md](bundle-vs-esmodule.md) — **Bundle vs ES Module 双模式**：源码决策链（两层判断）、行为差异表、历史原因、对 HAP-on-Android 的影响
- [ecmavm-creation.md](ecmavm-creation.md) — EcmaVM 创建与配置，Bundle vs ES Module 模式，预加载机制
- [napi-module-resolution.md](napi-module-resolution.md) — @ohos:/@bundle:/@native:/@app: 模块前缀解析，NativeModuleManager 加载
- [napi-module-registration.md](napi-module-registration.md) — **NAPI 模块注册机制**：静态注册/动态加载、Android 路径规则、三种 stub 注入方式
- [symbol-visibility-control.md](symbol-visibility-control.md) — **符号可见性控制**：linker version script 机制、为何 dlsym 不可见
- [hap-resource-integration.md](hap-resource-integration.md) — **HAP 读取与资源集成**：已有的 HapParser + minizip、ReadRawFileFromHap、resources.index 流程
- [platform-abstraction-layer.md](platform-abstraction-layer.md) — 7 个平台抽象层: Asset, 路径, 窗口, 文件系统, 进程模型, 模块路由, 系统模块

### Stage 模型与编译流水线

- [stage-model-architecture.md](stage-model-architecture.md) — Stage 模型架构: 组件类型、生命周期、单引擎共享设计、进程/线程模型、Context 体系、Android 映射关系
- [ohos-build-pipeline.md](ohos-build-pipeline.md) — HAP 编译流水线: 源码→abc 过程、module.json5 srcEntry 格式、record 名构造规则、ArkCompiler 四子系统架构

### ABC 字节码与构建系统

- [abc-bytecode-format.md](abc-bytecode-format.md) — PANDA ABC 二进制格式、VM 加载执行流水线、es2panda 编译管线、NAPI 桥接原理、跨平台执行条件
- [arkui-x-build-system.md](arkui-x-build-system.md) — ArkUI-X GN 构建架构、仓库组织（直接复用 OHOS 核心代码）、平台动态发现机制、分层设计原则
- [test-hap-analysis.md](test-hap-analysis.md) — 测试 HAP 样本完整分析: 内部结构、3 个模块记录、10 个外部依赖分类（@ohos: / @native: / L-class）、复杂度评估

### 渲染

- [arkui-rendering-android.md](arkui-rendering-android.md) — Virtual Rosen Window, SurfaceView/Skia 渲染管线, UIContent 实现

### 构建产物分析

- [libarkui-android-contents.md](libarkui-android-contents.md) — libarkui_android.so 完整构建内容分析：依赖树、各层模块拆解、符号可见性、对 HAP-on-Android 的启示

### 验证与评估

- [information-gap-analysis.md](information-gap-analysis.md) — 信息充分性评估：8 项交叉验证结果、6 个信息缺口及影响等级、补充探索优先级
- [api-coverage.md](api-coverage.md) — **OHOS API 兼容性分析**：对照 OHOS SDK 619 个 API 声明文件，梳理 ArkUI-X 已支持的 152 个模块、缺失的 P0/P1/P2 级 API 及扩展建议

### 技术方案

- [technical-plan.md](technical-plan.md) — **完整技术方案与落地路线**: 总体策略、核心阻塞点分析（含 srcEntry→record 名映射）、代码修改方案、构建方案、6 阶段实施路线、风险缓解与替代方案

---

## 关键发现速览

1. **ArkUI-X 直接复用 OHOS 核心代码** — 不是 fork，而是指向 OHOS 固定 tag，arkcompiler/ace_engine 等核心组件代码完全相同，差异仅在 Android/iOS adapter 层
2. **阻塞点是入口路由，不是文件格式** — ABC 格式是跨平台标准 (PANDA)，.abc 文件能被 ArkVM 在 Android 上加载；问题在于 `GetOutEntryPoint` 加 bundleName 前缀导致 record 名不匹配
3. **测试 HAP 仅 10 个外部依赖** — 1 个已有 (hilog)、3 个内建 (@native:)、4 个需移植 (UIAbility 等)、2 个可延后 stub；已验证 modules.abc 仅 12KB，3 个 record
4. **OHOS HAP 路径含 src/main/** — `entry/src/main/ets/entryability/EntryAbility`，与 ArkUI-X 路径前缀不同，加剧 entryPoint 不匹配
5. **Stage 模型单引擎共享** — 多个 ability 共享一个 ArkTS 引擎实例，Android 端只需一个 EcmaVM，简化了集成
6. **需自己构建 .so** — `ExecuteModuleBuffer` 在 libarkui_android.so 中是 hidden 符号，dlsym 不可访问，必须基于 OHOS 源码 + ArkUI-X adapter 层自行编译

---

## Plan 方案说明

### Plan A: 直接调用现有 libarkui_android.so (已否决)

**思路**: 通过 `dlsym` 调用已导出的 `RunScriptForAbc` 或 `RunScriptBuffer`，将 OHOS HAP 的 modules.abc 传入 ArkUI-X 的运行时执行，无需自己构建 .so。

**失败原因** (详见 `arkui-x-script-loading.md` §10):
1. `RunScriptForAbc` 使用 `ExecuteTypes::NAPI`，跳过 `ParseAbcEntryPoint` 的所有路由逻辑
2. `ExecuteModuleBuffer`（正确的执行函数）是 **hidden** 符号，`dlsym` 不可见
3. `GetOutEntryPoint` 会添加 `bundleName` 前缀，导致 entryPoint 与 OHOS HAP 的 ABC record 名不匹配
4. `AdaptOldIsaRecord` 错误截断 OHOS 格式的 entryPoint

### Plan B (替代方案): 从 ETS 源码重编译为 ArkUI-X 格式 ABC

**思路**: 不放直接运行已编译的 HAP 包，而是获取 ETS 源码，用 ArkUI-X 的 es2abc 工具链重新编译成 ArkUI-X 兼容格式的 .abc，再打包到 ArkUI-X 构建产物中运行。

**优点**: 不需要修改运行时代码。

**缺点**: 需要 ETS 源码；本质上是"用 ArkUI-X 重组一个 App"而不是"直接运行已编译的 HAP"；与项目目标不符。

### 当前方案 (已实施): 基于 ArkUI-X 6.1-Release 构建自定义运行时

**思路**: 以 ArkUI-X 6.1-Release 的 Android 构建体系为基础，对运行时中模块路由、record 名解析、渲染管线初始化的关键路径做定向适配，使其兼容 OHOS HAP 格式。

**实施范围**: 5 个仓库，10 个 cherry-pick + 9 个 manual commit。详见 [ARKUI-X_PATCHES.md](../docs/ARKUI-X_PATCHES.md)。

核心解决：ABC record 名双维度适配（SDK 5.0/6.0）、模块名拼接一致性（SplicingModuleName）、RSUIDirector 创建（6.1 白屏修复）、多实例渲染（RSClient + MultiInstance）。
