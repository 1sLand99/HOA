# Building HOA

## Quick start

```bash
# 1. Clone with submodules
git clone --recurse-submodules <this-repo>
cd HOA

# 2. Build all native .so files from source (~30 min first build)
cd third_party
./build_runtime.sh all

# 3. Build APK
cd ..
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~94 MB, 17 native libraries).

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Android NDK | 28.2+ | `ls /apps/android/ndk/28.2.13676358/build/cmake/android.toolchain.cmake` |
| CMake | ≥ 3.10 | `cmake --version` |
| Ruby | ≥ 3.0 | `ruby --version` |
| Gradle | 8.x (wrapper) | `./gradlew --version` |

If your NDK is at a different path, edit `third_party/build_runtime.sh` line 27 (`NDK=...`).

## Build steps in detail

### `./build_runtime.sh all` — full pipeline

```
do_preflight  →  verify NDK, CMake, Ruby, git submodules
do_patch      →  apply android_port.patch (idempotent)
do_codegen    →  generate 100+ C++ headers from ISA YAML via Ruby/ERB (skips if build_gen/ exists)
do_compile    →  CMake + NDK arm64-v8a cross-compile → 12 .so files
do_install    →  strip + copy to app/src/main/jniLibs/arm64-v8a/
```

Individual steps:

```bash
./build_runtime.sh codegen     # only regenerate build_gen/
./build_runtime.sh compile     # only cross-compile (needs build_gen/)
./build_runtime.sh install     # only copy .so files
./build_runtime.sh clean       # remove build_android/
```

### Code generation (15 stages)

The ArkCompiler runtime uses a Ruby/ERB codegen pipeline that generates thousands of lines
of C++ headers and source files from ISA YAML specifications. These are reproducible from
the source tree — rebuilding `build_gen/` from scratch takes ~2 minutes.

```bash
rm -rf build_gen
./build_runtime.sh codegen
```

Stages: ISA combine → ISA templates → type.h → plugin options → libpandabase →
compiler headers → runtime options → bytecode definitions → plugin defines →
entrypoints → assembler codegen → intrinsics.yaml → compiler intrinsics →
runtime intrinsics → runtime plugins → asm_defines.h + cross_values →
verification headers → verification templates.

### Android portability patches

The upstream `arkcompiler_runtime_core` source is pure OpenHarmony code. Eight patches in
`android_port.patch` make it compile with Android NDK (Clang 19, libc++, arm64-v8a). The
`do_patch` step is idempotent — safe to run multiple times.

### Build output

| .so | Size | Built from |
|-----|------|-----------|
| libarkruntime.so | 18 MB | Source (ETS VM, GC, interpreter) |
| libarkcompiler.so | 6.6 MB | Source (JIT compiler, optimizer) |
| libarkassembler.so | 980 KB | Source (bytecode assembler) |
| libpandafile.so | 612 KB | Source (Panda file format) |
| libpandabase.so | 464 KB | Source (utilities, logging, threading) |
| libziparchive.so | 212 KB | Source (ZIP/HAP extraction) |
| libz.so | 156 KB | Source (zlib) |
| libarkaotmanager.so | 76 KB | Source (AOT compilation) |
| libc_secshared.so | 72 KB | Source (secure C runtime) |
| libhongengine_c.so | 20 KB | Source (C wrapper API) |
| libarktarget_options.so | 8 KB | Source (target-specific compiler flags) |
| libc++_shared.so | 1.7 MB | NDK (C++ STL) |
| libarkui_android.so | 95 MB | ArkUI-X prebuilt SDK |
| libarkui_componentsnapshot.so | 540 KB | ArkUI-X prebuilt SDK |
| libarkui_focuscontroller.so | 520 KB | ArkUI-X prebuilt SDK |
| libhilog.so | 156 KB | ArkUI-X prebuilt SDK |

## Project structure

```
HOA/
├── third_party/
│   ├── build_runtime.sh          # Build orchestrator (1000+ lines)
│   ├── CMakeLists.txt            # Cross-compilation build (1907 lines, 19 targets)
│   ├── android_port.patch        # 8 patches for Android NDK compatibility
│   ├── build_gen/                # Generated headers (15-stage codegen output)
│   ├── build_android/            # CMake build directory (gitignored)
│   ├── arkcompiler_runtime_core/ # Submodule: Panda VM, compiler, assembler
│   ├── ace_engine/               # Submodule: ACE rendering engine
│   ├── app_framework/            # Submodule: Stage app model
│   └── arkui_for_android/        # Submodule: Android adapter
├── app/
│   ├── src/main/jniLibs/         # Native libraries (gitignored, built by build_runtime.sh)
│   ├── src/main/cpp/             # JNI bridges
│   ├── src/main/assets/arkui-x/  # System resources + test ABC files
│   └── build.gradle.kts
└── PROGRESS.md                   # Project progress tracker
```

## Troubleshooting

**"fatal error: 'asm_defines.h' file not found"** — Run codegen first: `./build_runtime.sh codegen`

**Ruby OpenStruct errors** — Fixed in the codegen scripts. If you see `uninitialized constant OpenStruct` on Ruby ≥ 3.3, ensure you're using the latest build_runtime.sh.

**inst_builder_gen.cpp is empty or truncated** — Remove build_gen and re-run codegen:
```bash
rm -rf build_gen
./build_runtime.sh codegen
```

**NDK not found** — Edit `NDK=/path/to/your/ndk` in `build_runtime.sh` line 27.

**APK too large** — The 95 MB `libarkui_android.so` dominates. If you only need
ArkCompiler runtime (no UI), you can exclude ArkUI-X .so files.
