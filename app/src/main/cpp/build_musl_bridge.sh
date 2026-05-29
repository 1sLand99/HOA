#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

missing_vars=()
for var in ANDROID_NDK_HOME MUSL; do
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
    echo "  export ANDROID_NDK_HOME=/path/to/ndk" >&2
    echo "  export MUSL=/path/to/musl" >&2
    exit 1
fi
NDK="$ANDROID_NDK_HOME"
MUSL="$MUSL"

if [ ! -d "$NDK" ]; then
    echo "ERROR: NDK not found at $NDK" >&2
    echo "  Set ANDROID_NDK_HOME to a valid NDK path." >&2
    exit 1
fi

if [ ! -d "$MUSL" ]; then
    echo "ERROR: musl not found at $MUSL" >&2
    echo "  Set MUSL=/path/to/musl." >&2
    exit 1
fi

exec make -f Makefile.musl_bridge -j"$(nproc)" all NDK="$NDK" MUSL="$MUSL"
