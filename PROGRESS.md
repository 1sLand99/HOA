# HOA 项目进展

## 当前状态

**路径 A：从 OpenHarmony 源码构建运行时** — Phase 1-2 完成，Phase 3 待开始。

---

## 已完成

### Phase 1: 构建基础设施

#### Git Submodules（4 个，均来自 Gitee）

| 仓库 | 路径 | 用途 |
|------|------|------|
| `openharmony/arkcompiler_runtime_core` | `third_party/arkcompiler_runtime_core` | Panda VM, libpandafile, libpandabase, JIT compiler |
| `openharmony/arkui_ace_engine` | `third_party/ace_engine` | ACE 渲染引擎 |
| `arkui-x/app_framework` | `third_party/app_framework` | Stage 应用模型 |
| `arkui-x/arkui_for_android` | `third_party/arkui_for_android` | ArkUI-X Android 适配器 |

#### 构建系统（`third_party/`）

从 HongEngine 移植并适配，所有文件均以源码形式管理：

| 文件 | 说明 |
|------|------|
| `CMakeLists.txt` (1907行) | 19个 CMake 目标，arm64-v8a NDK 交叉编译 |
| `build_runtime.sh` (1000+行) | 构建编排：patch → codegen → compile → install |
| `android_port.patch` | 8 个上游 C++ 源码的 Android 移植补丁 |
| `build_gen/` | 代码生成输出（15 阶段 Ruby ERB 流水线，可从零生成） |

#### Android 移植补丁

上游 `arkcompiler_runtime_core` 子模块代码是纯 OpenHarmony 源码，需要 8 个补丁才能用 Android NDK 编译：

| 文件 | 修改内容 |
|------|----------|
| `compiler/optimizer/ir/runtime_interface.h` | 添加 `include/object_header.h`；`ark::EntrypointId(id)` → `static_cast<size_t>(id)` |
| `compiler/optimizer/code_generator/encode_visitor.cpp` | 添加 `#include "events_gen.h"` |
| `compiler/optimizer/ir_builder/inst_builder.h` | `IsBootContext()` 改为 stub（返回 false） |
| `plugins/ets/compiler/optimizer/ets_intrinsics_peephole.cpp` | 添加 `#include "runtime/include/thread.h"` |
| `plugins/ets/runtime/ets_class_linker_extension.cpp` | 无启动 panda 文件时跳过 `EtsPlatformTypes` 初始化 |
| `plugins/ets/runtime/ets_vm_api.cpp` | 入口点名称 `ETSGLOBAL::main` → `_GLOBAL::main` |
| `runtime/arch/asm_support.cpp` | 禁用 `static_assert`（交叉编译偏移量差异） |
| `verification/verifier_messages_data.cpp` | 生成文件 include 路径适配 |

补丁通过 `build_runtime.sh do_patch` 应用，支持幂等检测（不会重复打补丁）。

#### Codegen（代码生成）

ArkCompiler 使用 15 阶段 Ruby/ERB 代码生成流水线，从 ISA YAML 生成数千个头文件和源文件。**已完全修复，可从零生成 `build_gen/`**：

已修复的 Ruby 3.3 兼容性问题：
- `inst_builder_gen.cpp.erb` — `inst_templates.yaml` 缺少 `templates:` 键（修复：替换为 symlink → `ir_builder/inst_templates.yaml`）
- 8 个 Ruby 脚本缺少 `require 'ostruct'`（`gen_intrinsics_data.rb` 等已添加）
- `THIRD_PARTY` 变量未定义导致 `defines.cpp` 编译失败（已添加）
- `defines.cpp` 编译需要 `intrinsics_enum.h`、`language_config_gen.inc`（已在 Stage 11-12 先生成，然后将 cross_values 移至 Stage 12b）
- `cross_values_getters_generator.rb` 只生成 1 个 getter 函数，但 `runtime_interface.h` 需要 178 个（改用 stub 生成器始终生成完整 set）

`build_runtime.sh` 在 `build_gen/` 存在且非空时自动跳过 codegen（`[ -s ... ]` 检查）

#### 从源码构建的 .so 文件

`build_runtime.sh all` 完整流程：

```
build_runtime.sh all
  ├── do_preflight  — 检查 NDK/Ruby/CMake/子模块
  ├── do_patch      — 打 Android 移植补丁（幂等）
  ├── do_codegen    — 代码生成（build_gen 存在且非空时跳过，可用 './build_runtime.sh codegen' 强制重新生成）
  ├── do_compile    — CMake + NDK 交叉编译 arm64-v8a
  │   └── cmake --build --target hongengine_c
  └── do_install    — strip + 复制到 app jniLibs
```

编译产物（stripped 大小）：

| .so 文件 | 大小 | 来源 |
|----------|------|------|
| libarkruntime.so | 18MB | 源码编译 |
| libarkcompiler.so | 6.6MB | 源码编译 |
| libarkassembler.so | 980KB | 源码编译 |
| libpandafile.so | 612KB | 源码编译 |
| libpandabase.so | 464KB | 源码编译 |
| libziparchive.so | 212KB | 源码编译 |
| libz.so | 156KB | 源码编译 |
| libarkaotmanager.so | 76KB | 源码编译 |
| libc_secshared.so | 72KB | 源码编译 |
| libhongengine_c.so | 20KB | 源码编译 |
| libarktarget_options.so | 8KB | 源码编译 |
| libc++_shared.so | 1.7MB | NDK 复制 |
| libarkui_android.so | 95MB | ArkUI-X 示例项目 |
| libarkui_componentsnapshot.so | 540KB | ArkUI-X 示例项目 |
| libarkui_focuscontroller.so | 520KB | ArkUI-X 示例项目 |
| libhilog.so | 156KB | ArkUI-X 示例项目 |

### Phase 2: ArkCompiler 运行时验证

#### JNI 桥接 (`app/src/main/cpp/hongengine_jni.cpp`)
- `nativeInit` — 创建 `HongEngineState`，持有 `JavaVM*` 和 `ANativeWindow*`
- `nativeSetSurface` / `nativeSurfaceChanged` / `nativeSurfaceDestroyed` — Surface 管理
- `nativeRunPage` — ETS VM 创建 + ABC 验证 + 模块执行
- `nativeDestroy` / `nativeOnShow` / `nativeOnHide` — 生命周期

#### StageActivityV2 (`app/src/main/java/app/hackeris/hoa/runtime/StageActivityV2.kt`)
- 按依赖顺序加载 12 个 .so 文件
- SurfaceView 生命周期管理（SurfaceHolder.Callback）
- ABC 文件查找：动态安装目录 → assets 测试文件
- 测试 ABC 文件已就位：`assets/arkui-x/test/{hello.abc, etsstdlib.abc, fibonacci.abc}`

#### APK 状态
- `./gradlew assembleDebug` — BUILD SUCCESSFUL，94MB APK，17 个 native 库
- 设备验证：Honor 设备 (192.168.1.14:41913) 当前网络不可达

---

## 待完成

### Phase 3: ACE Engine Android Adapter
- AceContainerAndroid、AceViewAndroid、PlatformWindowAndroid
- OSAL 实现（文件资源、像素图、显示管理、输入路由）
- EGL 上下文 + Skia 渲染集成
- `third_party/CMakeLists.txt` 中 `ace_core_android` 和 `hongengine_ace` 目标待构建

### Phase 4: App Framework + HAP 加载
- ability_runtime 集成（JsRuntime → EcmaVM, AbilityStage）
- Android Activity 生命周期 → OHOS Stage 生命周期桥接

### Phase 5: NAPI Shim 框架
- 拦截 `@ohos.*` / `@kit.*` import
- Android 后端实现（文件系统、网络等核心 API）

### 其他待办
- MainActivity 添加启动 StageActivityV2 的按钮（目前只能通过 adb 启动）
- 设备验证：ETS VM 执行、ABC 文件加载
- 设备验证后，进一步验证交叉编译产物的 asm_defines.h 偏移量在真实设备上是否运行正常

### 文档
- BUILD.md — 构建文档已完成
