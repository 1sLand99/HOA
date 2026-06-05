# HOA — Harmony on Android

在 Android 设备上运行 OpenHarmony / HarmonyOS HAP 应用。

**HOA 是一个正在开发中的实验性项目**，目标是让 HarmonyOS 的 HAP 应用包无需修改即可在 Android 设备上运行。目前已完成核心运行时搭建，ArkTS 页面渲染和原生 .so 加载均已验证通过，但大量系统 API 尚未实现，兼容性有限。

## 支持到什么程度

| 能力 | 状态 | 说明 |
|------|------|------|
| ArkTS 页面渲染 | 支持 | SurfaceView + Skia，SDK 5.0/6.0 ABC 格式兼容 |
| HAP 安装与启动 | 支持 | ZIP 解析、流式解压、多进程槽位管理（5 槽） |
| resources.index 解析 | 支持 | V1（OHOS 5.0）和 V2（SDK 6.0）双版本 |
| 原生 .so 加载 | 支持 | 无 RUNPATH 的闭源 .so 拓扑依赖加载 + musl ABI bridge（libb.so） |
| C++ STL（libc++_shared） | 支持 | std::string/vector/map/thread/exception/RTTI 等全部通过 |
| HDC 协议 | 支持 | TCP 8710，支持 install/uninstall/shell/file send，DevEco Studio 可识别为设备 |
| 权限映射 | 部分 | 普通权限自动映射（INTERNET 等），危险权限需手动处理 |
| 生命周期 | 部分 | onCreate/onResume/onPause/onDestroy 已对接，部分中间状态未实现 |
| HDS 组件 | 部分 | 70+ 导出 mock，功能正确但视觉与原版差异较大 |
| 第三方 ohpm 包 | 不支持 | 如 @ohos.pulltorefresh、@ohos.promptAction 等尚未补齐 |
| 完整 Bundle Manager | 不支持 | 未实现，`bundleInfo_ is nullptr` 日志警告 |
| 后台能力 / Service | 不支持 | 未实现 |
| 分布式 / 多设备 | 不支持 | 未实现 |

## 已知限制

HOA 仍在早期开发阶段，以下为已知限制：

- **HAP 兼容性**：目前只有部分 HAP 能正常运行。依赖未实现系统 API 的 HAP 会白屏或崩溃。
- **性能**：ArkUI-X 渲染管线未经优化，复杂页面可能卡顿。
- **内存**：每个 HAP 启动一个独立进程，多 HAP 同时运行内存压力较大。
- **稳定性**：长时间运行可能出现资源泄漏，建议测试后重启 app。
- **安全性**：HDC daemon 无认证机制（默认 8710 端口），仅适合开发调试，不要在生产环境使用。

## 原理

HOA 基于 ArkUI-X 的 Android 构建体系，通过多个仓库的定向适配使运行时能够加载并执行 OHOS 原生格式的 HAP：

- **ArkTS 渲染**：ETS VM 执行 modules.abc 字节码，ACE 引擎将 UI 树渲染到 Android SurfaceView
- **原生 .so 支持**：通过 `libb.so`（musl ABI bridge）在 Android bionic 上运行 OHOS 闭源 .so；ELF patch 将 DT_NEEDED `libc.so` 替换为 `libb.so`；`elf_load_with_deps` 对无 RUNPATH 的 .so 做拓扑依赖加载
- **HDC 协议**：实现 OHOS HDC daemon，支持 DevEco Studio 设备识别与一键部署

```
┌─────────────────────────────────┐
│  HAP (entry.hap)                │
│  ├── module.json                │
│  ├── ets/modules.abc            │  ← OHOS 原生字节码
│  ├── libs/arm64-v8a/*.so        │  ← OHOS 闭源 .so（musl ABI）
│  ├── resources.index            │
│  └── resfile/                   │
└──────────┬──────────────────────┘
           │ HapInstaller 解压 + ELF patch (libc.so → libb.so)
           ▼
┌─────────────────────────────────┐
│  HOA Application                │
│  ├── StageApplication           │  ← ArkUI-X Android 适配器
│  ├── libarkui_android.so        │  ← 内嵌 ETS VM + ACE 渲染引擎
│  ├── libb.so (musl bridge)      │  ← musl ABI 桥接（pthread/stdio/dirent/signal）
│  └── OHOS HAP Mode Patches      │  ← 7 仓库定向适配
└──────────┬──────────────────────┘
           │
           ▼
┌─────────────────────────────────┐
│  Android SurfaceView            │
│  └── Hello World (ArkUI)        │
└─────────────────────────────────┘
```

关键机制：Java 层通过 `setOhosHapMode(true)` 设置环境变量，经 JNI 传入 ETS VM，在模块路由时激活 OHOS 兼容路径（自动适配 SDK 5.0/6.0 ABC record 名格式差异），使 ArkUI-X 能正确加载 OHOS 编译的 ABC 文件。

## 构建

> **首次构建前请务必阅读 [`docs/BUILD.md`](docs/BUILD.md)**，其中包含前提条件、工具链安装、源码下载、编译及常见问题的完整说明。

构建概览：

```
build_all.sh (ArkUI-X → libb.so → sync → APK)

或分步：
repo init (HOA manifest) → prebuilts_download.sh → build.sh → build_musl_bridge.sh → sync_arkui_x.sh → gradlew assembleDebug
```

构建耗时数小时不等，视机器性能和网络状况而定。磁盘需求约 100GB（源码 + 产物）。

## 运行

- **生产模式**：MainActivity → Install HAP（选择文件）→ 点击启动
- **开发测试**：`adb shell am start -n app.hackeris.hoa/.DevTestActivity --ez autoLaunch true`

要求：Android 11+，arm64-v8a 设备。

## 社区

- **获取 HAP**：可前往 [sydxky.cn](https://sydxky.cn/) 下载 HAP 应用包。由于 HOA 尚在开发中，大部分 HAP 暂无法正常运行，仅建议用于测试。
- **QQ 群**：欢迎加入交流群讨论。

<img src="media/qq_qrcode.png" alt="QQ群二维码" width="320">

## 相关文档

- `docs/BUILD.md` — 完整构建文档
- `docs/ARKUI-X_PATCHES.md` — ArkUI-X 源码修改说明
- `agents/PLAN.md` — 技术方案
- `agents/PROGRESS.md` — 项目进展
