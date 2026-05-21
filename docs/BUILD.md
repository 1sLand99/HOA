# Building HOA

## 概览

HOA (HarmonyOS On Android) 是一个在 Android 上运行 OHOS 原生 HAP 的运行时环境。它基于修改版 ArkUI-X 构建系统，将 6 个关键仓库替换为 `harmony-on-android` 组织的 fork 版本。

**完整构建流程:**

```
ArkUI-X 源码 (repo 管理, 100+ 仓库)
  + HOA fork 覆盖 (6 个仓库, hoa-weekly 分支)
    → ./build.sh → 编译产物
      → sync_arkui_x.sh → 复制到 HOA 项目
        → ./gradlew assembleDebug → APK
```

总耗时约 30 分钟（取决于机器性能），磁盘需求 ~100GB（源码 + 构建产物）。

---

## 前置条件

| 工具 | 用途 | 安装方法 |
|------|------|---------|
| **repo** | 管理多仓库源码树 | `curl https://storage.googleapis.com/git-repo-downloads/repo > /usr/local/bin/repo && chmod +x /usr/local/bin/repo` |
| **git** | 版本控制 | 系统包管理器 |
| **python3** | ArkUI-X 构建脚本 | 系统包管理器 |
| **Java 17** | Android APK 编译 | `apt install openjdk-17-jdk` |
| **Android SDK** | Gradle 构建 APK | Android Studio 或 `sdkmanager` |
| **ArkUI-X 构建环境** | GN/Ninja/Clang 等 | 由 ArkUI-X 的 `prebuilts_download.sh` 自动下载 |

**操作系统**: ArkUI-X 构建脚本在 Ubuntu 18.04/20.04/22.04/24.04 上经过验证。其他 Linux 发行版可能需要在 `build.sh` 中跳过 OS 版本检查。

**Android SDK 配置**: 确保项目根目录有 `local.properties` 指向 SDK 路径：

```
sdk.dir=/path/to/android/sdk
```

---

## Step 1: 下载 ArkUI-X 源码并应用 HOA 修改

### 自动方式（推荐）

```bash
cd /path/to/HOA
./scripts/setup_arkui_x.sh ~/arkui-x-hoa
```

该脚本自动完成：
1. `repo init` 获取 ArkUI-X 基础 manifest
2. 创建 `local_manifests/hoa.xml` 覆盖 6 个仓库
3. `repo sync` 拉取全部源码
4. `prebuilts_download.sh` 下载预编译工具链（Clang, GN, Ninja 等）

### 手动方式

```bash
# 1. repo init
mkdir -p ~/arkui-x-hoa
cd ~/arkui-x-hoa
repo init -u https://gitcode.com/arkui-x/manifest.git -b weekly

# 2. 创建 local_manifests/hoa.xml (内容见附录 A)
mkdir -p .repo/local_manifests
# 将附录 A 的 XML 写入 .repo/local_manifests/hoa.xml

# 3. repo sync
repo sync -j4

# 4. 下载预编译工具链
bash build/prebuilts_download.sh --skip-ssl
```

---

## Step 2: 构建 ArkUI-X

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
```

构建产物输出到 `out/arkui-x/aosp_clang_arm64_release/`。

> **注意**: 首次构建约需 30 分钟。后续增量构建只需 2-5 分钟。

---

## Step 3: 同步产物到 HOA 项目

```bash
cd /path/to/HOA
ARKUI_BUILD=~/arkui-x-hoa/out/arkui-x/aosp_clang_arm64_release ./scripts/sync_arkui_x.sh
```

该脚本复制以下产物：

| 类型 | 源路径 | 目标路径 |
|------|--------|---------|
| Native 库 (.so) | `arkui/`, `plugins/` | `app/src/main/jniLibs/arm64-v8a/` |
| 系统资源 .abc | `arkui/ace_engine_cross/*.abc` | `app/src/main/assets/sys/systemres/abc/` |
| 资源文件 | `obj/interface/sdk/systemres/` | `app/src/main/assets/sys/systemres/` |
| 字体 | `obj/interface/sdk/systemres_fonts/` | `app/src/main/assets/sys/systemres/fonts/` |
| ICU 数据 | `icu_data/out/icudt74l.dat` | `app/src/main/assets/sys/systemres/icudt72l.dat` |
| arkui_android_adapter.jar | `arkui_android_adapter.jar` | `app/libs/` |
| stub.an | `gen/arkcompiler/ets_runtime/stub.an` | `app/src/main/assets/sys/stub/arm64-v8a/` |

**增量构建时**可使用 `--so-only` 仅更新 .so 文件：

```bash
./scripts/sync_arkui_x.sh --so-only
```

---

## Step 4: 构建 APK

```bash
cd /path/to/HOA
./gradlew assembleDebug
```

产物: `app/build/outputs/apk/debug/app-debug.apk` (~85MB)

> `libarkui_android.so` 占 APK 体积的绝大部分 (~82MB)，内含 ArkUI-X 引擎、ETS VM、编译器、Skia 等全部 native 组件。

---

## 常见工作流

### 日常开发（修改了 Java/Kotlin 代码）

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 修改了 ArkUI-X C++ 代码

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh --so-only
./gradlew assembleDebug
```

### 完整重建

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh
./gradlew assembleDebug
```

---

## HOA 修改版仓库

以下 6 个仓库被替换为 [harmony-on-android](https://gitcode.com/harmony-on-android) 组织的 fork：

| 仓库 | 路径 | 修改内容 |
|------|------|---------|
| **arkcompiler/ets_runtime** | `arkcompiler/ets_runtime` | OHOS HAP record 名适配：`isOhosHapMode` 标志位、`GetOutEntryPoint` 双维度路径修复、`AdaptOldIsaRecord` 跳过、`ParseAbcPathAndOhmUrl` 分支路由 |
| **build** | `build` | GN 构建模板适配 Android NDK：`external_deps` 清空、`arm64e` 双架构跳过、PAC 分支保护移除 |
| **app_framework** | `foundation/appframework` | `OHOS_HAP_MODE` 环境变量读取注入 VM 标志位、`hapPath` 前缀适配 |
| **arkui_for_android** | `foundation/arkui/ace_engine/adapter/android` | JNI setenv 桥接、StageApplication Java API、`resources.index` 路径修复、系统资源目录重命名为 `sys` |
| **arkui_napi** | `foundation/arkui/napi` | 模块管理器系统资源路径标记从 `arkui-x` 改为 `sys` |
| **plugins** | `plugins` | WebView 等插件资源路径从 `arkui-x` 改为 `sys` |

全部修改在 `hoa-weekly` 分支上（基于 `weekly_20260518`）。详细说明见 `docs/ARKUI-X_PATCHES.md`。

---

## 标志传递链

OHOS HAP 模式通过以下链路从 Java 层传递到 C++ VM：

```
HoaApplication.kt
  → StageApplication.setOhosHapMode(true)       [arkui_for_android]
    → JNI nativeSetOhosHapMode                  [arkui_for_android]
      → setenv("OHOS_HAP_MODE", "1")            [arkui_for_android]
        → js_runtime.cpp 读取环境变量           [app_framework]
          → JSNApi::SetOhosHapMode(vm_, true)   [arkcompiler/ets_runtime]
            → IsOhosHapMode() 分支判断          [arkcompiler/ets_runtime]
```

---

## Project structure

```
HOA/
├── scripts/
│   ├── setup_arkui_x.sh           # ArkUI-X 源码设置脚本
│   └── sync_arkui_x.sh            # ArkUI-X 产物同步脚本
├── app/
│   ├── src/main/jniLibs/arm64-v8a/ # Native 库 (来自 ArkUI-X 构建)
│   ├── src/main/assets/sys/       # 系统资源 (来自 ArkUI-X 构建)
│   ├── src/main/java/app/hackeris/hoa/  # Android 应用源码
│   └── build.gradle.kts
├── docs/
│   ├── BUILD.md                   # 构建文档 (本文件)
│   └── ARKUI-X_PATCHES.md         # ArkUI-X 源码修改详细说明
└── agents/                        # 技术调研文档
```

---

## Troubleshooting

### repo init 失败

确认网络可访问 `gitcode.com`。如果在受限环境中，可能需要配置代理：

```bash
export HTTP_PROXY=http://proxy:port
export HTTPS_PROXY=http://proxy:port
```

### build.sh 报 "OS version not supported"

ArkUI-X 构建脚本内置了 OS 版本检查。如果系统是较新版本，在 `build.sh` 中找到 OS 版本检查逻辑并添加对应版本号。

### APK 太大

`libarkui_android.so` (~82MB) 是体积最大的单文件。这无法避免，因为它内含完整的 ArkUI-X 引擎、ETS VM、Skia 等。如果只需调试非 UI 功能，可考虑精简。

### 构建失败，提示 .so 缺失

运行 `./scripts/sync_arkui_x.sh` 确保所有 ArkUI-X 产物已同步到项目中。

### resources.index 不匹配

如果 HAP 内 `$r()` 引用资源失败，检查：
1. `HapInstaller` 是否正确写入 `files/hap/{bundleName}.{moduleName}/resources.index`
2. `StageAssetProvider::Preload()` 是否从 `files/hap/` 正确复制到 `files/sys/`

---

## 附录 A: local_manifests/hoa.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
    <remote fetch="https://gitcode.com/harmony-on-android" name="hoa" />

    <!-- ArkCompiler ETS 运行时: OHOS HAP record 名适配 -->
    <project path="arkcompiler/ets_runtime" name="arkcompiler_ets_runtime" remote="hoa" revision="hoa-weekly" />

    <!-- 构建系统: GN 模板 Android NDK 适配 -->
    <project path="build" name="build" remote="hoa" revision="hoa-weekly" />

    <!-- 应用框架: VM 标志位注入 -->
    <project path="foundation/appframework" name="app_framework" remote="hoa" revision="hoa-weekly" />

    <!-- Android 适配器: JNI 桥接 + 资源路径修复 -->
    <project path="foundation/arkui/ace_engine/adapter/android" name="arkui_for_android" remote="hoa" revision="hoa-weekly" />

    <!-- ArkUI NAPI: 路径标记适配 -->
    <project path="foundation/arkui/napi" name="arkui_napi" remote="hoa" revision="hoa-weekly" />

    <!-- 插件系统: 资源路径适配 -->
    <project path="plugins" name="plugins" remote="hoa" revision="hoa-weekly" />
</manifest>
```
