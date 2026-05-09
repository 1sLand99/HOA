# OpenHarmony HAP 加载与执行流程分析

## 概述

OpenHarmony 运行 HAP 中的 ETS 字节码并非简单地调用 `ExecuteModule("_GLOBAL::main")`。
实际流程涉及多个层次：appspawn 进程孵化 → ability_runtime 框架初始化 → JsRuntime 创建 VM
→ arkcompiler_ets_runtime (napi/ecmascript 层) 加载和执行模块。

## 关键发现：我们当前的做法与 OHOS 实际做法的差异

### 我们当前的做法（基于 arkcompiler_runtime_core）

```
1. hongengine_ets_create_runtime():
   - 加载 etsstdlib.abc + modules.abc 作为 BootPandaFiles
   - 设置 modules.abc 作为 PandaFiles
   - 调用 ark::ets::CreateRuntime() → 创建 ETS VM

2. hongengine_ets_execute_module():
   - 调用 ExecuteModule(name) → 内部调用 ExecutePandaFile(pfPath, "_GLOBAL::main", {})
   - 或直接调用 ExecutePandaFile(pfPath, "entry.ETSGLOBAL::func_main_0", {})
```

### OHOS 实际做法（基于 arkcompiler_ets_runtime）

```
1. JSNApi::CreateJSVM(RuntimeOption) → EcmaVM::Create()
   - RuntimeOption 设置: bundleName, GC类型, AOT等
   - 没有 SetBootPandaFiles 概念

2. JSNApi::SetBundle(vm, false)       // 合并ABC模式 (非bundle)
   JSNApi::SetModuleInfo(vm, assetPath, entryPoint)
   JSNApi::SetHostResolveBufferTracker(vm, callback)  // HSP解析

3. JSNApi::LoadAotFile(vm, moduleName)
   - 加载预编译的 AOT 文件 (.an/.ai)
   - etsstdlib 通过 AOT 方式加载, 不是 BootPandaFiles

4. JSNApi::ExecuteModuleBufferSecure(vm, buffer, size, path)
   或 JSNApi::ExecuteSecureWithOhmUrl(vm, data, size, srcFilename, ohmUrl)
   - 入口点是 OhmUrl (@bundle:bundleName/moduleName/ets/pages/Index)
   - 通过模块解析系统 (ModuleResolver::HostResolveImportedModule) 处理
   - 递归执行 SourceTextModule::Instantiate → Evaluate
```

## 完整流程 (6个阶段)

### 阶段1: AppSpawn 进程孵化

**文件:** `foundation/ability/ability_runtime/frameworks/native/appkit/app/main_thread.cpp`

- `HandleLaunchApplication` (line 1670) → `PreloadModule`
- `ProcessMainAbility` (line 1828): 构建 srcPath，获取 compileMode, srcEntrance
- 调用 `runtime->PreloadMainAbility(moduleName, srcPath, hapPath, isEsmode, srcEntrance)`

### 阶段2: JsRuntime 创建 EcmaVM

**文件:** `foundation/ability/ability_runtime/frameworks/native/runtime/js_runtime.cpp`

- `JsRuntime::Initialize` (line 689):
  - 调用 `CreateJsEnv(options)` 创建 VM
  - `panda::JSNApi::SetBundle(vm, options.isBundle)` — 设置为合并ABC模式
  - `panda::JSNApi::SetBundleName(vm, options.bundleName)`
  - `panda::JSNApi::SetHostResolveBufferTracker(vm, JsModuleReader(...))` — 注册HSP解析回调

- `CreateJsEnv` (line 818):
  - 创建 `panda::RuntimeOption` (GC类型, 堆大小, JIT, AOT等)
  - 创建 `JsEnv::JsEnvironment`
  - 调用 `jsEnv_->Initialize(pandaOption, this)`

**文件:** `foundation/ability/ability_runtime/js_environment/frameworks/js_environment/src/js_environment.cpp`

- `JsEnvironment::Initialize` (line 59):
  ```cpp
  vm_ = panda::JSNApi::CreateJSVM(pandaOption);
  engine_ = new ArkNativeEngine(vm_, jsEngine);
  ```

### 阶段3: 设置模块信息和资源路径

**文件:** `foundation/ability/ability_runtime/frameworks/native/runtime/js_runtime.cpp`

- `RunScript` (line 1176): 非bundle模式下:
  ```cpp
  path = "/data/storage/el1/bundle/" + moduleName + "/ets/modules.abc";
  panda::JSNApi::SetAssetPath(vm, path);
  panda::JSNApi::SetModuleName(vm, moduleName);
  ```

- `UpdateModuleNameAndAssetPath` (line 1389): 更新模块名和资源路径

**文件:** `foundation/ability/ability_runtime/frameworks/native/runtime/js_module_reader.cpp`

- `JsModuleReader`: 从 HAP (ZIP) 中提取模块数据
  - 支持安全内存 (safe memory) 和压缩HAP两种模式
  - MERGE_ABC_PATH = `"ets/modules.abc"`

### 阶段4: 加载和执行 modules.abc

**文件:** `foundation/ability/ability_runtime/frameworks/native/runtime/js_runtime.cpp`

- `LoadModule` (line 1025):
  - 检查是否为 OhmUrl (`panda::JSNApi::IsOhmUrl`)
  - ES模块模式: 调用 `LoadJsModule(fileName, hapPath, srcEntrance)`
  - Bundle模式: 调用 `LoadJsBundle(fileName, hapPath, useCommonChunk)`

- `LoadScript` (line 568):
  - ES模块+OhmUrl模式:
    ```cpp
    return panda::JSNApi::ExecuteSecureWithOhmUrl(vm, buffer, len, srcFilename, srcEntrance);
    ```
  - 非OhmUrl模式:
    ```cpp
    return jsEnv_->LoadScript(path, buffer, len, isBundle);
    ```

**文件:** `foundation/arkui/napi/native_engine/impl/ark/ark_native_engine.cpp`

- `RunScriptBuffer` (line 2539):
  ```cpp
  if (isBundle) {
      ret = panda::JSNApi::ExecuteSecure(vm_, buffer, size, "_GLOBAL::func_main_0", path);
  } else {
      ret = panda::JSNApi::ExecuteModuleBufferSecure(vm_, buffer, size, path);
  }
  ```

### 阶段5: 入口点确定

`_GLOBAL::func_main_0` 在以下位置定义为常量:
- `foundation/arkui/napi/native_engine/native_engine.cpp:32`
- `foundation/arkui/napi/native_engine/impl/ark/ark_native_engine.cpp:99`
- `arkcompiler/ets_runtime/ecmascript/module/module_path_helper.h:97`

**关键:** `_GLOBAL::func_main_0` 仅用于 **Bundle 模式** (旧格式)。
对于 **合并ABC (ES模块) 模式**，入口点为 OhmUrl 格式:
```
@bundle:bundleName/moduleName/ets/pages/Index
```

OhmUrl 构建 (`foundation/arkui/ace_engine/frameworks/bridge/declarative_frontend/engine/jsi/jsi_declarative_engine.cpp:303`):
```cpp
std::string BuildOhmUrl(bundleName, moduleName, pagePath) {
    return "@bundle:" + bundleName + "/" + moduleName + "/ets/" + pagePath;
}
```

### 阶段6: @ohos.* 和 @system.* 模块解析

**文件:** `arkcompiler/ets_runtime/ecmascript/module/module_path_helper.h` (line 73-83)

前缀定义:
| 前缀 | 用途 |
|------|------|
| `@bundle:` | 应用内/跨应用 OHOS 模块 |
| `@package:` | Ohpm 第三方包 |
| `@ohos:` | OHOS NAPI 系统模块 (如 @ohos:hilog) |
| `@native:` | 系统内建模块 (如 @system.app) |
| `@app:` | 应用级 NAPI 原生模块 (.so) |

**文件:** `arkcompiler/ets_runtime/ecmascript/module/module_path_helper.cpp` (line 29-34)

`ConcatFileNameWithMerge` 根据前缀分派:
- `@bundle:` → `ParsePrefixBundle` (解析跨模块引用)
- `@package:` → 返回包路径
- `ets/` → `MakeNewRecord` (ETS相对引用)
- 其他 → `ParseThirdPartyPackage`

**文件:** `arkcompiler/ets_runtime/ecmascript/module/js_module_source_text.cpp` (line 296-321)

`CheckNativeModule` 判断模块是否为原生NAPI模块:
- `@ohos:xxx` → OHOS_MODULE (系统NAPI, 如 @ohos:hilog, @ohos:router)
- `@app:xxx` → APP_MODULE (应用原生.so)
- `@native:xxx.xxx` → NATIVE_MODULE (系统内建)

`GetRequireNativeModuleFunc` 从全局对象获取 `requireNapi` 函数,
该函数是解析所有 `@ohos:` 和 `@system:` 导入的 NAPI 桥接。

## 关键架构差异

| 方面 | 我们当前做法 | OHOS 实际做法 |
|------|-------------|--------------|
| VM创建 | `ark::ets::CreateRuntime()` | `JSNApi::CreateJSVM()` → `EcmaVM::Create()` |
| 运行时层 | 直接使用 arkcompiler_runtime_core | 通过 arkcompiler_ets_runtime (napi/ecmascript层) |
| stdlib加载 | 作为 BootPandaFiles | 通过 AOT (.an/.ai 文件) |
| 入口点 | `_GLOBAL::func_main_0` | OhmUrl (@bundle:...) → 模块记录名 |
| 模块解析 | 无 (直接函数调用) | ModuleResolver → SourceTextModule |
| @ohos:解析 | 无 (会失败) | requireNapi → NAPI .so 查找 |
| 合并ABC | 不支持 | ExecuteModuleBufferSecure |

## 设备上的关键路径

| 路径 | 用途 |
|------|------|
| `/data/storage/el1/bundle/{moduleName}/ets/modules.abc` | 安装后的合并ABC |
| `/data/storage/ark-cache/` | JIT/AOT 缓存 |
| `/data/service/el1/public/for-all-app/framework_ark_cache/` | 系统框架 AOT 缓存 (含 etsstdlib) |
| `/system/etc/system_kits_config.json` | @ohos: 模块映射配置 |

## 结论

要正确运行 HAP 的 modules.abc，我们需要:

1. **不能绕过 arkcompiler_ets_runtime 层** — 该层提供了模块解析系统(ModuleResolver)、
   OhmUrl处理、以及 @ohos:/@system: 导入的 NAPI 桥接。

2. **入口点不是 func_main_0** — 对于 ES模块格式的 HAP，入口点是 OhmUrl 格式的模块记录名，
   需要通过 `ExecuteModuleBufferSecure` 或 `ExecuteSecureWithOhmUrl` 执行。

3. **etsstdlib 不应作为 BootPandaFiles 加载** — 在 OHOS 上，etsstdlib 通过 AOT 编译器
   预编译为 .an/.ai 文件，通过 `LoadAotFile` 加载。

4. **@ohos: 和 @system: 导入需要 NAPI 桥接** — 我们需要提供 `requireNapi` 函数
   来解析这些系统模块引用 (可以返回空实现/Mock)。

5. **quickened 标记一致性** — 在我们的环境中，应该使用非quickened的 stdlib 
   配合非quickened的 HAP (debug构建)，或者全部quickened。不能混用。
