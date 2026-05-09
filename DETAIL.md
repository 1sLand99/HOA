# Path A: Phase 5-9 详细实施步骤

> 基于 FOUND.md 对 OpenHarmony HAP 加载流程的分析，修正后的实施方案。
> 核心认知：必须通过 `arkcompiler_ets_runtime` (napi/ecmascript层) 加载 HAP，
> 不能绕过模块系统直接调用 `ExecutePandaFile(func_main_0)`。

## Phase 5: 构建 arkcompiler_ets_runtime（napi/ecmascript 层）

### 5.1 分析 arkcompiler_ets_runtime 源码结构

**前置条件：** `third_party/arkcompiler_ets_runtime` 已作为 git submodule 克隆。

**关键目录结构：**
```
arkcompiler_ets_runtime/
├── ecmascript/
│   ├── napi/                    # JSNApi 接口层
│   │   ├── jsnapi_expo.cpp      # CreateJSVM, Execute, SetBundle, SetModuleInfo ...
│   │   └── include/jsnapi.h     # 公共头文件
│   ├── module/                  # ES 模块系统
│   │   ├── module_path_helper.cpp   # OhmUrl/@ohos:/@bundle: 路径解析
│   │   ├── module_path_helper.h     # 前缀常量 + ENTRY_MAIN_FUNCTION
│   │   ├── js_module_source_text.cpp # SourceTextModule: Instantiate/Evaluate
│   │   └── module_resolver.cpp      # HostResolveImportedModule
│   ├── jspandafile/             # ABC 文件访问层
│   │   ├── js_pandafile.cpp     # JSPandaFile: 合并ABC读取
│   │   ├── js_pandafile_manager.cpp # 文件管理
│   │   └── js_pandafile_executor.cpp
│   ├── ohos/                    # OHOS 平台特定
│   │   └── ohos_constants.h     # 设备路径常量
│   ├── base/                    # 基础工具类
│   ├── jobs/                    # 微任务/Promise
│   └── mem/                     # 内存管理
└── ...
```

**任务：**
- [ ] 列出 `ecmascript/` 下所有编译目标（`.gn` 或 `BUILD.gn` 文件）
- [ ] 识别 Android 上不需要的模块（如 `ohos/` 中 hilog 相关）
- [ ] 确定最小可构建目标列表：napi + module + jspandafile + base

### 5.2 分析依赖

```
arkcompiler_ets_runtime 依赖:
├── arkcompiler_runtime_core  ✅ 已构建 (libarkruntime.so 等)
│   ├── libpandafile.so       ✅
│   ├── libpandabase.so       ✅
│   ├── libarkruntime.so      ✅
│   └── libziparchive.so      ✅
├── libuv                     ⚠️ 需确认 (事件循环)
├── zlib                      ✅ 已处理 (libz.so)
└── OpenSSL                   ⚠️ 可能仅安全内存功能需要
```

**任务：**
- [ ] 检查 `ecmascript/` 的 GN 构建文件
- [ ] 列出链接时需要的外部库
- [ ] 确认 `libuv` 是否必须（或可用 stub 替代事件循环）
- [ ] 确认哪些依赖可通过 `libarkruntime.so` 间接满足

### 5.3 创建 CMake 构建配置

**文件：** `third_party/CMakeLists.txt`（新增部分）

```cmake
# ark_jsruntime — ETS napi/ecmascript layer
set(ETS_RUNTIME_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/arkcompiler_ets_runtime)

add_library(ark_jsruntime SHARED
    ${ETS_RUNTIME_ROOT}/ecmascript/napi/jsnapi_expo.cpp
    ${ETS_RUNTIME_ROOT}/ecmascript/module/module_path_helper.cpp
    ${ETS_RUNTIME_ROOT}/ecmascript/module/js_module_source_text.cpp
    ${ETS_RUNTIME_ROOT}/ecmascript/module/module_resolver.cpp
    ${ETS_RUNTIME_ROOT}/ecmascript/jspandafile/js_pandafile.cpp
    ${ETS_RUNTIME_ROOT}/ecmascript/jspandafile/js_pandafile_manager.cpp
    # ... 按需增加
)

target_link_libraries(ark_jsruntime
    PRIVATE arkruntime pandafile pandabase
    # PRIVATE uv z ...
)
```

**任务：**
- [ ] 确定需要编译的 `.cpp` 文件最小集合
- [ ] 处理 `#include` 路径（ecmascript 内部路径与 core 路径的差异）
- [ ] 处理条件编译：将 `PANDA_TARGET_OHOS` 替换为 Android 等效实现
- [ ] 创建 `android_port_ets.patch`（如需要）

### 5.4 交叉编译

```bash
cd third_party/build
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=/apps/android/ndk/{version}/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release
make -j$(nproc) ark_jsruntime
```

**任务：**
- [ ] 确认 NDK 路径和版本
- [ ] 运行 cmake 配置
- [ ] 修复编译错误（头文件路径、未定义符号、条件编译等）
- [ ] 成功产生 `libark_jsruntime.so`

### 5.5 验证构建产物

```bash
# 检查导出符号
readelf -Ws lib/libark_jsruntime.so | grep -E "CreateJSVM|ExecuteModule|SetBundle|SetModuleInfo|SetAssetPath"

# 检查动态库依赖
readelf -d lib/libark_jsruntime.so | grep NEEDED

# 确认文件大小
ls -lh lib/libark_jsruntime.so
```

**任务：**
- [ ] 确认 `JSNApi::CreateJSVM` 等关键符号可被 `dlsym` 找到
- [ ] 确认没有未定义的外部符号
- [ ] 将 `.so` 复制到 `app/src/main/jniLibs/arm64-v8a/`

---

## Phase 6: 替换 C API — 从 ark::ets 迁移到 JSNApi

### 6.1 重写 hongengine_c_api.h — 新的 JSVM API

**旧 API（废弃）：**
```c
HongEngineError hongengine_ets_create_runtime(state, abc, stdlib);
HongEtsResult hongengine_ets_execute_module(state, module_name, entry_point);
```

**新 API：**
```c
// VM 生命周期
HongEngineError hongengine_jsvm_create(HongEngineState* state,
                                       const char* bundle_name,
                                       bool is_bundle);       // false = 合并ABC

HongEngineError hongengine_jsvm_destroy(HongEngineState* state);

// 模块加载 — 使用 OhmUrl 入口点
// ohm_url: @bundle:app.hackeris.harmonyexample/entry/ets/entryability/EntryAbility
HongEtsResult hongengine_js_load_module(HongEngineState* state,
                                         const char* abc_path,
                                         const char* ohm_url,
                                         const char* module_name);

// NAPI 模块注册 — 处理 @ohos:* 导入
typedef void* (*HongNativeModuleInit)(void* engine, void* exports);
HongEngineError hongengine_js_register_napi(HongEngineState* state,
                                             const char* module_name,
                                             HongNativeModuleInit init_func);
```

### 6.2 实现 VM 创建

```cpp
// 参考: js_runtime.cpp:CreateJsEnv → js_environment.cpp:Initialize

HongEngineError hongengine_jsvm_create(HongEngineState* state,
                                       const char* bundle_name,
                                       bool is_bundle) {
    panda::RuntimeOption option;
    option.SetGcType("g1-gc");
    option.SetBundleName(bundle_name);
    option.SetArkBundleName("");

    auto* vm = panda::JSNApi::CreateJSVM(option);  // ← 核心调用
    if (!vm) {
        state->lastError = "JSNApi::CreateJSVM failed";
        return HONGENGINE_ERROR_VM_CREATE;
    }
    state->jsVm = vm;

    // 设置为合并ABC模式（非bundle）
    panda::JSNApi::SetBundle(vm, is_bundle);
    panda::JSNApi::SetBundleName(vm, bundle_name);

    // 注册模块解析回调（HSP用 — 暂不需要）
    // panda::JSNApi::SetHostResolveBufferTracker(vm, callback);

    return HONGENGINE_OK;
}
```

### 6.3 实现模块加载

```cpp
// 参考: js_runtime.cpp:LoadScript (OhmUrl路径)
//       ark_native_engine.cpp:RunScriptBuffer (非bundle分支)

HongEtsResult hongengine_js_load_module(HongEngineState* state,
                                         const char* abc_path,
                                         const char* ohm_url,
                                         const char* module_name) {
    // 1. 读取 ABC 到内存
    std::vector<uint8_t> buffer = ReadFileBytes(abc_path);
    if (buffer.empty()) {
        return Error(HONGENGINE_ERROR_FILE, "Failed to read ABC file");
    }

    // 2. 设置路径和模块名
    panda::JSNApi::SetAssetPath(state->jsVm, abc_path);
    panda::JSNApi::SetModuleName(state->jsVm, module_name);

    // 3. 执行 (合并ABC + OhmUrl)
    bool ok = panda::JSNApi::ExecuteSecureWithOhmUrl(
        state->jsVm,
        buffer.data(), buffer.size(),
        abc_path,      // srcFilename
        ohm_url        // 入口点 OhmUrl
    );

    if (!ok) {
        state->lastError = "ExecuteSecureWithOhmUrl failed";
        return Error(HONGENGINE_OK, state->lastError.c_str());
    }
    return Success(0);
}
```

### 6.4 更新 JNI 桥接

**文件：** `app/src/main/cpp/hongengine_jni.cpp`

```cpp
// nativeRunPage — 重写签名，接受 ohmUrl 而非 entryPoint
JNIEXPORT jstring JNICALL
Java_..._nativeRunPage(JNIEnv* env, jclass, jlong ptr,
    jstring bundleName, jstring moduleName,
    jstring abcPath, jstring ohmUrl) {

    auto* state = GetState(ptr);

    const char* bundle = env->GetStringUTFChars(bundleName, nullptr);
    const char* module = env->GetStringUTFChars(moduleName, nullptr);
    const char* abc    = env->GetStringUTFChars(abcPath, nullptr);
    const char* ohm    = env->GetStringUTFChars(ohmUrl, nullptr);

    // 创建 VM
    HongEngineError err = hongengine_jsvm_create(state->c_state, bundle, false);
    if (err != HONGENGINE_OK) {
        // ... error
    }

    // 加载模块
    HongEtsResult result = hongengine_js_load_module(state->c_state, abc, ohm, module);

    // ... release strings, return result
}
```

### 6.5 更新 Kotlin 层

**文件：** `app/src/main/java/.../StageActivityV2.kt`

```kotlin
// 新增：OhmUrl 构建
private fun buildOhmUrl(info: HapModuleInfo): String {
    val entryPath = info.srcEntry
        .removePrefix("./")
        .removeSuffix(".ets")
    return "@bundle:${info.bundleName}/${info.moduleName}/$entryPath"
}

// 修改：tryInitRuntime — 使用新流程
private fun tryInitRuntime(surface: Surface) {
    // ...
    val ohmUrl = buildOhmUrl(parseModuleJson(extractedDir))

    nativeState = nativeInit(assets, filesDir.absolutePath)
    nativeSetSurface(nativeState, surface)

    val result = nativeRunPage(nativeState,
        bundleName, moduleName, abcFile.absolutePath, ohmUrl)
    // ...
}
```

---

## Phase 7: OhmUrl 入口点构建

### 7.1 解析 module.json

```kotlin
data class HapModuleInfo(
    val bundleName: String,
    val moduleName: String,
    val compileMode: String,
    val srcEntry: String,        // "./ets/entryability/EntryAbility.ets"
    val mainAbility: String,
)

fun parseModuleJson(dir: File): HapModuleInfo {
    val json = JSONObject(File(dir, "module.json").readText())
    val app = json.getJSONObject("app")
    val module = json.getJSONObject("module")
    return HapModuleInfo(
        bundleName = app.getString("bundleName"),
        moduleName = module.getString("name"),
        compileMode = module.getString("compileMode"),
        srcEntry = module.getString("srcEntry"),
        mainAbility = module.getString("mainElement"),
    )
}
```

### 7.2 OhmUrl 构建规则

参考 `jsi_declarative_engine.cpp:BuildOhmUrl`:
```
@bundle:{bundleName}/{moduleName}/ets/{pagePath}
```

示例：
```
srcEntry: "./ets/entryability/EntryAbility.ets"
去掉 "./" → "ets/entryability/EntryAbility.ets"
去掉 ".ets" → "ets/entryability/EntryAbility"
OhmUrl: "@bundle:app.hackeris.harmonyexample/entry/ets/entryability/EntryAbility"
```

### 7.3 验证

**任务：**
- [ ] 确认 HAP 的 `module.json` 中 `srcEntry` 实际值
- [ ] 构建 OhmUrl 并输出到 logcat
- [ ] 如果 OhmUrl 解析失败，查阅 `ModulePathHelper::CheckAndGetRecordName` 的转换规则

---

## Phase 8: NAPI 桥接 — 处理 @ohos:* 和 @system:* 导入

### 8.1 分析 HAP 的外部依赖

**任务：**
- [ ] 用 `panda_file_dumper` 或程序化方式枚举 `modules.abc` 中所有 `EXTERNAL` 类
- [ ] 分类：标准库（etsstdlib 提供）vs @ohos:* vs @system:* vs @native:*

**预期需要的模块：**

| 模块 | 用途 | 实现难度 |
|------|------|---------|
| `@ohos:app.ability.UIAbility` | EntryAbility 继承 | 中 |
| `@ohos:hilog` | 日志输出 | 低 |
| `@ohos:router` | 页面跳转 | 中 |
| `@ohos:application.*` | BackupExtension | 低 |

### 8.2 实现 requireNapi 框架

```cpp
// napi_bridge_registry.cpp
// 全局注册表：module_name → init_function
static std::map<std::string, HongNativeModuleInit> g_napiRegistry;

void hongengine_napi_register(const char* name, HongNativeModuleInit init) {
    g_napiRegistry[name] = init;
}

// requireNapi 回调（注入到 JS 全局环境）
// 由 JSNApi 在模块解析时调用
static void* AndroidRequireNapi(const char* module_name) {
    auto it = g_napiRegistry.find(module_name);
    if (it == g_napiRegistry.end()) {
        LOG(WARNING, RUNTIME) << "requireNapi: not found: " << module_name;
        return nullptr;  // 返回空，让模块继续加载
    }
    return it->second;
}
```

### 8.3 实现 @ohos:hilog stub

```cpp
// napi_bridge_hilog.cpp
// 桥接到 Android __android_log_print

static void HilogInfo(uint32_t domain, const char* tag, const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, tag, "[dom:%u] %s", domain, msg);
}

static void* HilogModuleInit(void* engine, void* exports) {
    // 注册 info, debug, error, warn 方法
    ExportFunction(exports, "info", HilogInfo);
    ExportFunction(exports, "debug", HilogDebug);
    ExportFunction(exports, "error", HilogError);
    ExportFunction(exports, "warn", HilogWarn);
    return exports;
}
```

### 8.4 实现 @ohos:app.ability.UIAbility stub

```cpp
// napi_bridge_ability.cpp
// UIAbility 基类 — EntryAbility 需要继承它

static void* UIAbilityModuleInit(void* engine, void* exports) {
    auto abilityClass = DefineClass(exports, "UIAbility");
    DefineVirtual(abilityClass, "onCreate",     [](void* self, void* want, void* param) {});
    DefineVirtual(abilityClass, "onDestroy",    [](void* self) {});
    DefineVirtual(abilityClass, "onForeground", [](void* self) {});
    DefineVirtual(abilityClass, "onBackground", [](void* self) {});
    DefineVirtual(abilityClass, "onWindowStageCreate", [](void* self, void* stage) {});
    DefineVirtual(abilityClass, "onWindowStageDestroy", [](void* self) {});
    return exports;
}
```

### 8.5 文件结构

```
app/src/main/cpp/napi_bridge/
├── napi_bridge_registry.cpp   # requireNapi 框架 + 注册表
├── napi_bridge_registry.h     # 公共接口
├── napi_bridge_hilog.cpp      # @ohos:hilog
├── napi_bridge_ability.cpp    # @ohos:app.ability.UIAbility
├── napi_bridge_router.cpp     # @ohos:router
├── napi_bridge_app.cpp        # @ohos:application.*
└── napi_bridge_common.cpp     # 公共工具 (DefineClass, ExportFunction)
```

---

## Phase 9: 端到端验证

### 9.1 验证步骤

```bash
# 1. 启动 (带完整参数)
adb shell am start -n app.hackeris.hoa/.runtime.StageActivityV2 \
  --es BUNDLE_NAME app.hackeris.harmonyexample \
  --es MODULE_NAME entry \
  --es ABILITY_NAME EntryAbility

# 2. 观察日志
adb logcat -c && adb logcat -s HOA.StageV2:* HOA.Runtime:* ArkEtsVm:*
```

### 9.2 阶段日志期望

**Phase 5 (构建验证):**
```
HOA.Runtime: All 14 native libraries loaded OK (新增 libark_jsruntime.so)
```

**Phase 6 (VM创建):**
```
ArkEtsVm: JSNApi::CreateJSVM success
ArkEtsVm: SetBundle: isBundle=false
HOA.Runtime: JSVM created OK
```

**Phase 7 (OhmUrl模块加载):**
```
HOA.Runtime: OhmUrl: @bundle:app.hackeris.harmonyexample/entry/ets/entryability/EntryAbility
ArkEtsVm: ExecuteSecureWithOhmUrl: resolving entry record
ArkEtsVm: SourceTextModule::Instantiate: dependencies resolved
ArkEtsVm: SourceTextModule::Evaluate: begin
```

**Phase 8 (NAPI桥接):**
```
ArkEtsVm: requireNapi: @ohos:hilog → stub (logcat bridge)
ArkEtsVm: requireNapi: @ohos:app.ability.UIAbility → stub (ability class)
```

**Phase 9 (最终期望):**
```
ArkEtsVm: EntryAbility::onCreate called
ArkEtsVm: Module init completed successfully
HOA.StageV2: HAP entry ability launch SUCCESS!
```

---

## 可能的风险和缓解

| 风险 | 概率 | 缓解 |
|------|------|------|
| `arkcompiler_ets_runtime` 依赖 `libuv` 等外部库，交叉编译困难 | 中 | 最小化编译目标，先只编译 napi + module + jspandafile |
| `JSNApi::ExecuteSecureWithOhmUrl` 需要完整的 JS 全局环境 | 中 | 如果太复杂，改用 `ExecuteModuleBufferSecure` |
| NAPI 桥接的 DefineClass/ExportFunction 需要运行时类型系统 | 中 | 从最简单的空导出对象开始，逐步增加 |
| 合并ABC入口记录名与 OhmUrl 不匹配 | 低 | 用程序枚举 ABC 内部记录名，验证匹配 |
| quickened 标记不一致再次出现 | 中 | 在 JSNApi 层由 LoadAotFile 统一处理，不需要手动管理 |
| `arkcompiler_ets_runtime` 中包含 ohos/ 平台特定代码 | 高 | 用 `#ifdef PANDA_TARGET_OHOS` 隔离，Android 路径用 stub 替代 |
