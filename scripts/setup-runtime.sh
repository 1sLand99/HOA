#!/bin/bash
# setup-runtime.sh — Copy required ArkUI-X runtime files to the HOA project
#
# Usage: ./scripts/setup-runtime.sh [ARKUIX_SAMPLE_DIR] [ARKUIX_SRC_DIR]
#
# Arguments:
#   ARKUIX_SAMPLE_DIR  Path to ArkUI-X example project (default: /data/share/arkui-x-example)
#   ARKUIX_SRC_DIR     Path to ArkUI-X source tree (default: /src/arkui-x)
#
# This script copies:
#   1. Pre-built .so native libraries (arm64-v8a)
#   2. arkui_android_adapter.jar
#   3. .abc bytecode, module.json, resources.index from a sample app
#
# These files are excluded from git (.gitignore) since they are
# reproducible build artifacts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ARKUIX_SAMPLE_DIR="${1:-/data/share/arkui-x-example}"
ARKUIX_SRC_DIR="${2:-/src/arkui-x}"

echo "=== HOA Runtime Setup ==="
echo "Project:    $PROJECT_DIR"
echo "Sample dir: $ARKUIX_SAMPLE_DIR"
echo "Source dir:  $ARKUIX_SRC_DIR"
echo ""

# --- 1. Pre-built native libraries ---
echo "--- Copying native libraries ---"

LIBS_SRC="$ARKUIX_SAMPLE_DIR/.arkui-x/android/app/libs"
JNIDIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
JARDIR="$PROJECT_DIR/app/libs"

if [ ! -d "$LIBS_SRC" ]; then
    echo "ERROR: Sample libs directory not found: $LIBS_SRC"
    exit 1
fi

mkdir -p "$JNIDIR" "$JARDIR"

for so in "$LIBS_SRC/arm64-v8a/"*.so; do
    name=$(basename "$so")
    cp -v "$so" "$JNIDIR/$name"
done

if [ -f "$LIBS_SRC/arkui_android_adapter.jar" ]; then
    cp -v "$LIBS_SRC/arkui_android_adapter.jar" "$JARDIR/"
else
    echo "ERROR: arkui_android_adapter.jar not found"
    exit 1
fi

echo ""

# --- 2. .abc bytecode and assets ---
echo "--- Copying .abc bytecode and assets ---"

# Find the most complete sample app with .abc files
# Priority: EnjoyArkUIX > windowtest > arkui-x-example built output
ABC_SRC=""

# Try EnjoyArkUIX sample first (has complete assets)
CANDIDATE="$ARKUIX_SRC_DIR/samples/CodeLab/EnjoyArkUIX/.arkui-x/android/app/src/main/assets"
if [ -d "$CANDIDATE" ]; then
    # Use the 'resh' variant (release-resh), pick first module with .abc
    for module_dir in "$CANDIDATE/"*/arkui-x/*/; do
        if [ -f "${module_dir}ets/modules.abc" ]; then
            # Take the first complete module
            ABC_SRC="${module_dir%/}"
            break
        fi
    done
    # Fallback to doc variant
    if [ -z "$ABC_SRC" ]; then
        for module_dir in "$CANDIDATE/doc/arkui-x/"*/; do
            if [ -f "${module_dir}ets/modules.abc" ]; then
                ABC_SRC="${module_dir%/}"
                break
            fi
        done
    fi
fi

if [ -z "$ABC_SRC" ]; then
    echo "ERROR: No .abc sample found in ArkUI-X source tree"
    exit 1
fi

echo "Using sample from: $ABC_SRC"

ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/arkui-x/dynamicHap"
mkdir -p "$ASSETS_DIR/ets"
mkdir -p "$ASSETS_DIR/resources/base/profile"

# Copy .abc bytecode
cp -v "$ABC_SRC/ets/modules.abc" "$ASSETS_DIR/ets/"

# Copy sourceMaps if available
if [ -f "$ABC_SRC/ets/sourceMaps.map" ]; then
    cp -v "$ABC_SRC/ets/sourceMaps.map" "$ASSETS_DIR/ets/"
fi

# Copy module.json
if [ -f "$ABC_SRC/module.json" ]; then
    cp -v "$ABC_SRC/module.json" "$ASSETS_DIR/"
fi

# Copy resources.index
if [ -f "$ABC_SRC/resources.index" ]; then
    cp -v "$ABC_SRC/resources.index" "$ASSETS_DIR/"
fi

# Copy resource files
if [ -d "$ABC_SRC/resources" ]; then
    cp -rv "$ABC_SRC/resources/"* "$ASSETS_DIR/resources/" 2>/dev/null || true
fi

echo ""

# --- 3. Verify ---
echo "--- Verification ---"

errors=0

check_file() {
    if [ -f "$1" ]; then
        size=$(du -h "$1" | cut -f1)
        echo "  OK  $2 ($size)"
    else
        echo "  MISSING  $2"
        errors=$((errors + 1))
    fi
}

check_file "$JNIDIR/libarkui_android.so" "libarkui_android.so"
check_file "$JNIDIR/libhilog.so" "libhilog.so"
check_file "$JARDIR/arkui_android_adapter.jar" "arkui_android_adapter.jar"
check_file "$ASSETS_DIR/ets/modules.abc" "modules.abc"
check_file "$ASSETS_DIR/module.json" "module.json"
check_file "$ASSETS_DIR/resources.index" "resources.index"

# Check .abc version
if [ -f "$ASSETS_DIR/ets/modules.abc" ]; then
    version=$(xxd -l 4 -s 12 "$ASSETS_DIR/ets/modules.abc" | awk '{print $2, $3, $4, $5}' | tr ' ' '.')
    echo "  .abc bytecode version: $version"
fi

echo ""

if [ $errors -eq 0 ]; then
    echo "=== All files copied successfully ==="
    echo ""
    echo "Next steps:"
    echo "  1. Build the project:  ./gradlew assembleDebug"
    echo "  2. Install on device:  adb install app/build/outputs/apk/debug/app-debug.apk"
    echo "  3. Watch logs:         adb logcat -s HOA.App:V HOA.Main:V HOA.Ability:V"
else
    echo "=== $errors file(s) MISSING — check errors above ==="
    exit 1
fi
