# HOA — Harmony on Android

在 Android 设备上运行 OpenHarmony HAP 应用。

## 原理

HOA 基于 ArkUI-X weekly_20260518 的 Android 构建体系，通过 6 个仓库的定向适配使运行时能够加载并执行 OHOS 原生格式的 HAP，将 ArkTS 页面渲染到 Android SurfaceView 上。

```
┌─────────────────────────────────┐
│  HAP (entry.hap)                │
│  ├── module.json                │
│  ├── ets/modules.abc            │  ← OHOS 原生字节码
│  ├── resources.index            │
│  └── resfile/                   │
└──────────┬──────────────────────┘
           │ HapInstaller 解压
           ▼
┌─────────────────────────────────┐
│  HOA Application                │
│  ├── StageApplication           │  ← ArkUI-X weekly_20260518 Android 适配器
│  ├── libarkui_android.so        │  ← 内嵌 ETS VM + ACE 渲染引擎
│  └── OHOS HAP Mode Patches      │  ← 6 仓库定向适配
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
repo init (HOA manifest) → prebuilts_download.sh → build.sh → sync_arkui_x.sh → gradlew assembleDebug
```

构建耗时数小时不等，视机器性能和网络状况而定。磁盘需求约 100GB（源码 + 产物）。

## 运行

- **生产模式**：MainActivity → Install HAP（选择文件）→ 点击启动
- **开发测试**：`adb shell am start -n app.hackeris.hoa/.DevTestActivity --ez autoLaunch true`

要求：Android 8.0+，arm64-v8a 设备。

## 当前状态

ArkUI-X weekly_20260518 移植完成。5 个已安装 HAP 中 4 个正常渲染，WebView HAP 资源加载和 HAP 大文件安装 OOM 已修复，支持安装/预览/启动/卸载全流程。详见 `agents/PROGRESS.md`。

## 相关文档

- `docs/BUILD.md` — 完整构建文档
- `docs/ARKUI-X_PATCHES.md` — ArkUI-X 源码修改说明
- `agents/PLAN.md` — 技术方案
- `agents/PROGRESS.md` — 项目进展
