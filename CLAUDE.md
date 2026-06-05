# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: HOA — Harmony on Android

在 Android 设备上运行 OpenHarmony/HarmonyOS HAP 应用。通过 ArkUI-X 的 Android 构建体系 + musl ABI bridge (libb.so) 使 OHOS 闭源 .so 能在 Android bionic 上运行。

---

## Architecture

```
HAP (.hap, ZIP format)
  ├── module.json          → 应用元数据
  ├── ets/modules.abc      → OHOS 原生 ArkTS 字节码 (ETS VM 执行)
  ├── libs/arm64-v8a/*.so  → OHOS 闭源 .so (musl ABI, DT_NEEDED libc.so)
  ├── resources.index      → 资源索引 (V1/V2 双版本)
  └── resfile/             → 实际资源文件
         │
         ▼ HapInstaller: ZIP解压 → ELF patch (libc.so→libb.so) → 拓扑依赖加载
         │
┌─────────────────────────────────────────────┐
│ HOA Application (Android)                   │
│  ├── StageApplication       ArkUI-X 适配器  │
│  ├── libarkui_android.so    内嵌 ETS VM     │
│  ├── libb.so               musl ABI bridge  │  ← 关键组件
│  └── libohaudio.so         音频 stub (mock) │
└──────────────┬──────────────────────────────┘
               │ setOhosHapMode(true)
               ▼
┌─────────────────────────────────────────────┐
│ Android SurfaceView                         │
└─────────────────────────────────────────────┘
```

### 核心流程

1. **HAP 安装**: `HapBundleLoader` 解析 module.json → `HapInstaller` 流式解压到 `filesDir/hap/<bundleName>.<moduleName>/`
2. **ELF 补丁**: 遍历 .so 的 `.dynstr` section，将 `libc.so` (8字节) 覆写为 `libb.so`
3. **进程槽位**: 10 个独立进程 (`:hap0`–`:hap9`)，通过 `HoaAbilityActivity0`–`HoaAbilityActivity9` 管理
4. **启动**: `HoaApplication.onCreate()` → 加载 `libb.so` → 加载 `libarkui_android.so` → HMS stub libs → `setOhosHapMode(true)`

### 源码组成 (157 个预编译 .so + 4 个构建产物)

| 来源 | 数量 | 说明 |
|------|------|------|
| ArkUI-X 构建 (`sync_arkui_x.sh`) | ~153 | libarkui_android.so + ACE 引擎 + plugins + third-party |
| libb.so (`build_musl_bridge.sh`) | 1 | musl ABI bridge，NDK 28+ 独立构建 |
| APK CMake (`CMakeLists.txt`) | 3 | libace_napi.z.so, libhilog_ndk.z.so, libohaudio.so (NDK 21) |

所有 .so 置于 `app/src/main/jniLibs/arm64-v8a/`，打包进 APK。

---

## libb.so — Musl ABI Bridge (核心)

**作用**: 让 OHOS 闭源 .so (musl 编译) 在 Android bionic 上运行。

**SONAME**: `libb.so` (精确 8 字节，通过 ELF .dynstr hot-patch 替换 `libc.so`)

**机制**:
1. `elf_patch.c`: 遍历已加载 ELF 的 .dynstr section，将 "libc.so\0" (正好 8 字节) 覆写为 "libb.so\0"。随后所有 `DT_NEEDED libc.so` 的符号解析都指向 libb.so。
2. `elf_patch_jni.c`: 拓扑依赖加载 — 对无 RUNPATH 的 .so 按依赖顺序加载。
3. `-Wl,-Bsymbolic`: libb.so 内部符号引用优先绑定自身定义。
4. `libb.map`: 版本脚本控制导出符号，`local: *;` 隐藏未列出的符号。

**桥接的实现方式**:
- **musl 源码编译**: pthread/stdio/dirent/signal/setjmp 等从 musl 源码直接编译进 libb.so，提供完整 musl ABI 实现。
- **bridge .c 文件**: `pthread_bridge.c` (线程创建/pthread_self), `malloc_bridge.c` (分配器 TLS 交换), `clone_bridge.c` (clone 包装), `signal_bridge.c`, `musl_stubs.c`。
- **stub .c 文件**: `ohaudio_stub.c` (libohaudio.so), `hilog_stub.c` (libhilog_ndk.z.so) — 提供 OHOS 特有 API 的空实现或 fallback。

### malloc_bridge.c — 分配器桥接 (最关键)

**问题**: musl 线程的 `tpidr_el0` 指向 musl TLS，Android scudo 分配器把 per-thread cache 存在 `tpidr_el0[6]` (TLS_SLOT_SANITIZER)。直接调用 bionic malloc 会覆写 musl TLS 导致崩溃。

**解决方案**: 每次进入 bionic 分配器前切换到 per-thread bionic TLS block。

```
malloc/free 入口
  → enter_bionic_alloc(&saved)
    → 检查 tpidr_el0: 如果是 main bionic thread → 跳过
    → 保存当前 musl TP → get_bionic_tp() → msr tpidr_el0, bionic_tp
  → real_malloc/real_free (bionic, resolved via dlsym(RTLD_NEXT))
  → leave_bionic_alloc(saved)
    → msr tpidr_el0, saved (恢复 musl TP)
```

**关键约束**:
- `real_malloc`/`real_free` 等通过 `dlsym(RTLD_NEXT, ...)` 解析，避免 PLT 递归（libb.so 自身导出 malloc/free）。
- Per-thread bionic TLS block 用 `real_calloc(1, 64)` 分配，零初始化。
- TLS table 用 futex-based spinlock 保护。
- 主线程的 bionic_tp 在 constructor 中捕获 (`mrs tpidr_el0`)，用于 bootstrap 新 TLS block 分配。

---

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/cpp/malloc_bridge.c` | 分配器桥接 + TLS 交换 |
| `app/src/main/cpp/pthread_bridge.c` | pthread 创建/self/errno 桥接 |
| `app/src/main/cpp/clone_bridge.c` | clone syscall 包装 |
| `app/src/main/cpp/elf_patch.c` | ELF .dynstr hot-patch (libc.so→libb.so) |
| `app/src/main/cpp/elf_patch_jni.c` | JNI 接口 + 拓扑依赖加载 |
| `app/src/main/cpp/signal_bridge.c` | sigaction ABI 转换 (musl 40-byte → kernel 32-byte) |
| `app/src/main/cpp/libb.map` | 版本脚本，控制导出符号 |
| `app/src/main/cpp/Makefile.musl_bridge` | libb.so 构建 (NDK 28+, 独立于 APK 的 NDK 21) |
| `app/src/main/cpp/build_musl_bridge.sh` | libb.so 构建 wrapper |
| `app/src/main/cpp/ohaudio_stub.c` | libohaudio.so stub |
| `app/src/main/cpp/hilog_stub.c` | libhilog_ndk.z.so stub |
| `app/CMakeLists.txt` | APK CMake: 3 个 stub .so (NDK 21) |
| `app/build.gradle.kts` | APK 构建配置 (版本号、依赖、NDK 配置) |
| `scripts/build_all.sh` | 一键构建 (ArkUI-X→libb.so→sync→APK) |
| `scripts/sync_arkui_x.sh` | 从 ArkUI-X 产物同步到 HOA |
| `scripts/setup_arkui_x.sh` | 首次 ArkUI-X 源码下载 (repo init/sync) |
| `manifests/hoa-weekly.xml` | repo manifest: 8 个定制仓库 override |

---

## Build

### 环境变量

完整构建需设置 4 个环境变量（`build_all.sh` 强制检查）：

```bash
export ARKUI_X_SRC=/path/to/arkui-x
export ARKUI_BUILD=$ARKUI_X_SRC/out/arkui-x/aosp_clang_arm64_release
export ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358
export MUSL=$ARKUI_X_SRC/third_party/musl
```

### 工具链

| 组件 | NDK | 说明 |
|------|-----|------|
| libb.so | NDK 28+ (`$ANDROID_NDK_HOME`) | aarch64-linux-android26-clang, `-nostdlib` |
| APK stub .so | NDK 21.3.6528147 (`$ANDROID_HOME/ndk/21.3.6528147`) | CMake, `c++_shared` |
| ArkUI-X 本体 | NDK 28+ (ArkUI-X 自带 toolchain) | gn + ninja |

> **警告**: 两套 NDK 工具链独立，不可混用。修改 libb.so 后只需重编译 libb.so + APK，不需要重编译 ArkUI-X。

### 常用增量构建工作流

```bash
# 仅 libb.so (修改了 bridge 代码)
cd app/src/main/cpp
MUSL=/data/share/hoa2/arkui-x/third_party/musl bash build_musl_bridge.sh

# 仅 APK (修改了 Java/Kotlin 或 libb.so)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 修改了 ArkUI-X C++ 代码
cd $ARKUI_X_SRC && ./build.sh --product-name arkui-x --target-os android
cd /src/HOA && ./scripts/sync_arkui_x.sh --so-only && ./gradlew assembleDebug

# 同步时可选择性复制
./scripts/sync_arkui_x.sh --so-only     # 仅 .so 文件
./scripts/sync_arkui_x.sh --abc-only    # 仅 systemres .abc
./scripts/sync_arkui_x.sh --res-only    # 仅 JAR/fonts/ICU
./scripts/sync_arkui_x.sh --dry-run     # 预览不复制

# 一键构建 (支持跳过步骤)
./scripts/build_all.sh                  # 全量
./scripts/build_all.sh --skip-arkui     # 跳过 ArkUI-X 编译
./scripts/build_all.sh --skip-musl      # 跳过 libb.so 编译
```

### 首次搭建

```bash
# 1. 下载 ArkUI-X 源码
./scripts/setup_arkui_x.sh

# 2. 下载预编译工具链
cd $ARKUI_X_SRC && bash build/prebuilts_download.sh --build-arkuix --skip-ssl

# 3. 一键构建
./scripts/build_all.sh
```

---

## Testing

### 运行单元测试

```bash
# 全部单元测试
./gradlew testDebugUnitTest

# 单个测试类
./gradlew testDebugUnitTest --tests "app.hackeris.hoa.hap.HapBundleLoaderTest"

# 单个测试方法
./gradlew testDebugUnitTest --tests "app.hackeris.hoa.hap.HapBundleLoaderTest.parse_moduleConfig"
```

### DevTestActivity — 快速验证 HAP

```bash
# 安装并启动 HAP
adb shell am force-stop app.hackeris.hoa
adb shell am start -n app.hackeris.hoa/.DevTestActivity \
    -e installHapPath /data/local/tmp/zbox.hap \
    --ez autoLaunch true

# 查看日志
adb logcat -d | grep -iE "(HOA|DevTest|DOSBox|SIGABRT|SIGSEGV|malloc_bridge)"
```

---

## Version Scheme

版本号在 `app/build.gradle.kts` 中定义：

```
versionName = YY.M.D.B    (年.月.日.当日构建次数)
versionCode = YYMMDDBB    (年年月月日日次次，共 9 位)

示例: 26.6.5.1 → versionCode 260605001 (2026-06-05 第 1 次构建)
```

---

## HDC (HarmonyOS Device Connector)

HOA 实现了 OHOS HDC daemon（TCP 8710 端口），使 DevEco Studio 可将 HOA 设备识别为 OHOS 设备并一键部署 HAP。

| 文件 | 职责 |
|------|------|
| `HdcDaemon.kt` | TCP server，默认端口 8710 |
| `HdcService.kt` | Android foreground service |
| `HdcProtocol.kt` | HDC 协议实现 |
| `HdcSession.kt` | Per-session 状态管理 |
| `HdcShellHandler.kt` | Shell 命令处理 |
| `HdcFileHandler.kt` | 文件传输 |
| `HdcInstallHandler.kt` | HAP 安装/卸载 |

**注意**: HDC daemon 无认证机制，仅适合开发调试，不要在生产环境启用。

---

## 已知问题

1. **DOSBox-X (zbox) 非确定性崩溃**: zbox 自身 Config/Section_prop/Value 存在堆缓冲区溢出，非 HOA bug。当前版本可正常运行。
2. **Scudo 兼容性**: 不同 Android 版本/厂商的 scudo 版本差异可能导致 TLS slot 初始化行为不同。
3. **dlsym(RTLD_NEXT) 厂商差异**: 部分厂商 dynamic linker 对 RTLD_NEXT 实现不同。
4. **内存压力**: 每个 HAP 启动一个独立进程，多 HAP 同时运行内存压力较大。

---

## Git 仓库

- **HOA**: `dev` 分支，remote origin
- **ArkUI-X**: `/data/share/hoa2/arkui-x/`，remote `openharmony`
- **ArkUI-X manifest**: `harmony-on-android/manifest`，branch `hoa-weekly` — 管理 8 个定制仓库 (ets_runtime, build, appframework, ace_engine/adapter/android, arkui/napi, plugins, musl, build_plugins)

---

## 相关文档

- `README.md` — 项目概述、支持能力、已知限制
- `docs/BUILD.md` — 完整构建文档 (前提条件、分步构建、常见工作流)
- `docs/ARKUI-X_PATCHES.md` — ArkUI-X 源码修改说明
- `agents/PROGRESS.md` — 项目进展 (已验证能力清单)
- `agents/PLAN.md` — 技术方案
