# HOA — Harmony on Android

在 Android 设备上运行 OpenHarmony HAP 应用。

## 原理

HOA 基于 ArkUI-X 的 Android 构建体系，通过 4 个定向 Patch 使运行时能够识别 OHOS 原生格式的 ABC 字节码，将 HAP 中的 ArkTS 页面渲染到 Android SurfaceView 上。

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
│  ├── StageApplication           │  ← ArkUI-X Android 适配器
│  ├── libarkui_android.so        │  ← 内嵌 ETS VM + ACE 渲染引擎
│  └── OHOS HAP Mode Patches      │  ← ABC record 名匹配修复
└──────────┬──────────────────────┘
           │
           ▼
┌─────────────────────────────────┐
│  Android SurfaceView            │
│  └── Hello World (ArkUI)        │
└─────────────────────────────────┘
```

关键机制：Java 层通过 `setOhosHapMode(true)` 设置环境变量，经 JNI 传入 ETS VM，在模块路由时激活 OHOS 兼容路径（插入 `src/main/`、用 `&` 包裹 record 名），使 ArkUI-X 能正确加载 OHOS 编译的 ABC 文件。

## 构建

```bash
cd /data/share/hoa2/arkui-x
./build.sh --product-name arkui-x --target-os android

cd /path/to/HOA
./scripts/sync_arkui_x.sh
./gradlew assembleDebug
```

详见 `docs/BUILD.md`。

## 运行

- **生产模式**：MainActivity → Install HAP（选择文件）→ 点击启动
- **开发测试**：`adb shell am start -n app.hackeris.hoa/.DevTestActivity --ez autoLaunch true`

要求：Android 8.0+，arm64-v8a 设备。

## 当前状态

OHOS HAP "Hello World" 已在 Android 设备上端到端渲染成功

## 相关文档

- `docs/BUILD.md` — 完整构建文档
- `docs/ARKUI-X_PATCHES.md` — ArkUI-X 源码修改说明
- `agents/PLAN.md` — 技术方案
- `agents/PROGRESS.md` — 项目进展
