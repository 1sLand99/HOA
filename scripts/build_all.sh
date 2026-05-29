#!/bin/bash
# build_all.sh — 完整构建 HOA（ArkUI-X → libb.so → sync → APK）
#
# 用法:
#   ./scripts/build_all.sh                # 完整构建
#   ./scripts/build_all.sh --skip-arkui   # 跳过 ArkUI-X 编译
#   ./scripts/build_all.sh --skip-musl    # 跳过 libb.so 编译
#
# 环境变量（必须设置，不带默认值）:
#   ARKUI_X_SRC        ArkUI-X 源码根目录
#   ANDROID_NDK_HOME   NDK 路径
#   MUSL               musl 源码目录
#   ARKUI_BUILD        ArkUI-X 产物目录（$ARKUI_X_SRC/out/.../aosp_clang_arm64_release）
#
# 前置:
#   1. ./scripts/setup_arkui_x.sh 已完成，ArkUI-X 源码就位
#   2. ArkUI-X 的 build/prebuilts_download.sh 已执行

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── 选项 ────────────────────────────────────────────────────────
SKIP_ARKUI=false
SKIP_MUSL=false

for arg in "$@"; do
    case "$arg" in
        --skip-arkui) SKIP_ARKUI=true ;;
        --skip-musl)  SKIP_MUSL=true  ;;
        --help|-h)
            sed -n '2,17p' "$0"
            exit 0
            ;;
        *) echo "未知选项: $arg" >&2; exit 1 ;;
    esac
done

# ── 路径（均来自环境变量，不设默认值）──────────────────────────
missing_vars=()
for var in ARKUI_X_SRC ARKUI_BUILD MUSL ANDROID_NDK_HOME; do
    if [ -z "${!var:-}" ]; then
        missing_vars+=("$var")
    fi
done
if [ ${#missing_vars[@]} -gt 0 ]; then
    echo "错误: 以下环境变量未设置:" >&2
    for var in "${missing_vars[@]}"; do
        echo "  $var" >&2
    done
    echo "" >&2
    echo "请设置后再运行，例如:" >&2
    echo "  export ARKUI_X_SRC=/path/to/arkui-x" >&2
    echo "  export ARKUI_BUILD=/path/to/arkui-x/out/.../aosp_clang_arm64_release" >&2
    echo "  export MUSL=/path/to/musl" >&2
    echo "  export ANDROID_NDK_HOME=/path/to/ndk" >&2
    exit 1
fi
ARKUI_X_SRC="${ARKUI_X_SRC}"
ARKUI_BUILD="${ARKUI_BUILD}"
MUSL="${MUSL}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME}"

JNILIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"

# ── 日志 ────────────────────────────────────────────────────────
log()    { echo "[$(date +%H:%M:%S)] $*"; }
die()    { echo "[$(date +%H:%M:%S)] ERROR: $*" >&2; exit 1; }

check_dir() {
    [ -d "$1" ] || die "$2 不存在: $1"
}

# ── Step 1: 编译 ArkUI-X ────────────────────────────────────────
build_arkui_x() {
    log "==== Step 1/4: 编译 ArkUI-X ===="
    check_dir "$ARKUI_X_SRC" "ARKUI_X_SRC"
    cd "$ARKUI_X_SRC"
    ./build.sh --product-name arkui-x --target-os android \
        || die "ArkUI-X 编译失败"
    log "ArkUI-X 编译完成"
}

# ── Step 2: 编译 libb.so ────────────────────────────────────────
build_libb() {
    log "==== Step 2/4: 编译 libb.so (musl ABI bridge) ===="
    check_dir "$MUSL" "MUSL"
    cd "$PROJECT_ROOT/app/src/main/cpp"
    MUSL="$MUSL" bash build_musl_bridge.sh \
        || die "libb.so 编译失败"
    log "libb.so 编译完成"
}

# ── Step 3: 同步产物 ────────────────────────────────────────────
sync_artifacts() {
    log "==== Step 3/4: 同步 ArkUI-X 产物到 HOA ===="
    check_dir "$ARKUI_BUILD" "ARKUI_BUILD"
    cd "$PROJECT_ROOT"
    ARKUI_BUILD="$ARKUI_BUILD" bash "$SCRIPT_DIR/sync_arkui_x.sh" \
        || die "产物同步失败"
    log "产物同步完成"
}

# ── Step 4: 编译 APK ────────────────────────────────────────────
build_apk() {
    log "==== Step 4/4: 编译 APK ===="
    cd "$PROJECT_ROOT"
    ./gradlew assembleDebug || die "APK 编译失败"
    log "APK 编译完成"
    log "产物: $PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
}

# ── 主流程 ──────────────────────────────────────────────────────
log "HOA 构建开始"
log "  ARKUI_X_SRC  = $ARKUI_X_SRC"
log "  ARKUI_BUILD  = $ARKUI_BUILD"
log "  MUSL         = $MUSL"
log "  NDK          = $ANDROID_NDK_HOME"

if $SKIP_ARKUI; then
    log "跳过 ArkUI-X 编译 (--skip-arkui)"
else
    build_arkui_x
fi

if $SKIP_MUSL; then
    log "跳过 libb.so 编译 (--skip-musl)"
else
    build_libb
fi

sync_artifacts
build_apk

log "构建完成"
