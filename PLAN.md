# HOA: HarmonyOS on Android — 技术方案

> 在 Android 上直接运行鸿蒙 HAP 应用的兼容层，类似 Wine 的设计理念

## 一、可行性分析

### 1.1 核心结论：**可行，但需分层实现**

基于对 ArkUI-X 源码、OpenHarmony 源码和 Ark Compiler 的深度分析，在 Android 上直接运行 HAP 包在技术上具有可行性，但完整度取决于系统 API 兼容层的覆盖范围。

### 1.2 有利因素

| 组件 | 状态 | 来源 |
|------|------|------|
| Ark 字节码虚拟机 (Ark VM) | ✅ 已有 Android 移植 | ArkUI-X `arkcompiler/ets_runtime` |
| ArkUI 渲染引擎 | ✅ 已有 Android 适配 | ArkUI-X `ace_engine/adapter/android` |
| JNI 桥接层 (Java ↔ C++) | ✅ 完整实现 | ArkUI-X `StageActivityDelegate`/`StageApplicationDelegate` |
| Android Surface 渲染集成 | ✅ 已实现 | ArkUI-X `WindowViewSurface`/`WindowViewTexture` |
| Ark 字节码格式 (.abc) | ✅ 完全开放 | OpenHarmony `arkcompiler/runtime_core/docs/file_format.md` |
| 编译器前端 (es2panda) | ✅ 开源 | OpenHarmony `arkcompiler/ets_frontend` |
| NAPI 桥接 (Native ↔ JS) | ✅ 已有实现 | ArkUI-X `ets_runtime/ecmascript/napi` |
| Ability 框架 | ✅ 开源 | OpenHarmony `foundation/ability/ability_runtime` |
| Bundle Manager | ✅ 开源 | OpenHarmony `foundation/bundlemanager` |
| Window Manager | ✅ 开源 | OpenHarmony `foundation/window` |

### 1.3 核心挑战

| 挑战 | 难度 | 说明 |
|------|------|------|
| 系统服务 API 兼容 | 🔴 高 | HAP 使用 `@kit.*` 体系（AbilityKit、NetworkKit、MediaKit 等），需逐一适配到 Android 对应 API |
| HAP 中的原生 .so 库 | 🔴 高 | HAP 包内可能包含依赖 OpenHarmony 系统库的原生 .so，无法直接在 Android 上加载 |
| HarmonyOS 专有 API | 🟡 中 | 华为移动服务 (HMS) 等闭源 API 无可用实现 |
| 应用沙箱/权限模型差异 | 🟡 中 | OpenHarmony 的 AccessToken 权限体系与 Android 权限模型不同 |
| 系统服务 IPC 框架 | 🟡 中 | OpenHarmony 使用 IPCKit/Samgr，Android 使用 Binder，需映射 |

### 1.4 ArkUI-X 的关键启示

ArkUI-X 已经证明了 **ArkTS 应用可以在 Android 上运行**，但它的模式是"交叉编译"：

```
ArkUI-X 模式：源码 → 交叉编译 → Android APK（内嵌 .abc + .so）
HOA 目标模式：现成 .hap → 直接加载运行（无需重新编译）
```

ArkUI-X 提供了以下可直接复用的关键组件：
- `StageActivity` / `StageApplication`：Android Activity/Application 适配
- `libarkui_android.so`：ArkUI 渲染引擎 + Ark VM 的 Android 构建
- `arkui_android_adapter.jar`：Java 侧桥接层
- `StageAssetProvider`：资源加载机制
- 插件桥接框架 (`plugins/bridge/`)：跨平台通信

**关键差异**：ArkUI-X 通过 hvigor 构建系统将 `.abc` 打包进 APK 的 assets 中，由 `StageApplication` 在运行时提取。HOA 需要做到：给定一个 `.hap` 文件，动态解析、加载和执行。

---

## 二、整体架构

### 2.1 分层架构图

```
┌──────────────────────────────────────────────────────────────┐
│                    HAP Application                           │
│              (ArkTS/ArkUI 代码, .abc 字节码)                  │
├──────────────────────────────────────────────────────────────┤
│                     Ark VM Runtime                           │
│           (加载并执行 .abc 字节码，来自 ArkUI-X)                │
├──────────────────────────────────────────────────────────────┤
│                  ArkUI Rendering Engine                      │
│        (声明式 UI 渲染，布局/绘制/动画/事件，来自 ArkUI-X)       │
├──────────────────────────────────────────────────────────────┤
│               System API Compatibility Layer                 │
│  ┌────────────┬────────────┬────────────┬─────────────┐     │
│  │ @kit.      │ @kit.      │ @kit.      │ @kit.       │     │
│  │ AbilityKit │  ArkUI     │ NetworkKit │ MediaKit    │     │
│  │ →Activity  │ →ArkUI-X   │ →OkHttp    │ →ExoPlayer  │     │
│  └────────────┴────────────┴────────────┴─────────────┘     │
├──────────────────────────────────────────────────────────────┤
│              Platform Abstraction Layer                       │
│  ┌──────────┬───────────┬───────────┬──────────────────┐   │
│  │ HAP      │ Ability   │ Window    │  Resource        │   │
│  │ Bundle   │ Manager   │ Manager   │  Manager         │   │
│  │ Loader   │ Shim      │ Shim      │  Shim            │   │
│  └──────────┴───────────┴───────────┴──────────────────┘   │
│  ┌──────────┬───────────┬───────────┬──────────────────┐   │
│  │ Process  │ Permission│ IPC       │  System Property │   │
│  │ Manager  │ Shim      │ Bridge    │  Shim            │   │
│  └──────────┴───────────┴───────────┴──────────────────┘   │
├──────────────────────────────────────────────────────────────┤
│              Android Platform (ART + Native)                  │
│  ActivityManager  PackageManager  WindowManager  SurfaceFlinger │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 与 Wine 的类比

| Wine 概念 | HOA 对应 |
|-----------|---------|
| PE 加载器 (加载 .exe/.dll) | HAP Bundle Loader (解析 .hap, 加载 .abc) |
| Win32 API 兼容层 | @kit.* API 兼容层 |
| Wine Server (进程/窗口/IPC) | Platform Abstraction Layer |
| ntdll (系统调用翻译) | IPC Bridge (Samgr → Binder) |
| 虚拟桌面/窗口管理 | Window Manager Shim |
| 注册表模拟 | System Property Shim / Preferences |

---

## 三、核心模块设计

### 3.1 HAP Bundle Loader（HAP 包加载器）

**职责**：解析 HAP 文件格式，提取资源、字节码和配置，提供给运行时使用。

**HAP 文件格式**：
HAP 本质上是 ZIP 格式，内部结构：
```
app.hap
├── module.json              # 模块配置（ability 声明、权限、元数据）
├── pack.info                # 包信息
├── ets/                     # 编译产物
│   ├── modules.abc          # Ark 字节码（核心！）
│   ├── sourceMaps.map       # 源码映射
│   └── buildModules.abc     # 构建模块字节码
├── resources/               # 资源文件
│   ├── base/
│   │   ├── element/         # 字符串、颜色等
│   │   ├── media/           # 图片
│   │   └── profile/         # 配置
│   ├── en/                  # 英文资源
│   └── zh/                  # 中文资源
├── resources.index          # 资源索引
├── libs/                    # 原生 .so 库
│   ├── arm64-v8a/
│   └── armeabi-v7a/
└── resume.json              # 恢复信息
```

**实现方案**：
```kotlin
class HapBundleLoader {
    // 解析 HAP 文件
    fun parse(hapFile: File): HapBundle

    // 从 HAP 中提取 .abc 字节码
    fun extractBytecode(hapBundle: HapBundle): ByteArray

    // 解析 module.json 获取 Ability 信息
    fun parseModuleConfig(hapBundle: HapBundle): ModuleConfig

    // 加载资源索引
    fun loadResourceIndex(hapBundle: HapBundle): ResourceManager

    // 提取原生 .so 库（如果存在且可兼容）
    fun extractNativeLibs(hapBundle: HapBundle, abi: String): List<File>
}
```

**关键实现细节**：
- 使用 Java `ZipFile` API 解析 HAP
- `module.json` 解析后提取 `abilities` 列表、`extensionAbilities`、权限声明
- `.abc` 文件提取后通过 Ark VM 的 `libpandafile` API 加载
- 资源系统需实现 OpenHarmony 的 `$r('app.type.name')` 解析

### 3.2 Ability Manager Shim（Ability 管理适配层）

**职责**：将 OpenHarmony 的 Ability 生命周期映射到 Android Activity 生命周期。

**生命周期映射**：

| OpenHarmony UIAbility | Android Activity | 说明 |
|----------------------|------------------|------|
| onCreate(want, launchParam) | onCreate(savedInstanceState) | 创建 |
| onWindowStageCreate(windowStage) | onPostCreate() | 窗口创建 |
| onForeground() | onResume() | 前台 |
| onBackground() | onPause() | 后台 |
| onDestroy() | onDestroy() | 销毁 |
| onNewWant(want) | onNewIntent(intent) | 新请求 |

**Want ↔ Intent 映射**：
```
OpenHarmony Want                    Android Intent
├── elementName.bundleName    →     component.package
├── elementName.abilityName   →     component.className
├── action                    →     action
├── uri                       →     data (Uri)
├── parameters                →     extras (Bundle)
└── flags                     →     flags
```

**实现方案**：
```kotlin
class HoaAbilityManager {
    // 启动 Ability → 启动 Android Activity
    fun startAbility(want: OhWant): Int

    // 终止 Ability
    fun terminateAbility(abilityId: Int)

    // Ability 生命周期回调分发
    fun dispatchLifecycle(abilityId: Int, event: LifecycleEvent)

    // 连接 ServiceExtension → 绑定 Android Service
    fun connectAbility(want: OhWant, conn: IAbilityConnection): Int
}
```

**Activity 宿主设计**：
```kotlin
class HoaAbilityActivity : StageActivity() {
    private lateinit var hapBundle: HapBundle
    private lateinit var abilityInfo: AbilityInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        // 从 intent 获取 HAP 路径和 Ability 名称
        val hapPath = intent.getStringExtra("HAP_PATH")
        val abilityName = intent.getStringExtra("ABILITY_NAME")

        // 加载 HAP
        hapBundle = HapBundleLoader.parse(File(hapPath))
        abilityInfo = hapBundle.getAbility(abilityName)

        // 设置 ArkUI 实例名
        setInstanceName("${hapBundle.bundleName}:${hapBundle.moduleName}:${abilityName}")

        super.onCreate(savedInstanceState)
    }
}
```

### 3.3 Ark VM Runtime Integration（Ark VM 运行时集成）

**职责**：在 Android 进程中初始化并运行 Ark VM，加载和执行 HAP 中的 .abc 字节码。

**来源**：直接复用 ArkUI-X 的 `arkcompiler/ets_runtime`，构建为 Android .so。

**初始化流程**：
```
StageApplication.onCreate()
  ├── 加载 libarkui_android.so
  ├── 初始化 Ark VM
  │   ├── 创建 JSThread
  │   ├── 初始化 EcmaVM
  │   ├── 注册 NAPI 模块（系统 API 桥接）
  │   └── 设置 GC 参数
  ├── 初始化 ArkUI 引擎
  │   ├── 创建 PipelineContext
  │   ├── 注册组件工厂
  │   └── 设置渲染后端 (Skia/OHOS)
  └── 预加载 ETS 运行时模块
```

**HAP .abc 加载流程**：
```
HoaAbilityActivity.onCreate()
  ├── HapBundleLoader.extractBytecode() → modules.abc
  ├── StageAssetProvider 注入 HAP 资源路径
  ├── ArktsDynamicFrontend 加载 modules.abc
  │   ├── libpandafile 解析 .abc 二进制
  │   ├── ClassLinker 解析类和方法
  │   ├── 查找入口类 (EntryAbility)
  │   └── 解释器/JIT 执行字节码
  ├── ArkUI 组件树构建
  │   ├── 解析 @Entry/@Component 声明
  │   ├── 构建 RenderNode 树
  │   └── 执行首次布局
  └── 渲染到 Android SurfaceView
```

**NAPI 模块注册（关键适配点）**：
ArkTS 代码通过 NAPI 调用原生 C++ API。OpenHarmony 中每个 `@kit.*` 对应一组 NAPI 模块。HOA 需要为每个 kit 实现对应的 NAPI 模块，内部桥接到 Android API：

```cpp
// 示例：@kit.AbilityKit 的 NAPI 适配
napi_value AbilityKitModuleInit(napi_env env, napi_value exports) {
    // 将 OHOS 的 UIAbility API 映射到 HOA 的 Java Activity 适配
    napi_set_named_property(env, exports, "startAbility", NativeStartAbility);
    napi_set_named_property(env, exports, "terminateSelf", NativeTerminateSelf);
    // ...
    return exports;
}

// 桥接到 Android Activity
napi_value NativeStartAbility(napi_env env, napi_callback_info info) {
    // 解析 Want 参数
    // 通过 JNI 调用 Android startActivity
    // ...
}
```

### 3.4 System API Compatibility Layer（系统 API 兼容层）

**这是整个项目工作量最大、最关键的部分。**

**API Kit 覆盖优先级**：

#### P0 - 必须（应用无法启动/显示）

| Kit | 关键 API | Android 映射 | 复杂度 |
|-----|---------|-------------|--------|
| @kit.AbilityKit | UIAbility, Want, AbilityConstant | Activity, Intent | 中 |
| @kit.ArkUI | Component, @Entry, @State, build() | ArkUI-X 已实现 | 低（复用） |
| @kit.ArkUI (window) | WindowStage, window.create() | Android WindowManager | 中 |
| hilog | info/error/debug/warn | android.util.Log | 低 |

#### P1 - 重要（常见应用核心功能）

| Kit | 关键 API | Android 映射 | 复杂度 |
|-----|---------|-------------|--------|
| @kit.NetworkKit | http, socket | OkHttp / java.net | 中 |
| @kit.BasicServicesKit | Preferences, relationalStore | SharedPreferences, SQLite | 中 |
| @kit.ArkUI (router) | router.pushUrl, router.back | 自实现路由栈 | 中 |
| @kit.MediaKit | image, media | Android Bitmap, ExoPlayer | 高 |
| @kit.FileManagementKit | fs, fileIo | java.io, android.storage | 中 |

#### P2 - 增强（提升兼容率）

| Kit | 关键 API | Android 映射 | 复杂度 |
|-----|---------|-------------|--------|
| @kit.LocationKit | geoLocationManager | FusedLocationProvider | 中 |
| @kit.NotificationKit | notificationManager | NotificationManager | 中 |
| @kit.TelephonyKit | sim, call | TelephonyManager | 高 |
| @kit.CameraKit | camera | Camera2 API | 高 |
| @kit.SensorKit | sensor | Android SensorManager | 低 |

#### P3 - 可选（特殊场景）

| Kit | 说明 | 策略 |
|-----|------|------|
| @kit.SDKKit (HMS) | 华为闭源服务 | 不可实现，返回 stub |
| @kit.DriveKit | 鸿蒙分布式能力 | 不可实现，返回 stub |
| @kit.AIKit | 鸿蒙 AI 服务 | 部分可映射到 ML Kit |

### 3.5 Resource Manager Shim（资源管理适配层）

**职责**：解析 HAP 中的 `resources.index` 和资源文件，提供 `$r()` 和 `$rawfile()` 访问。

**OpenHarmony 资源系统**：
- `$r('app.string.app_name')` — 限定词资源引用
- `$rawfile('config.json')` — 原始文件引用
- 资源限定：语言、分辨率、方向、暗色模式等

**实现方案**：
```kotlin
class HoaResourceManager(
    private val hapBundle: HapBundle,
    private val context: Context
) {
    // 解析 resources.index 二进制格式
    fun parseResourceIndex(): ResourceTable

    // 解析 $r() 引用，返回实际资源
    fun getResource(resName: String, qualifiers: Qualifiers): Any

    // 获取 rawfile
    fun getRawFile(path: String): InputStream

    // 设备限定词匹配（语言、密度、暗色模式等）
    fun matchConfig(qualifiers: Qualifiers): ResourceConfig
}
```

`resources.index` 的二进制格式需从 OpenHarmony 的 `global/resource_management` 模块中提取解析逻辑。

### 3.6 IPC Bridge（进程间通信桥接）

**职责**：将 OpenHarmony 的 Samgr/IPCKit 调用映射到 Android Binder。

**OpenHarmony IPC 模型**：
- SystemAbility 注册到 Samgr
- 客户端通过 Samgr 获取代理 (Proxy)
- Proxy/Stub 通过 IPCKit (类似 Binder) 通信

**Android 映射**：
```
OpenHarmony                          Android
├── Samgr (SystemAbility Manager) →  ServiceManager / 自定义 Service
├── IPCKit (IPC框架)            →    Android Binder / AIDL
├── SystemAbility Proxy         →    Binder Proxy / AIDL Stub
└── Parcel (序列化)             →    Android Parcel
```

**实现策略**：
对于运行在单进程中的 HAP 应用，大部分系统服务不需要真正的 IPC，可以在本地进程中创建 shim 对象直接调用。只有少数需要跨进程的场景（如通知、位置服务）才需要真正的 Android Service 绑定。

### 3.7 Native Library Compatibility（原生库兼容）

**HAP 中的 .so 库是最棘手的兼容性问题。**

**问题分析**：
- HAP 中的 .so 可能链接了 OpenHarmony 系统库（如 `libhilog.so`, `libace.so`, `libbundle_framework.so`）
- 这些系统库在 Android 上不存在
- 直接 `dlopen` 会因未解析符号而失败

**解决方案（按优先级）**：

1. **提供 shim .so 库**：为常用的 OpenHarmony 系统库提供 Android 版本的 shim，内部转发到 Android 对应 API
   - `libhilog.so` → 封装 `__android_log_print`
   - `libace.so` → 封装 ArkUI-X 的 C++ API
   - `libnapi.so` → 封装 Ark VM NAPI

2. **符号重定向**：通过 `LD_PRELOAD` 或自定义 linker 拦截特定符号调用

3. **纯 ArkTS HAP 无此问题**：如果 HAP 只包含 .abc 字节码而没有原生 .so，则完全不需要此适配

---

## 四、项目结构

```
HOA/
├── app/                              # Android 应用壳
│   ├── src/main/
│   │   ├── java/app/hoa/
│   │   │   ├── HoaApplication.kt     # 继承 StageApplication
│   │   │   ├── HoaMainActivity.kt    # HAP 选择/管理主界面
│   │   │   ├── HoaAbilityActivity.kt # HAP Ability 宿主 Activity
│   │   │   ├── loader/
│   │   │   │   ├── HapBundleLoader.kt
│   │   │   │   └── HapBundle.kt
│   │   │   ├── manager/
│   │   │   │   ├── HoaAbilityManager.kt
│   │   │   │   ├── HoaBundleManager.kt
│   │   │   │   ├── HoaWindowManager.kt
│   │   │   │   └── HoaResourceManager.kt
│   │   │   ├── bridge/
│   │   │   │   ├── WantMapper.kt     # Want ↔ Intent 映射
│   │   │   │   └── IpcBridge.kt      # IPC 桥接
│   │   │   └── compat/               # Android API 兼容实现
│   │   │       ├── NetworkCompat.kt
│   │   │       ├── StorageCompat.kt
│   │   │       └── MediaCompat.kt
│   │   ├── jniLibs/                  # 预编译 ArkUI-X .so
│   │   │   ├── arm64-v8a/
│   │   │   │   ├── libarkui_android.so
│   │   │   │   └── ...
│   │   │   └── armeabi-v7a/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── native/                           # C++ 层
│   ├── CMakeLists.txt
│   ├── kit_shim/                     # @kit.* NAPI 模块实现
│   │   ├── ability_kit.cpp
│   │   ├── network_kit.cpp
│   │   ├── storage_kit.cpp
│   │   └── media_kit.cpp
│   ├── system_shim/                  # 系统服务 shim .so
│   │   ├── libhilog_shim.so
│   │   ├── libace_shim.so
│   │   └── libnapi_shim.so
│   └── jni/                          # JNI 桥接
│       ├── hoa_runtime_jni.cpp
│       └── hoa_asset_jni.cpp
├── build-arkui-x/                    # ArkUI-X Android 构建脚本
│   ├── build_arkvm.sh
│   ├── build_ace_engine.sh
│   └── patches/                      # 对 ArkUI-X 的补丁
└── docs/
    └── api_coverage.md               # API 兼容覆盖率跟踪
```

---

## 五、分阶段实施计划

### Phase 1: 最小可行原型 (MVP) — 4-6 周

**目标**：能在 Android 上加载一个纯 ArkTS 的 HAP，显示 Hello World 级别的 UI。

**范围**：
- [ ] HAP Bundle Loader：解析 HAP ZIP，提取 module.json、modules.abc、resources
- [ ] Ark VM 集成：从 ArkUI-X 构建 `libarkui_android.so`，在 Android 上初始化 Ark VM
- [ ] ArkUI 渲染集成：复用 ArkUI-X 的 SurfaceView 渲染管线
- [ ] HoaAbilityActivity：实现最小 Activity 宿主，加载 .abc 并执行
- [ ] @kit.ArkUI 基础兼容：Text、Column、Row、Button 等基础组件
- [ ] hilog 适配：映射到 android.util.Log
- [ ] 资源加载：基础 $r() 和 $rawfile() 支持

**验证标准**：加载 ArkUI-X Example 项目的 HAP，显示 "Hello World" 页面，点击可交互。

### Phase 2: 核心框架兼容 — 6-8 周

**目标**：支持大多数纯 ArkTS 应用的核心功能。

**范围**：
- [ ] Ability 生命周期完整映射（onCreate → onDestroy 全链路）
- [ ] router 导航系统（pushUrl / back / replaceUrl）
- [ ] @kit.AbilityKit 核心适配（UIAbility, Want, AbilityConstant）
- [ ] @kit.NetworkKit 适配（http 请求，基于 OkHttp）
- [ ] @kit.BasicServicesKit 适配（Preferences, relationalStore）
- [ ] @kit.ArkUI (window) 适配（WindowStage 窗口操作）
- [ ] resources.index 完整解析（限定词匹配、暗色模式、多语言）
- [ ] HAP 安装管理（多个 HAP 的安装/卸载/信息查询）

**验证标准**：能运行包含网络请求、数据存储、多页面路由的中等复杂度 ArkTS 应用。

### Phase 3: 系统 API 扩展 — 8-12 周

**目标**：覆盖大部分常用系统 API，提升应用兼容率。

**范围**：
- [ ] @kit.MediaKit 适配（图片编解码、媒体播放）
- [ ] @kit.FileManagementKit 适配（文件系统访问）
- [ ] @kit.LocationKit 适配
- [ ] @kit.NotificationKit 适配
- [ ] @kit.SensorKit 适配
- [ ] @kit.TelephonyKit 基础适配
- [ ] 原生 .so 库加载支持（shim .so 策略）
- [ ] ServiceExtension / DataShareExtension 支持

**验证标准**：能运行使用多媒体、位置、通知等系统 API 的应用。

### Phase 4: 生态兼容与优化 — 持续

**目标**：提升稳定性、性能和兼容率。

**范围**：
- [ ] 更多 @kit.* 适配（CameraKit, AIKit 等）
- [ ] 性能优化（Ark VM JIT 调优、渲染管线优化）
- [ ] HAP 兼容性测试套件
- [ ] 原生 .so 库兼容性改进
- [ ] HAP 应用签名/权限处理
- [ ] 多窗口 / 分屏支持
- [ ] 开发者工具（调试器、日志查看器）

---

## 六、关键技术路径

### 6.1 从 ArkUI-X 构建 Android 运行时

ArkUI-X 源码 (`/src/arkui-x/`) 已经包含 Android 构建支持。构建步骤：

```bash
# 1. 构建 Ark VM (ets_runtime)
cd /src/arkui-x/arkcompiler/ets_runtime
# 使用 GN/Ninja 构建 Android target
gn gen out/android --args='target_os="android" target_cpu="arm64"'
ninja -C out/android

# 2. 构建 ACE Engine (arkui 渲染引擎)
cd /src/arkui-x/foundation/arkui/ace_engine
gn gen out/android --args='target_os="android" target_cpu="arm64"'
ninja -C out/android

# 3. 收集产物
# libarkui_android.so, arkui_android_adapter.jar 等
```

### 6.2 HAP 加载 vs APK 打包的关键差异

ArkUI-X 将 .abc 嵌入 APK assets，由 `StageApplication` 在 `onCreate` 中复制到 data 目录后加载。

HOA 需要支持运行时动态加载任意 HAP：

```
ArkUI-X 路径:
  APK assets/ → StageApplication.copyAssets() → /data/app/.../ → ArktsFrontend 加载

HOA 路径:
  .hap 文件 → HapBundleLoader 解析 → 内存/临时目录 → ArktsDynamicFrontend 动态加载
```

需要修改 ArkUI-X 的 `StageAssetProvider`，使其支持从 HAP 包直接加载资源，而非仅从 APK assets 加载。

### 6.3 NAPI 模块注册机制

OpenHarmony 中，每个 Kit 的 NAPI 模块在系统启动时注册到 Ark VM。HOA 需要在 Android 侧实现相同的注册机制：

```cpp
// 在 libarkui_android.so 初始化时注册所有 shim 模块
void RegisterHoaNapiModules(napi_env env) {
    // 替代 OpenHarmony 系统服务提供的 NAPI 模块
    RegisterAbilityKit(env);     // @kit.AbilityKit
    RegisterNetworkKit(env);     // @kit.NetworkKit
    RegisterBasicServicesKit(env); // @kit.BasicServicesKit
    RegisterMediaKit(env);       // @kit.MediaKit
    // ...
}
```

每个 shim 模块需实现对应的 NAPI 接口，内部通过 JNI 调用 Android API。

### 6.4 module.json 到 AndroidManifest 的映射

HAP 的 `module.json` 包含权限声明、Ability 注册等信息，需要映射到 Android 系统理解的形式：

```json
// module.json (HAP)
{
  "module": {
    "name": "entry",
    "type": "entry",
    "requestPermissions": [
      { "name": "ohos.permission.INTERNET" }
    ],
    "abilities": [
      {
        "name": "EntryAbility",
        "type": "page",
        "launchType": "singleton"
      }
    ]
  }
}
```

映射策略：
- `ohos.permission.INTERNET` → `android.permission.INTERNET`
- `ohos.permission.LOCATION` → `android.permission.ACCESS_FINE_LOCATION`
- Ability 不需要动态注册到 AndroidManifest，由 HoaAbilityActivity 统一承载

---

## 七、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| ArkUI-X Android 构建可能需要特定 NDK/SDK 版本 | 构建阻塞 | 提前验证构建环境，准备 Docker 构建镜像 |
| .abc 字节码版本不兼容（HAP 由更新版 SDK 编译） | 运行时崩溃 | 维护多版本 Ark VM，或限制支持的 API 版本范围 |
| 系统服务 API 依赖链深（一个 API 可能间接依赖多个子系统） | 适配工作量超预期 | 采用懒加载 + stub 返回策略，优先适配高频 API |
| 原生 .so 库不兼容 | 部分 HAP 无法运行 | 明确标注支持范围，优先支持纯 ArkTS HAP |
| 华为法律/许可限制 | 项目风险 | 仅基于 OpenHarmony 开源代码，不使用任何闭源 HarmonyOS 组件 |

---

## 八、与 Wine 的关键区别

| 维度 | Wine | HOA |
|------|------|-----|
| 目标 | Windows PE → Linux | HarmonyOS HAP → Android |
| 字节码 | x86/x64 机器码（需 CPU 兼容） | Ark .abc 字节码（VM 解释，CPU 无关） |
| 系统调用 | NT 系统调用 → Linux syscall | Samgr IPC → Android Binder |
| API 层 | Win32/COM → X11/Wayland | @kit.* → Android SDK |
| 图形系统 | GDI/DirectX → X11/Vulkan | ArkUI Engine → Android Surface |
| 字节码优势 | — | .abc 是 VM 字节码，天然跨平台，无需指令集翻译 |

**HOA 的一个关键优势**：Ark .abc 是 VM 字节码而非本地机器码，这意味着不存在 Wine 面临的指令集翻译问题。只要 Ark VM 在 Android 上能运行，任何 .abc 字节码理论上都能执行。主要工作量在系统 API 适配，而非指令翻译。
