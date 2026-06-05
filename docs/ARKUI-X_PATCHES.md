# ArkUI-X weekly_20260518 移植 Patch 记录

> 源码根目录: arkui-x 源码树（repo 管理，含多个独立 git 仓库）
>
> 目标: 使 ArkUI-X 的 Android 运行时能够加载并执行 OHOS 原生格式的 HAP

## 涉及仓库总览

| 仓库 | 上游 | 已提交 (cherry-pick) | 新增 (hoa-weekly) |
|------|------|---------------------|-------------------|
| `arkcompiler/ets_runtime` | `openharmony/arkcompiler_ets_runtime` | 2 | 1 |
| `foundation/appframework` | `arkui-x/app_framework` | 2 | 4 |
| `foundation/arkui/ace_engine/adapter/android` | `arkui-x/arkui_for_android` | 3 | 7 |
| `foundation/arkui/napi` | `openharmony/arkui_napi` | 2 | 0 |
| `build` | `openharmony/build` | 1 | 0 |
| `plugins` | `arkui-x/plugins` | 0 | 4 |
| `build_plugins` | `arkui-x/build_plugins` | 0 | 0 |

---

## 1. arkcompiler/ets_runtime（ETS 运行时 VM）

### 已提交 (cherry-pick from hoa)

#### 1.1 `f91786229` — Read OHOS_HAP_MODE env var to set VM flag

OHOS 和 ArkUI-X 编译出的 ABC 文件 record 名格式不同，需要注入 VM 标志位以区分。

- `ecma_vm.h` — 新增标志位 `isOhosHapMode_` 及访问方法
- `jsnapi_expo.cpp/.h` — 新增 `SetOhosHapMode` / `IsOhosHapMode` 全局 API
- `module.cpp` — `GetOutEntryPoint` 在 OHOS 模式下插入 `src/main/` 并用 `&` 包裹输出
- `module_path_helper.cpp` — `ParseAbcPathAndOhmUrl` / `ParseUrl` 新增 OHOS 分支
- `js_pandafile_executor.cpp` — 跳过 `AdaptOldIsaRecord`

#### 1.2 `b70448e99` — fix(ets_runtime): 通过 ABC record 名检测区分 OHOS SDK 5.0/6.0 格式

SDK 5.0 和 6.0 的 ABC record 名格式不同。SDK 6.0 使用 `bundleName/entry/ets/...`（无 `&` 分隔符，无 `src/main/` 段），但 `IsNormalizedOhmUrlPack()` 对两者都返回 true，导致 SDK 6.0 HAP 被错误地按 SDK 5.0 格式处理。

**策略**: 首次加载 ABC 时检测 record 名实际格式（SDK 5.0 以 `&` 开头），设置 `isOhosSdk6Format_` 标志位，之后所有路径据此选择正确路径。

- `ecma_vm.h` — 新增 `isOhosSdk6Format_` 标志位
- `js_pandafile.h` — 新增 `HasOhmUrlRecordFormat()` 检测方法
- `module.cpp` — `GetOutEntryPoint` 条件收窄为 `IsNormalizedOhmUrlPack() && !IsOhosSdk6Format()`
- `module_path_helper.cpp` — `ParseAbcPathAndOhmUrl` 同理收窄
- `js_pandafile_executor.cpp` — `ExecuteModuleBuffer` 格式检测 + 一次性 bootstrap retry

### 新增 (hoa-weekly)

#### 1.3 `16a93345c` — fix(ets_runtime): 将 HDS HSP record 引用重定向到 NAPI stub 模块

HAP 字节码包含形如 `com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/HdsActionBar` 的直接 record 引用。Android 端无真实 HDS HSP，在 `ReplaceModuleThroughFeature` 中将此类引用统一路由到 `@hms:hds.hdsBaseComponent`，由 HDS 桩插件提供替代实现。

- `module_resolver.cpp` — `ReplaceModuleThroughFeature()` 末尾新增 `com.huawei.hmos.hdscomponent` 前缀匹配，重定向到 `@hms:hds.hdsBaseComponent`

---

## 2. foundation/appframework（应用框架）

### 已提交 (cherry-pick from hoa)

#### 2.1 `670f516` — Read OHOS_HAP_MODE env var to set VM flag

- `js_runtime.cpp` — `ArkJsRuntime::Initialize` 中读取 `OHOS_HAP_MODE` 环境变量，调用 `SetOhosHapMode(vm_, true)`

#### 2.2 `bcc6b5b` — 将 abilityInfo->hapPath 前缀从 arkui-x 改为 hap

- `ability_stage.cpp` — `abilityInfo->hapPath = "hap/" + abilityInfo->moduleName + "/";`
- `app_main.cpp` — 配套将路径前缀改为 `hap/`

### 新增 (hoa-weekly)

#### 2.3 `app_main.cpp` — 动态模块路径使用 GetSplicingModuleName

HOA 将 HAP 解压到 `filesDir/hap/<bundleName>.<moduleName>/`，原代码直接用未拼接的 `moduleName`（如 `entry`）构造路径，导致 `module.json` 找不到。

```cpp
// 原: auto jsonFile = GetAppDataModuleDir() + '/' + moduleName + "/module.json";
// 改:
std::string fullModuleName = GetSplicingModuleName(moduleName);
auto jsonFile = GetAppDataModuleDir() + '/' + fullModuleName + "/module.json";
```

#### 2.4 `bundle_constants.h` — MAX_MODULE_NAME 31 → 255

HOA 中 `SplicingModuleName()` 将 `entry` 拼接为 `app.hackeris.harmonyexample.entry`（33 字符），超过旧上限 31。
255 是 `uint8_t` 最大值，可容纳任意合法 bundleName。

#### 2.5 `module_profile.cpp` — 校验日志 + TransformTo 拼接行为恢复

1. `CheckBundleNameIsValid()` / `CheckModuleNameIsValid()` — 每个失败分支增加 HOA-DEBUG 日志
2. `ToInnerBundleInfo()` — 增加校验前的 bundleName/moduleName 日志
3. `TransformTo()` — 恢复原始拼接行为：`SplicingModuleName` 成功后 `module.name = fullModuleName`、`module.packageName = fullModuleName`

#### 2.6 `rs_system_properties.cpp` — GetRSClientMultiInstanceEnabled() → true

开启 RS 客户端多实例模式，使每个 HAP 窗口拥有独立的渲染上下文。不开启则渲染管线无法正常工作。

---

## 3. foundation/arkui/ace_engine/adapter/android（Android 适配器）

### 已提交 (cherry-pick from hoa)

#### 3.1 `6087ae2` — Add SetOhosHapMode JNI bridge and Java API

- `stage_application_delegate_jni.cpp/.h` — JNI 实现 `SetOhosHapMode`，通过 `setenv` 设置 `OHOS_HAP_MODE` 环境变量
- `StageApplication.java` — 公开静态方法 `setOhosHapMode(boolean)`
- `StageApplicationDelegate.java` — `nativeSetOhosHapMode` native 声明

#### 3.2 `6025238` — stage_asset_provider: 修复 OHOS HAP 模式下 resources.index 路径不匹配

- `stage_asset_provider.cpp` — `GetResIndexPath()` 使用 `GetSplicingModuleName()` 构造 resources.index 路径

#### 3.3 `a407655` — 将系统资源目录名从 arkui-x 重命名为 sys

系统资源 assets 目录从 `arkui-x/` 改为 `sys/`，与 HOA 项目中的 assets 结构一致。

### 新增 (hoa-weekly)

#### 3.4 `virtual_rs_window.cpp` — 创建 RSUIDirector 并使用 RSUIContext（白屏修复，核心）

原代码直接 `RSSurfaceNode::Create(config)` 创建 surface node，没有 RSUIContext，渲染管线无法工作。
ArkUI-X 原生流程中 RSUIDirector 由外部创建注入；HOA 的 HAP 宿主流程中没有这个注入点。

```cpp
// CreateSurfaceNode() 中:
rsUIDirector_ = Rosen::RSUIDirector::Create();       // 6.1 无参构造
if (!rsUIDirector_) { LOGE(...); return; }
surfaceNode_ = RSSurfaceNode::Create(config, true,
    rsUIDirector_->GetRSUIContext());

// 新增 GetRSUIContext() 实现:
auto rsUIContext = rsUIDirector_ ? rsUIDirector_->GetRSUIContext() : nullptr;
return rsUIContext;
```

新增 `#include` for `rs_ui_context.h` 和 `rs_ui_director.h`。

**6.1 适配点**: `RSUIDirector::Create()` 在 6.1 中为无参函数（旧版传入 `nullptr`）。

#### 3.5 `virtual_rs_window.h` — GetRSUIDirector/GetRSUIContext 返回实际对象

- `GetRSUIDirector()` 返回 `rsUIDirector_`（原返回 `nullptr`）
- `GetRSUIContext()` 改为声明（原返回 `nullptr`），实现在 .cpp 中
- 新增成员 `std::shared_ptr<RSUIDirector> rsUIDirector_ = nullptr`
- `surfaceNode_`、`context_`、`notifyNativefunc_` 显式初始化为 `nullptr`

#### 3.6 `osal/system_properties.cpp` — GetMultiInstanceEnabled() → true

`SystemProperties::GetMultiInstanceEnabled()` 返回 `true`（原返回 `false`）。

配合 2.6 的 `GetRSClientMultiInstanceEnabled()` → `true`，共同开启 RS 多实例渲染模式。

#### 3.7 `StageActivity.java` — setInstanceName 拼接

HOA 解压 HAP 到 `filesDir/hap/<bundleName>.<moduleName>/`，InstanceName 格式为 `bundleName:moduleName:abilityName:instanceId`。需要将 moduleName 从 `entry` 拼接为 `app.hackeris.harmonyexample.entry`，与 C++ 侧 key 一致。

```java
String fullModuleName = bundleName + "." + moduleName;
String modulePath = ARKUI_X_DIR + "/" + fullModuleName;
if (isExistDir(modulePath) && nameArray.length >= 4) {
    moduleName = fullModuleName;
    instanceName = nameArray[0] + ":" + fullModuleName + ":" + nameArray[2] + ":" + nameArray[3];
}
```

#### 3.8 `StageApplicationDelegate.java` — STUB_COPY_BUFFER_SIZE 常量

新增 `private static final int STUB_COPY_BUFFER_SIZE = 8192;`。

#### 3.9 `AceWeb.java` — WebView 加载 HAP 资源 ERR_ACCESS_DENIED 修复（2026-05-22）

Chrome WebView 118+ 在导航层直接拦截 `file://` URL，不调用 `shouldInterceptRequest`，`setAllowFileAccess(true)` 无效。ArkUI-X 的 `GetRawFileUrl()` 返回裸路径 `/data/user/0/.../rawfile/xxx.html`，Android WebView 内部自动补 `file://` 前缀后被拦截。

**修复策略**: 将 `file://` 和裸 `/data/` 路径改写为 `http://hoa.internal/` 虚拟主机 URL，在 `shouldInterceptRequest` 中拦截并提供文件内容。

- `rewriteFileUrl()` — 三个 `loadUrl` 入口处转换 URL scheme
- `handleFileRequest()` — 识别 `hoa.internal` 主机，通过 `FileInputStream` 返回 `WebResourceResponse`
- `guessMimeType()` — 根据扩展名判断 MIME 类型
- 恢复 `setAllowFileAccess(true)` / `setAllowContentAccess(true)`（对子资源加载仍有帮助）

#### 3.10 `AceWebPluginAosp.java` — Web 组件 `src` 属性 `resource://rawfile/` URL 解析（2026-05-23）

Web 组件的 `src` 属性设置声明式路径（`Web({ src: "resource://rawfile/..." })`）与程序化 `controller.loadUrl()` 走两套独立的代码路径。`loadUrl()` 路径在 `js_web_webview.cpp` 的 NAPI 层处理 rawfile 转换，但声明式路径经过 appframework 跨平台 resource register → Java `AceWebPluginAosp.create()` → `toAndroidAssetUrl()`，后者无法识别 `resource://rawfile/` scheme，导致 Android WebView 报 `ERR_UNKNOWN_URL_SCHEME`。

**修复策略**: 在 `toAndroidAssetUrl()` 中新增 `resource://rawfile/` 前缀检测，扫描 `files/hap/` 下各 HAP 的 `resources/rawfile/` 子目录定位实际文件，转换为 `file://` URL 后交给 `rewriteFileUrl()` / `handleFileRequest()` 处理。

- `toAndroidAssetUrl()` — 新增 `resource://rawfile/` → `file://` 转换分支
- `resolveRawfileUrl()` — 新增方法，在 `files/hap/*/resources/rawfile/` 下扫描匹配文件
- 新增 `import java.io.File`，新增常量 `RESOURCE_RAWFILE_PREFIX`

已修复 HAP: Legado（`com.legadoTeam.app`）

---

## 4. foundation/arkui/napi（NAPI 模块加载）

### 已提交 (cherry-pick from hoa)

#### 4.1 `81238fd7` — fix(arkui_napi): 修复 Android 平台 NAPI 模块 .abc 路径加载失败

修复 NAPI 模块在 Android 平台上加载 `.abc` 文件时的路径构造逻辑。

#### 4.2 `942fa0f1` — 将 ABC 路径标记从 /files/arkui-x 改为 /files/sys

配合 3.3，将 native NAPI 模块的 ABC 路径从 `/files/arkui-x` 改为 `/files/sys`。

---

## 5. build（GN 构建系统）

### 已提交 (cherry-pick from hoa)

#### 5.1 `3ec47ab1` — 支持在 is_arkui_x=true 条件下能正常跑通 Android 交叉编译

`templates/cxx/cxx.gni`：
1. 当 `is_arkui_x=true` 时清空 `external_deps`（ArkUI-X 不走 OHOS bundles 依赖系统）
2. 移除 `arm64e` 双架构编译、PAC 分支保护（Android NDK clang 无对应插件）
3. 移除 test/notice 构建流程（Android 构建不需要）

---

## 6. plugins（ArkUI-X 插件库）

### 新增 (hoa-weekly)

#### 6.1 HMS HDS Base Component Stub（`plugins/hms/hds/`）— 2026-05-25 更新

hw_base_calendar HAP (`com.hw.base_calendar`) 导入 `@hms:hds.hdsBaseComponent`，ArkUI-X 不支持 HMS 模块解析链路，导致 `SyntaxError` → 白屏。

HDS（Huawei Design System）是 HMS SDK 的 UI 组件库（`@kit.UIDesignKit`, API 18/5.1.0），提供 `HdsNavigation`、`HdsNavDestination`、`HdsActionBar` 等对标准 ArkUI 组件的设计规范包装。ArkUI-X 没有 HMS `system_kits_config.json` 配置、没有 `SetHmsModuleList` 注册、没有 HDS ABC 实现——整条链路不存在。

**策略**: 嵌入式 ABC mock（ABC-only 模式，参考 popup 插件）+ module_resolver 重定向，覆盖 ES import 和 HSP record 双路径。

**实现方式**:
- JS mock 源码 (`src/hds_component_mock.js`) 提供 ViewV2 组合实现的 HdsActionBar（Row + Button + Image）、ActionBarButton/ActionBarStyle 数据类、组件委托（Navigation/NavDestination/Tabs/ListItem）、枚举、stub 函数
- 三步 ABC 构建流水线：`es2abc --module` → `llvm-objcopy` 嵌入 → `ohos_source_set` 链接进 `libhms_hds.so`
- C++ stub 简化为 ABC-only 模式（`nm_register_func = nullptr`），通过 `napi_module_with_js_register` 同时注册 `hds.hdsBaseComponent` 和 `UIDesignKit` 两个模块名
- 配合 `arkcompiler/ets_runtime` 中 `ReplaceModuleThroughFeature` 的 HDS HSP record 重定向（commit `16a93345c`）

**组件委托状态**:

| 导出 | 委托至 | 状态 |
|------|--------|------|
| `HdsActionBar` | ViewPU 组合（Row + Button + Image） | 已实现（二级：组合实现） |
| `ActionBarButton` | JS class（属性容器） | 已实现 |
| `ActionBarStyle` | JS class（属性容器） | 已实现 |
| `HdsNavigation` | global `Navigation` | 已实现（一级：委托） |
| `HdsNavDestination` | global `NavDestination` | 已实现 |
| `HdsTabs` | global `Tabs` | 已实现 |
| `HdsListItemCard` | global `ListItem` | 已实现 |
| `HdsListItem` | global `ListItem` | 已实现 |
| `PrefixImage` | global `Image` | 已实现 |
| `SuffixButton` | global `Button` | 已实现 |
| `SuffixArrowIconText` | global `Row` | 已实现 |
| `ScrollEffectType` | 枚举 `{ COMMON_BLUR: 0 }` | 已实现 |
| `HdsNavigationTitleMode` | 枚举 `{ FREE: 0, FULL: 1, MINI: 2 }` | 已实现 |
| `DividerMode` | 枚举 `{ AUTO: 0, ALWAYS: 1, NONE: 2 }` | 已实现 |
| `HdsNavDestinationTitleMode` | 枚举 `{ FREE: 0, FULL: 1, MINI: 2 }` | 已实现 |
| Instance/Attribute 类 (8个) | `undefined` 桩函数 | 已实现 |
| `HdsTabsController` | `undefined` 桩函数 | 已实现 |

**关键技术点**: `export default { ... }` 是 ABC-only 模式的必需——缺失导致 `GetExportObjectFromBuffer("default")` 查找失败，所有 named export 不可见。

**调用方 (HOA 项目)**: 在 `HoaApplication.kt` 的 `initArkUIX()` 中：

```kotlin
if (isHapProcess) {
    System.loadLibrary("hms_hds")
}
```

#### 6.2 `js_web_webview.cpp` — `LoadUrl()` 新增 `resource://rawfile/` 转换（2026-05-23）

`NapiWebviewController::LoadUrl()`（程序化 `controller.loadUrl()` 路径）参照已有的 `PostUrl()` 逻辑，新增 `resource://rawfile/` 前缀检测，调用 `GetRawFileUrl()` 将 URL 转换为 `file://` 路径。

此修复覆盖 ETS 代码中显式调用 `controller.loadUrl("resource://rawfile/...")` 的场景（声明式 `src` 属性路径的对应修复见 3.10）。

#### 6.3 `js_web_webview.cpp` — `InitAppModule` 设置 HAP 路径

通过 JNI 接收 Java `StageApplication` 传入的 HAP 模块路径，存储为静态变量 `appHapPath_`，供 `GetRawFileUrl()` 使用。

---

## 7. build_plugins（SDK 构建描述）

### 新增 (hoa-weekly)

#### 7.1 `arkui_cross_sdk_description_std.json` — 新增 libhms_hds.so

在 SDK 描述文件中新增 `libhms_hds.so` 插件条目，使构建系统在打包时将 HDS stub 库包含到产物中。

---

## 标志传递链

```
HoaApplication.kt
  → StageApplication.setOhosHapMode(true)       [仓库 3, 已提交 3.1]
    → JNI nativeSetOhosHapMode                  [仓库 3, 已提交 3.1]
      → setenv("OHOS_HAP_MODE", "1")            [仓库 3, 已提交 3.1]
        → js_runtime.cpp 读取环境变量           [仓库 2, 已提交 2.1]
          → JSNApi::SetOhosHapMode(vm_, true)   [仓库 1, 已提交 1.1]
            → 各模块通过 IsOhosHapMode() 判断   [仓库 1]
```

---

## 调用方 (HOA 项目)

在 `HoaApplication.kt` 中：

```kotlin
override fun onCreate() {
    super.onCreate()       // StageApplication.onCreate → 加载 native 库
    setOhosHapMode(true)   // 激活 OHOS 模式标志链
}
```

测试入口已独立到 `DevTestActivity`，与生产主流程 `MainActivity` 分离。

---

## 新增变更明细

以下变更已提交到 hoa-weekly（非 cherry-pick，直接创建的新 patch）：

### arkcompiler/ets_runtime
| 文件 | 变更 |
|------|------|
| `ecmascript/module/module_resolver.cpp` | ReplaceModuleThroughFeature 新增 HDS HSP record 重定向 |

### foundation/appframework
| 文件 | 变更 |
|------|------|
| `app_main.cpp` | GetSplicingModuleName 动态模块路径 |
| `bundle_constants.h` | MAX_MODULE_NAME 31 → 255 |
| `module_profile.cpp` | TransformTo 拼接行为恢复 |
| `rs_system_properties.cpp` | GetRSClientMultiInstanceEnabled() → true |

### foundation/arkui/ace_engine/adapter/android
| 文件 | 变更 |
|------|------|
| `virtual_rs_window.cpp` | RSUIDirector + GetRSUIContext 实现（白屏修复核心） |
| `virtual_rs_window.h` | GetRSUIDirector/GetRSUIContext 返回实际对象 |
| `osal/system_properties.cpp` | GetMultiInstanceEnabled() → true |
| `StageActivity.java` | setInstanceName 拼接逻辑 |
| `StageApplicationDelegate.java` | STUB_COPY_BUFFER_SIZE 常量 |
| `AceWeb.java` | WebView HAP 资源加载 shouldInterceptRequest 拦截 |
| `AceWebPluginAosp.java` | Web 组件 src 属性 resource://rawfile/ URL 解析 |

### plugins
| 文件 | 变更 |
|------|------|
| `hms/hds/BUILD.gn` | HDS stub 三步 ABC 构建流水线（es2abc + gen_js_obj + source_set） |
| `hms/hds/hds_base_component_stub.cpp` | ABC-only 模式注册（nm_register_func = nullptr），双模块名 |
| `hms/hds/src/hds_component_mock.js` | ViewPU 组合 HdsActionBar + 所有 HDS 导出 |
| `plugin_lib.gni` | common_plugin_libs 新增 `"hms/hds"` |
| `web/webview/js_web_webview.cpp` | LoadUrl() 新增 resource://rawfile 转换 |

### build_plugins
| 文件 | 变更 |
|------|------|
| `sdk/arkui_cross_sdk_description_std.json` | 新增 libhms_hds.so 描述 |

---

## SDK 6.0 Record 名格式兼容

### 背景

OHOS SDK 5.0 和 6.0 的 ABC record 名格式不同：

| 维度 | SDK 5.0 | SDK 6.0 |
|------|---------|---------|
| bundleName 前缀 | 无 | 有 (`liubai.yuedu.hos/entry/ets/...`) |
| src/main/ 路径段 | 有 (`entry/src/main/ets/...`) | 无 (`entry/ets/...`) |
| 归一化 URL 分隔符 | `&` 包裹 (`&...&`) | 无 |
| 模块引用前缀 | `@normalized:` | `@bundle:` |

SDK 6.0 格式和 ArkUI-X 原生格式一致——唯一的区别是 `IsNormalizedOhmUrlPack()` 返回 true，错误地将控制流导向了 SDK 5.0 的 `&` 包裹路径。

**策略**: 首次加载 ABC 时检测 record 名实际格式（SDK 5.0 以 `&` 开头，SDK 6.0 以 bundleName 开头），设置 `isOhosSdk6Format_` 标志位。之后所有路径据此选择正确格式，无需多重 fallback。

已修复 HAP: 留白阅读（`liubai.yuedu.hos`）— ABC 版本 `12.0.6.0`，panda 版本 `0c.00.06.00`，模块引用前缀 `@bundle:`。
