# Path A: Phase 5+ — 正确的 HAP 加载路径

## 核心发现 (Phase 4 研究结论)

Phase 4 的试验和研究揭示了关键架构差异：**我们一直在使用错误的运行时层**。

### 我们当前的做法（基于 arkcompiler_runtime_core，不正确）

```
hongengine_ets_create_runtime():
  → 加载 etsstdlib.abc + modules.abc 作为 BootPandaFiles
  → 调用 ark::ets::CreateRuntime() → 创建 ETS VM

hongengine_ets_execute_module():
  → ExecuteModule(name) → ExecutePandaFile(pfPath, "_GLOBAL::func_main_0", {})
```

**问题：**
- `func_main_0` 不是合并ABC格式的入口点 — 即使 `NONEXISTENT::nonexistent` 也返回 OK
- 合并ABC 需要通过模块解析系统 (ModuleResolver) 处理，不能直接调用 ExecutePandaFile
- @ohos:/@system: 导入必须通过 NAPI 桥接解析
- etsstdlib 在 OHOS 上通过 AOT (.an/.ai) 加载，不是 BootPandaFiles

### OHOS 实际做法（基于 arkcompiler_ets_runtime）

```
1. JSNApi::CreateJSVM(RuntimeOption) → EcmaVM::Create()
2. JSNApi::SetBundle(vm, false)        // 合并ABC模式
   JSNApi::SetModuleInfo(vm, assetPath, entryPoint)
   JSNApi::SetHostResolveBufferTracker(vm, callback)
3. JSNApi::LoadAotFile(vm, moduleName) // 加载预编译系统ABC
4. JSNApi::ExecuteModuleBufferSecure(vm, buffer, size, path)
   或 ExecuteSecureWithOhmUrl(vm, data, size, filename, ohmUrl)
   → ModuleResolver::HostResolveImportedModule
   → SourceTextModule::Instantiate → Evaluate
```

### 关键源码位置

| 组件 | 路径 |
|------|------|
| 进程入口 | `foundation/ability/ability_runtime/.../main_thread.cpp` |
| JsRuntime | `foundation/ability/ability_runtime/.../runtime/js_runtime.cpp` |
| JsEnvironment | `foundation/ability/ability_runtime/js_environment/.../js_environment.cpp` |
| ArkNativeEngine | `foundation/arkui/napi/native_engine/impl/ark/ark_native_engine.cpp` |
| JSNApi | `arkcompiler/ets_runtime/ecmascript/napi/jsnapi_expo.cpp` |
| ModulePathHelper | `arkcompiler/ets_runtime/ecmascript/module/module_path_helper.cpp` |
| SourceTextModule | `arkcompiler/ets_runtime/ecmascript/module/js_module_source_text.cpp` |

详细分析见 `FOUND.md`。

## 已完成的 Phase 成果

Phase 1-4 取得了以下可用成果：

| 成果 | 状态 |
|------|------|
| 版本升级 (0.0.0.5 → 13.0.1.0) | 已验证 — `libpandafile.so` 可打开现代字节码 |
| HAP 解压 | 已验证 — ZipFile 正确提取 modules.abc (9228 bytes, 60 classes) |
| 所有 .so 加载 | 已验证 — 13 个 native 库全部加载成功 |
| ETS VM 创建 | 已验证 — `ark::ets::CreateRuntime()` 成功 |
| 入口点调用 | **不可行** — `ExecutePandaFile(func_main_0)` 不是正确的模块加载方式 |

## 修正后的路线

### Phase 5: 引入 arkcompiler_ets_runtime 层

**目标：** 构建并集成 napi/ecmascript 层，替代当前的直接 core runtime 调用。

**子仓库状态：** `third_party/arkcompiler_ets_runtime` 已添加为 git submodule。

**步骤：**

1. **分析 arkcompiler_ets_runtime 构建系统**
   - 查看 `ecmascript/` 目录的 CMakeLists / gn 配置
   - 确定 Android ARM64 交叉编译所需的目标和依赖
   - 关键目标：`libark_jsruntime.so`（或等价物，提供 JSNApi）

2. **交叉编译 for Android**
   - 创建 CMakeLists 适配（参考 `third_party/CMakeLists.txt` 中的模式）
   - 编译依赖：libuv、zlib、openssl（napi 层可能需要）
   - 链接 arkcompiler_runtime_core

3. **验证构建产物**
   - `JSNApi::CreateJSVM` 符号可调用
   - `JSNApi::ExecuteModuleBufferSecure` 符号可调用
   - `ModulePathHelper` 链完整

### Phase 6: 替换 C API 为 JSNApi 调用

**目标：** 重写 `hongengine_c_api.cpp`，使用 JSNApi 替代直接的 core runtime 调用。

**变更文件：**

```
third_party/hongengine_c_api.h    — 新增 JSNApi 包装函数
third_party/hongengine_c_api.cpp  — 使用 JSNApi 替代 ark::ets:: 调用
app/src/main/cpp/hongengine_jni.cpp — 更新 JNI 调用
```

**新 API 设计：**

```c
// VM 创建（替代 hongengine_ets_create_runtime）
HongEngineError hongengine_js_create_vm(HongEngineState* state,
                                         const char* bundle_name);

// 模块加载（替代 hongengine_ets_execute_module）
// 自动检测合并ABC格式，使用 ModuleResolver 加载
HongEtsResult hongengine_js_load_module(HongEngineState* state,
                                         const char* abc_path,
                                         const char* ohm_url,    // 入口点 URL
                                         const char* module_name);

// requireNapi 注册（处理 @ohos:/@system: 导入）
HongEngineError hongengine_js_register_napi(HongEngineState* state,
                                             const char* module_name,
                                             void* require_func);
```

**关键变化：**
- `CreateRuntime` → `JSNApi::CreateJSVM` + `JSNApi::SetBundle(false)`
- `ExecuteModule` → `JSNApi::ExecuteModuleBufferSecure` 或 `ExecuteSecureWithOhmUrl`
- OhmUrl 格式：`@bundle:{bundleName}/{moduleName}/ets/{pagePath}`

### Phase 7: OhmUrl 入口点构建

**目标：** 从 HAP 的 module.json 构建正确的 OhmUrl 入口点。

**HAP 信息：**
```json
{
  "bundleName": "app.hackeris.harmonyexample",
  "compileMode": "esmodule",
  "srcEntry": "./ets/entryability/EntryAbility.ets"
}
```

**OhmUrl 构建规则**（参考 `jsi_declarative_engine.cpp:BuildOhmUrl`）：
```
@bundle:{bundleName}/{moduleName}/ets/{pagePath}
```

对于 EntryAbility：
```
@bundle:app.hackeris.harmonyexample/entry/ets/entryability/EntryAbility
```

**实现步骤：**
1. 解析 `module.json` 获取 `srcEntry`
2. 将 `.ets` 扩展名去掉
3. 构建 OhmUrl：`@bundle:{bundleName}/{moduleName}/ets/{relativePath}`

### Phase 8: NAPI 桥接（@ohos:* 和 @system:* 导入）

**目标：** 提供 requireNapi 函数来处理 HAP 引用的系统模块。

**HAP 引用的外部模块：**
- `@ohos:app.ability.UIAbility` — Ability 基类
- `@ohos:hilog` — 日志系统
- `@ohos:application.BackupExtensionAbility`

**策略（分两阶段）：**

**8a. 最小化 stub（让模块初始化和 EntryAbility 实例化能进行）**

为以下核心类提供最小化实现：
```
@ohos:app.ability.UIAbility → 提供 onCreate/onDestroy 空实现
@ohos:hilog                  → 桥接到 Android __android_log_print
@ohos:router                 → 空实现（暂不需要路由）
@ohos:application.*          → 空实现
```

**8b. 完整的 NAPI 桥接框架**

参考 OHOS `GetRequireNativeModuleFunc`（`js_module_source_text.cpp:323`）:
```cpp
// requireNapi 函数签名
JSValue requireNapi(JSThread* thread, JSValue moduleName, JSValue version);
```

在 Android 上：
```cpp
// 查找规则: 将 @ohos:hilog 映射到 libhilog.so 或 JNI 实现
// 查找规则: 将 @ohos:app.ability.UIAbility 映射到 libability_napi.so
```

## 修正后的文件变更计划

### Phase 5 (构建):

| 文件 | 变更 |
|------|------|
| `third_party/CMakeLists.txt` | 新增 arkcompiler_ets_runtime 构建目标 |
| `third_party/arkcompiler_ets_runtime/` | 已存在（git submodule） |
| `third_party/android_port.patch` | 可能需要新增 napi 层的 Android 适配补丁 |
| `app/src/main/jniLibs/arm64-v8a/` | 新增 libark_jsruntime.so 等产物 |

### Phase 6-8 (集成):

| 文件 | 变更 |
|------|------|
| `third_party/hongengine_c_api.h` | **重写** — 改用 JSNApi 函数 |
| `third_party/hongengine_c_api.cpp` | **重写** — 实现 JSNApi 包装 |
| `app/src/main/cpp/hongengine_jni.cpp` | **重写** — OhmUrl 构建 + NAPI 桥接 |
| `app/src/main/java/.../StageActivityV2.kt` | 小改 — 新增 OhmUrl 构建逻辑 |
| `app/src/main/cpp/napi_bridge.cpp` | **新建** — @ohos:* 模块 stub 实现 |

### 不再需要的文件:

| 文件 | 原因 |
|------|------|
| `third_party/arkcompiler_runtime_core/static_core/plugins/ets/runtime/ets_vm_api.cpp 修改` | 不再直接调用 ExecuteModule |
| `third_party/arkcompiler_runtime_core/static_core/runtime/runtime.cpp 修改` | 不再需要 bypass quickened 检查（在 JSNApi 层处理） |

## 验证步骤

```bash
# Phase 5: 构建验证
cd third_party/build && make -j$(nproc) ark_jsruntime
# 确认: libark_jsruntime.so 产生

# Phase 6: VM 创建验证
adb logcat -s HOA.Runtime:* ArkEtsVm:*
# 确认: "JSNApi::CreateJSVM succeeded"
# 确认: "SetBundle: isBundle=false"

# Phase 7: 模块加载验证
# 确认: OhmUrl 正确构建
# 确认: ExecuteModuleBufferSecure 调用成功
# 确认: ModuleResolver::HostResolveImportedModule 返回模块记录

# Phase 8: NAPI 桥接验证
# 确认: @ohos:hilog 调用输出到 logcat
# 确认: EntryAbility 实例创建
# 确认: onCreate 生命周期回调被调用
```

## 关键参考文件

| 文件 | 作用 |
|------|------|
| `FOUND.md` | 完整的 OHOS HAP 加载流程分析（中文） |
| `third_party/arkcompiler_ets_runtime/ecmascript/napi/jsnapi_expo.cpp` | JSNApi 实现 |
| `third_party/arkcompiler_ets_runtime/ecmascript/module/module_path_helper.cpp` | 模块路径解析 |
| `/src/ohos/foundation/ability/ability_runtime/.../js_runtime.cpp` | 真实 OHOS 加载流程 |
| `/src/ohos/foundation/arkui/napi/native_engine/impl/ark/ark_native_engine.cpp` | NativeEngine 桥接 |
