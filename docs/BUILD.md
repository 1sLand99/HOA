# Building HOA

HOA 基于 ArkUI-X 构建体系，通过 repo + manifest 管理 8 个定制仓库。

构建流程：

- **一键构建：** `build_all.sh`（需设置 4 个环境变量，见下方）
- **分步构建：** `repo init` → `prebuilts_download.sh` → `build.sh` → `build_musl_bridge.sh` → `sync_arkui_x.sh` → `gradlew assembleDebug`

耗时数小时不等，磁盘需求约 100GB（源码 + 产物）。

---

> **前置参考**: 构建前建议阅读 [ArkUI-X 开发准备](https://gitcode.com/arkui-x/docs/blob/master/zh-cn/framework-dev/quick-start/start-overview.md)，了解基本概念和环境要求。

## 前提条件

### 系统依赖

```bash
sudo apt-get install -y \
    binutils git git-lfs git-core gnupg flex bison gperf \
    build-essential zip curl zlib1g-dev gcc-multilib g++-multilib \
    libc6-dev-i386 lib32ncurses5-dev x11proto-core-dev libx11-dev \
    lib32z-dev ccache libgl1-mesa-dev libxml2-utils xsltproc \
    unzip m4 python3 python3-pip
```

### repo

```bash
curl -s https://raw.gitcode.com/gitcode-dev/repo/raw/main/repo-py3 > /usr/local/bin/repo
chmod a+x /usr/local/bin/repo
```

### Java

```bash
apt install openjdk-17-jdk
export JAVA_HOME=/path/to/jdk
export PATH=${JAVA_HOME}/bin:${PATH}
```

### Android SDK

需要 NDK 21.3.6528147、SDK Platform 34、Build Tools 28.0.3+。

```bash
./sdkmanager --install "ndk;21.3.6528147" --sdk_root=/path/to/Android/Sdk
./sdkmanager --install "platforms;android-34" --sdk_root=/path/to/Android/Sdk
./sdkmanager --install "build-tools;28.0.3" --sdk_root=/path/to/Android/Sdk

export ANDROID_HOME=/path/to/Android/Sdk
export PATH=${ANDROID_HOME}/tools:${ANDROID_HOME}/tools/bin:${ANDROID_HOME}/build-tools/28.0.3:${ANDROID_HOME}/platform-tools:${PATH}
```

HOA 项目根目录创建 `local.properties`：

```
sdk.dir=/path/to/Android/Sdk
```

### musl bridge 额外依赖

构建 libb.so（musl ABI bridge）需要 NDK 28+ 的 aarch64 clang 工具链。libb.so 使 OHOS HAP 的闭源 .so 文件能在 Android bionic 上运行。

```bash
export ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358
export MUSL=/path/to/arkui-x/third_party/musl
export ARKUI_X_SRC=/path/to/arkui-x
export ARKUI_BUILD=$ARKUI_X_SRC/out/arkui-x/aosp_clang_arm64_release
```

> 这四个环境变量是 `build_all.sh` 的必需要求，缺一不可。详见[一键构建](#一键构建-build_allsh)。

---

## 一键构建 (`build_all.sh`)

`scripts/build_all.sh` 一次性完成全部 4 个步骤（ArkUI-X → libb.so → 同步 → APK）：

```bash
export ARKUI_X_SRC=/path/to/arkui-x
export ARKUI_BUILD=$ARKUI_X_SRC/out/arkui-x/aosp_clang_arm64_release
export ANDROID_NDK_HOME=/path/to/ndk/28.2.13676358
export MUSL=$ARKUI_X_SRC/third_party/musl

cd /path/to/HOA
./scripts/build_all.sh
```

支持跳过部分步骤：

```bash
./scripts/build_all.sh --skip-arkui   # 跳过 ArkUI-X 编译
./scripts/build_all.sh --skip-musl    # 跳过 libb.so 编译
```

以下为分步说明，供调试或增量构建参考。

---

## 分步构建

## Step 1: 下载源码

拉取 ArkUI-X 源码树，8 个定制仓库 override 到 `harmony-on-android` 组织的 fork。

```bash
mkdir -p ~/arkui-x-hoa
cd ~/arkui-x-hoa
repo init -u https://gitcode.com/harmony-on-android/manifest \
          -b hoa-weekly -m hoa-weekly.xml --no-repo-verify
repo sync -c -j4
repo forall -c 'git lfs pull'
```

> SSH 方式：`-u git@gitcode.com:harmony-on-android/manifest.git`

---

## Step 2: 下载预编译工具链

下载 Clang、GN、Ninja 等构建工具。

```bash
bash build/prebuilts_download.sh --build-arkuix --skip-ssl
```

---

## Step 3: 编译 ArkUI-X

编译 Android arm64 产物，输出到 `out/arkui-x/aosp_clang_arm64_release/`。

```bash
./build.sh --product-name arkui-x --target-os android
```

> 仅编译 arm64（跳过 arm 32-bit 和 x86_64 模拟器），节省约 60% 时间：
> ```bash
> ./build.sh --product-name arkui-x --target-os android --gn-args 'build_target_arch="arm64"'
> ```

---

## Step 4: 编译 libb.so（musl ABI bridge）

编译 musl 子集为 `libb.so`，使 OHOS HAP 的闭源 .so 能够通过 musl ABI 在 Android bionic 上运行。

```bash
cd /path/to/HOA/app/src/main/cpp
MUSL=$ARKUI_X_SRC/third_party/musl bash build_musl_bridge.sh
```

产物：`app/src/main/jniLibs/arm64-v8a/libb.so`

> libb.so 需要 NDK 28+ 的 aarch64 clang，与 APK 的 NDK 21 是两套独立工具链。
> `build_musl_bridge.sh` 会检查 `ANDROID_NDK_HOME` 和 `MUSL` 两个环境变量。

---

## Step 5: 同步产物到 HOA

将 ArkUI-X 构建的 .so、资源文件、jar 等复制到 HOA 项目对应目录。

```bash
cd /path/to/HOA
ARKUI_BUILD=~/arkui-x-hoa/out/arkui-x/aosp_clang_arm64_release ./scripts/sync_arkui_x.sh
```

增量构建（仅更新 .so）：

```bash
./scripts/sync_arkui_x.sh --so-only
```

---

## Step 6: 构建 APK

```bash
./gradlew assembleDebug
```

产物: `app/build/outputs/apk/debug/app-debug.apk` (~85MB)

---

## 常见工作流

### 完整重建

```bash
# 推荐：使用一键构建脚本
cd /path/to/HOA
ARKUI_X_SRC=/path/to/arkui-x \
ARKUI_BUILD=/path/to/arkui-x/out/arkui-x/aosp_clang_arm64_release \
ANDROID_NDK_HOME=/path/to/ndk \
MUSL=/path/to/arkui-x/third_party/musl \
./scripts/build_all.sh

# 或者分步：
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA/app/src/main/cpp
MUSL=~/arkui-x-hoa/third_party/musl bash build_musl_bridge.sh
cd /path/to/HOA
./scripts/sync_arkui_x.sh
./gradlew assembleDebug
```

### 修改了 ArkUI-X C++ 代码

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh --so-only
./gradlew assembleDebug
```

### 修改了 musl bridge 源码

```bash
cd /path/to/HOA/app/src/main/cpp
MUSL=~/arkui-x-hoa/third_party/musl bash build_musl_bridge.sh
cd /path/to/HOA
./gradlew assembleDebug
```

### 修改了 Java/Kotlin 代码

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 附录: HOA manifest

维护在 `harmony-on-android/manifest` 仓库的 `hoa-weekly` 分支：

```xml
<!-- hoa-weekly.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote fetch="https://gitcode.com/harmony-on-android" name="hoa" />
  <include name="default.xml" />
  <project path="arkcompiler/ets_runtime" name="arkcompiler_ets_runtime"
           remote="hoa" revision="hoa-weekly" />
  <project path="build" name="build"
           remote="hoa" revision="hoa-weekly" />
  <project path="build_plugins" name="build_plugins"
           remote="hoa" revision="hoa-weekly" />
  <project path="foundation/appframework" name="app_framework"
           remote="hoa" revision="hoa-weekly" />
  <project path="foundation/arkui/ace_engine/adapter/android"
           name="arkui_for_android" remote="hoa" revision="hoa-weekly" />
  <project path="foundation/arkui/napi" name="arkui_napi"
           remote="hoa" revision="hoa-weekly" />
  <project path="plugins" name="plugins"
           remote="hoa" revision="hoa-weekly" />
  <project path="third_party/musl" name="third_party_musl"
           remote="hoa" revision="hoa-weekly" />
</manifest>
```
