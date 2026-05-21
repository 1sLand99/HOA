#!/bin/bash
# setup_arkui_x.sh — 下载 ArkUI-X 源码并切换到 HOA 修改版分支
#
# 用法:
#   ./scripts/setup_arkui_x.sh <目标目录>
#
# 示例:
#   ./scripts/setup_arkui_x.sh ~/arkui-x-hoa
#
# 前置条件:
#   - repo 工具 (https://gerrit.googlesource.com/git-repo)
#   - python3
#   - git
#   - 磁盘空间 ~100GB (源码 + 构建产物)
#
# 完成后，进入目标目录运行:
#   ./build.sh --product-name arkui-x --target-os android

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ============================================================
# 参数解析
# ============================================================
if [ $# -lt 1 ]; then
    echo "用法: $0 <目标目录>"
    echo ""
    echo "示例: $0 ~/arkui-x-hoa"
    echo ""
    echo "将在目标目录下创建完整的 ArkUI-X 源码树（含 HOA 修改）。"
    exit 1
fi

TARGET_DIR="$1"
MANIFEST_URL="https://gitcode.com/arkui-x/manifest.git"
MANIFEST_BRANCH="weekly"

# ============================================================
# 前置条件检查
# ============================================================
log()    { echo "[$(date +%H:%M:%S)] $*"; }
err()    { echo "[$(date +%H:%M:%S)] ERROR: $*" >&2; }
abort()  { err "$*"; exit 1; }

command -v repo   >/dev/null 2>&1 || abort "repo 工具未安装。安装方法: https://gerrit.googlesource.com/git-repo"
command -v git    >/dev/null 2>&1 || abort "git 未安装"
command -v python3 >/dev/null 2>&1 || abort "python3 未安装"

mkdir -p "$TARGET_DIR" || abort "无法创建目录: $TARGET_DIR"

# ============================================================
# Step 1: repo init
# ============================================================
log "========================================="
log "HOA ArkUI-X 源码设置"
log "目标: $TARGET_DIR"
log "========================================="

cd "$TARGET_DIR"

if [ -d ".repo" ]; then
    log ".repo 已存在，跳过 repo init"
else
    log "Step 1/4: repo init..."
    repo init -u "$MANIFEST_URL" -b "$MANIFEST_BRANCH" --no-repo-verify
    log "repo init 完成"
fi

# ============================================================
# Step 2: 创建 local_manifests/hoa.xml
# ============================================================
log "Step 2/4: 创建 local_manifests/hoa.xml..."

mkdir -p .repo/local_manifests

cat > .repo/local_manifests/hoa.xml << 'MANIFEST'
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
    <!--
    HOA 项目修改版仓库清单。
    以下 6 个仓库使用 harmony-on-android 组织的 fork，
    hoa-weekly 分支包含基于 ArkUI-X weekly_20260518 的 OHOS HAP 在
    Android 上运行所需的全部修改。
    -->

    <remote fetch="https://gitcode.com/harmony-on-android" name="hoa" />

    <!-- ArkCompiler ETS 运行时: OHOS HAP record 名适配 (isOhosHapMode 标志位, GetOutEntryPoint 等) -->
    <project path="arkcompiler/ets_runtime" name="arkcompiler_ets_runtime" remote="hoa" revision="hoa-weekly" />

    <!-- 构建系统: GN 模板适配 Android NDK (external_deps 清空, arm64e 跳过, PAC 移除) -->
    <project path="build" name="build" remote="hoa" revision="hoa-weekly" />

    <!-- 应用框架: OHOS_HAP_MODE 环境变量读取, hapPath 前缀适配 -->
    <project path="foundation/appframework" name="app_framework" remote="hoa" revision="hoa-weekly" />

    <!-- Android 适配器: JNI setenv 桥接, StageApplication Java API, resources.index 路径修复, sys 目录重命名 -->
    <project path="foundation/arkui/ace_engine/adapter/android" name="arkui_for_android" remote="hoa" revision="hoa-weekly" />

    <!-- ArkUI NAPI 模块管理: sys 路径标记适配 -->
    <project path="foundation/arkui/napi" name="arkui_napi" remote="hoa" revision="hoa-weekly" />

    <!-- 插件系统: WebView 等资源路径 sys 适配, hilog 插件 -->
    <project path="plugins" name="plugins" remote="hoa" revision="hoa-weekly" />
</manifest>
MANIFEST

log "local_manifests/hoa.xml 创建完成"

# ============================================================
# Step 3: repo sync
# ============================================================
log "Step 3/4: repo sync (可能需要较长时间)..."
repo sync -j4 --no-clone-bundle
log "repo sync 完成"

# ============================================================
# Step 4: 下载预编译工具链
# ============================================================
log "Step 4/4: 下载预编译工具链..."
if [ -f "build/prebuilts_download.sh" ]; then
    bash build/prebuilts_download.sh --skip-ssl
    log "预编译工具链下载完成"
else
    err "build/prebuilts_download.sh 未找到，请手动下载预编译工具链"
fi

# ============================================================
# 完成
# ============================================================
log ""
log "========================================="
log "ArkUI-X 源码设置完成!"
log ""
log "下一步:"
log "  cd $TARGET_DIR"
log "  ./build.sh --product-name arkui-x --target-os android"
log ""
log "构建完成后, 在 HOA 项目目录运行:"
log "  cd $(dirname "$(dirname "$SCRIPT_DIR")")"
log "  ./scripts/sync_arkui_x.sh"
log "  ./gradlew assembleDebug"
log "========================================="
