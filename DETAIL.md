# HOA MVP 详细实施规划

> MVP 目标：在 Android 上加载一个纯 ArkTS HAP，显示 Hello World 级别的 UI 并支持基本交互

## 总览

MVP 分为 6 个阶段，每个阶段都有明确的验证标准（可独立验证），后续阶段依赖前序阶段的产出。

```
Stage 0 ──→ Stage 1 ──→ Stage 2 ──→ Stage 3 ──→ Stage 4 ──→ Stage 5
构建环境      运行时壳    复现        HAP解析      动态加载     资源系统
              初始化      ArkUI-X     module.json  从HAP加载    $r()解析
```

---

## Stage 0: 构建环境搭建与 ArkUI-X Android 编译

**目标**：从 ArkUI-X 源码成功编译出 Android 运行时库（.so + .jar），获得可用的运行时二进制。

**预计耗时**：1 周

### 实现路径

1. **环境准备**
   - 安装 Android NDK r21（ArkUI-X 指定版本：21.3.6528147）
   - 设置 `ANDROID_HOME`、`JAVA_HOME` 环境变量
   - 安装 Python 3.11、Node.js 14.21.1（ArkUI-X build.sh 会自动下载 prebuilts）
   - 验证基础工具链：clang 15.0.4、ninja 1.13.2、cmake 3.28.2

2. **下载 prebuilts**
   ```bash
   cd /src/arkui-x
   ./build/prebuilts_download.sh --build-arkuix --skip-ssl
   ```
   验证 `prebuilts/` 目录下 nodejs、python、clang 等已下载。

3. **执行 Android 构建**
   ```bash
   ./build.sh --product-name arkui-x --target-os android
   ```
   构建系统会：
   - 应用 ArkUI-X 补丁到 OpenHarmony 构建模板
   - 以 `target_os="android" target_cpu="arm64"` 编译所有子系统
   - 使用 NDK 的 clang 交叉编译 C++ 代码

4. **收集构建产物**
   从 `out/arkui-x/` 收集以下关键文件：
   - `libarkui_android.so` — ArkUI 渲染引擎 + Ark VM 核心库
   - `arkui_android_adapter.jar` — Java 侧桥接层（含 StageActivity、StageApplication）
   - `libhilog.so` — 日志库
   - `libresourcemanager.so` — 资源管理库
   - 各 UI 组件插件 .so（libarkui_*.so）
   - NAPI 模块 .so

5. **构建产物验证**
   ```bash
   # 检查 .so 架构
   file out/arkui-x/libarkui_android.so
   # 预期输出：ELF 64-bit LSB shared object, ARM aarch64

   # 检查 .jar 内容
   jar tf out/arkui-x/arkui_android_adapter.jar | grep StageActivity
   # 预期输出：ohos/stage/ability/adapter/StageActivity.class

   # 检查符号导出
   nm -D out/arkui-x/libarkui_android.so | grep -i "napi_create"
   # 预期：能看到 NAPI 相关符号
   ```

### 验证标准

- [ ] `build.sh --product-name arkui-x --target-os android` 零错误完成
- [ ] `libarkui_android.so` 存在且为 ARM64 ELF
- [ ] `arkui_android_adapter.jar` 包含 `ohos.stage.ability.adapter.StageActivity`
- [ ] `libhilog.so`、`libresourcemanager.so` 及至少 10 个组件插件 .so 存在

### 已知风险与应对

| 风险 | 应对 |
|------|------|
| NDK 版本不匹配导致编译失败 | 严格使用 r21.3.6528147；准备 Dockerfile 固定环境 |
| prebuilts 下载因网络问题失败 | `--skip-ssl` 跳过证书验证；离线准备 prebuilts 包 |
| 源码有 Android 特定编译错误 | 检查是否需要补丁，参考 `/src/arkui-x/foundation/arkui/ace_engine/adapter/android/` 的 BUILD.gn |

---

## Stage 1: Android 应用壳与 ArkUI-X 运行时初始化

**目标**：创建 Android 应用项目，集成 ArkUI-X 运行时，成功初始化 Ark VM 和 ArkUI 渲染引擎，在屏幕上显示空白渲染表面。

**预计耗时**：1 周

### 实现路径

1. **创建 Android 项目**
   - 基于现有 `/src/HOA/` 项目（已是 Android Gradle 项目）
   - minSdk 26, targetSdk 33, 支持 arm64-v8a 和 armeabi-v7a

2. **集成 ArkUI-X 运行时库**
   - 将 Stage 0 产出的 .so 放入 `app/src/main/jniLibs/arm64-v8a/`
   - 将 `arkui_android_adapter.jar` 放入 `app/libs/`
   - 配置 `build.gradle.kts`：
     ```kotlin
     dependencies {
         implementation(files("libs/arkui_android_adapter.jar"))
     }
     android {
         defaultConfig {
             ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
         }
     }
     ```

3. **实现 HoaApplication**
   ```kotlin
   class HoaApplication : StageApplication() {
       override fun onCreate() {
           super.onCreate()  // 初始化 ArkUI-X 运行时
       }
   }
   ```
   `StageApplication.onCreate()` 内部会：
   - 加载 `libarkui_android.so`
   - 创建应用沙箱目录（temp, files, cache, preferences）
   - 初始化 Ark VM 和 ArkUI 引擎
   - 注册 NAPI 模块

4. **实现 MainActivity（空白渲染表面）**
   ```kotlin
   class MainActivity : StageActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           // 暂时使用空实例名，仅验证渲染表面创建
           setInstanceName("test:entry:MainAbility:")
           super.onCreate(savedInstanceState)
       }
   }
   ```
   `StageActivity.onCreate()` 会：
   - 通过 JNI 调用 `StageActivityDelegate.nativeAttachStageActivity()`
   - 创建 `WindowViewSurface`（继承 SurfaceView）
   - 绑定 ArkUI 渲染管线到 Surface

5. **AndroidManifest.xml 配置**
   ```xml
   <application
       android:name=".HoaApplication"
       ...>
       <activity
           android:name=".MainActivity"
           android:exported="true">
           <intent-filter>
               <action android:name="android.intent.action.MAIN" />
               <category android:name="android.intent.category.LAUNCHER" />
           </intent-filter>
       </activity>
   </application>
   ```

6. **创建最小 assets 目录**
   ArkUI-X 运行时期望 `assets/arkui-x/` 目录存在（即使是空的）：
   ```
   app/src/main/assets/arkui-x/
   ```

### 验证标准

- [ ] 应用安装到 Android 设备/模拟器后能启动，不崩溃
- [ ] logcat 中能看到 `StageApplication` 初始化日志（libarkui_android.so 加载成功）
- [ ] logcat 中能看到 Ark VM 初始化日志
- [ ] 屏幕上显示 SurfaceView（黑屏/空白渲染表面）
- [ ] 无 unsatisfied link error、no such library 等错误

### 调试要点

- 如果 `StageApplication.onCreate()` 崩溃：检查 .so 是否正确放置在 jniLibs 对应 ABI 目录
- 如果出现符号解析失败：用 `nm -D` 检查 .so 导出符号，确认 NAPI 注册函数存在
- 如果 SurfaceView 未显示：检查 WindowViewSurface 创建逻辑，确认 Surface 回调被触发

---

## Stage 2: 复现 ArkUI-X Example——加载预编译 .abc 显示 UI

**目标**：将 ArkUI-X Example 项目的编译产物（.abc 字节码 + 资源）预打包进 APK，验证完整的 UI 渲染链路：.abc 加载 → Ark VM 执行 → ArkUI 组件树构建 → 渲染到 SurfaceView。

**预计耗时**：1.5 周

> 这是关键里程碑——此阶段成功即证明 ArkUI-X 运行时在我们的应用壳中可正常工作。

### 实现路径

1. **获取 ArkUI-X Example 的编译产物**

   方案 A（优先）：从已有构建中获取
   ```bash
   # 如果 ArkUI-X 构建产出中有示例 .abc
   find /src/arkui-x/out -name "modules.abc" -o -name "modules_static.abc"
   ```

   方案 B：使用示例项目中的 .so 库
   ```bash
   # ArkUI-X example 的 .arkui-x/android/app/libs/ 中有预编译运行时
   ls /data/share/arkui-x-example/.arkui-x/android/app/libs/arm64-v8a/
   ```
   该目录已包含 `libarkui_android.so`、`libhilog.so` 等预编译二进制。

   方案 C：用 hvigor 编译 ArkUI-X Example 生成 .abc
   ```bash
   cd /data/share/arkui-x-example
   # 需要 hvigor 和 ArkUI-X SDK
   ```

2. **准备 assets 目录结构**

   ArkUI-X 期望的 assets 格式（StageAssetProvider 加载路径）：
   ```
   app/src/main/assets/arkui-x/
   ├── entry/                          # 模块名
   │   ├── ets/
   │   │   ├── modules.abc             # 编译后的 ArkTS 字节码
   │   │   └── sourceMaps.map          # 调试映射（可选）
   │   ├── resources/
   │   │   ├── base/
   │   │   │   ├── element/
   │   │   │   │   └── string.json     # 字符串资源
   │   │   │   └── media/              # 图片资源
   │   │   └── rawfile/                # 原始文件
   │   ├── resources.index             # 资源索引（编译产物）
   │   ├── module.json                 # 模块配置
   │   └── moduleConfig.json           # 模块编译配置
   └── systemres/                      # 系统资源
       └── resources.index
   ```

3. **更新 MainActivity 加载 entry 模块**
   ```kotlin
   class MainActivity : StageActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           // instanceName 格式: {bundleName}:{moduleName}:{abilityName}:
           setInstanceName("app.hackeris.arkuixexample:entry:EntryAbility:")
           super.onCreate(savedInstanceState)
       }
   }
   ```

   `setInstanceName()` 的作用：告知 ArkUI-X 运行时要加载哪个模块的哪个 Ability。运行时会据此：
   - 在 `assets/arkui-x/{moduleName}/` 下查找 .abc 和 module.json
   - 通过 `ArktsDynamicFrontend` 加载 `modules.abc`
   - 查找入口类 `__EntryWrapper_{abilityName}`
   - 调用入口方法创建 Ability 实例

4. **处理 systemres**

   ArkUI 渲染引擎依赖系统资源（默认主题、组件样式等）。来源：
   - 从 ArkUI-X 构建产物的 `out/arkui-x/systemres/` 复制
   - 或从 `/src/ohos/` 的系统资源中提取

   如果缺失系统资源，ArkUI 组件可能无法正确渲染（缺少默认颜色、字体等）。

5. **资源索引处理**

   `resources.index` 是编译时由 `global_resource_tool` 从 JSON 资源文件生成的二进制文件。需要确保有正确编译的 `resources.index`，否则 `$r()` 引用无法解析。

### 验证标准

- [ ] 应用启动后，屏幕显示 "Hello World" 文本（来自 ArkUI-X Example 的 Index.ets）
- [ ] 点击文本后，文字变为 "Welcome"（验证交互事件链路：Touch → JNI → ArkUI → 状态更新 → 重渲染）
- [ ] logcat 中看到 Ability 生命周期回调：`onCreate` → `onWindowStageCreate`
- [ ] 无 .abc 解析错误、无 NAPI 模块缺失错误
- [ ] 字体、颜色等基本样式正确渲染

### 可能问题与排查

| 现象 | 原因 | 排查 |
|------|------|------|
| 启动后黑屏，无 UI | .abc 未找到或加载失败 | 检查 assets 路径和 instanceName 格式 |
| 崩溃：Unable to find __EntryWrapper | abilityName 不匹配 | 检查 module.json 中的 abilities 列表 |
| 组件渲染但无样式 | 缺少 systemres | 确保 systemres/resources.index 存在 |
| $r() 引用返回空值 | resources.index 缺失或格式错误 | 检查 resources.index 是否为正确编译产物 |

---

## Stage 3: HAP 文件解析器

**目标**：实现 HAP 文件格式解析，能够从 .hap 文件中提取 module.json、.abc 字节码、resources.index 和资源文件。纯工具模块，不依赖 Android 运行时。

**预计耗时**：1 周

### 实现路径

1. **定义数据模型**
   ```kotlin
   data class HapBundle(
       val hapFile: File,
       val moduleConfig: ModuleConfig,
       val bytecodeEntries: Map<String, ByteArray>,  // "ets/modules.abc" → bytes
       val resourceIndex: ByteArray?,                 // resources.index
       val rawResources: Map<String, ByteArray>,      // resources/base/... → bytes
       val nativeLibs: Map<String, ByteArray>,        // libs/arm64-v8a/*.so → bytes
       val packInfo: PackInfo?
   )

   data class ModuleConfig(
       val name: String,           // "entry"
       val type: String,           // "entry" | "feature" | "shared"
       val bundleName: String,     // 来自 pack.info 或 app.json5
       val mainElement: String,    // "EntryAbility"
       val deviceTypes: List<String>,
       val abilities: List<AbilityConfig>,
       val pages: List<String>,    // 页面路由表
       val requestPermissions: List<String>
   )

   data class AbilityConfig(
       val name: String,           // "EntryAbility"
       val srcEntry: String,       // 源码路径
       val type: String,           // "page" | "service" | "data"
       val launchType: String,     // "singleton" | "multiton" | "specified"
       val exported: Boolean,
       val skills: List<SkillConfig>
   )
   ```

2. **实现 HapBundleLoader**

   ```kotlin
   class HapBundleLoader {
       fun parse(hapFile: File): HapBundle {
           val zip = ZipFile(hapFile)

           // 解析 module.json
           val moduleJson = zip.getEntry("module.json")
               ?: zip.getEntry("module.json5")
               ?: throw HapParseException("module.json not found")
           val moduleConfig = parseModuleConfig(zip.getInputStream(moduleJson))

           // 提取 .abc 字节码
           val bytecodeEntries = mutableMapOf<String, ByteArray>()
           val abcEntries = listOf("ets/modules.abc", "ets/modules_static.abc")
           for (abcPath in abcEntries) {
               zip.getEntry(abcPath)?.let { entry ->
                   bytecodeEntries[abcPath] = zip.getInputStream(entry).readBytes()
               }
           }

           // 提取 resources.index
           val resourceIndex = zip.getEntry("resources.index")?.let {
               zip.getInputStream(it).readBytes()
           }

           // 提取 pack.info
           val packInfo = zip.getEntry("pack.info")?.let {
               parsePackInfo(zip.getInputStream(it))
           }

           // 提取资源文件
           val rawResources = extractResources(zip, "resources/")

           // 提取原生 .so
           val nativeLibs = extractNativeLibs(zip)

           zip.close()
           return HapBundle(hapFile, moduleConfig, bytecodeEntries, resourceIndex,
                           rawResources, nativeLibs, packInfo)
       }
   }
   ```

3. **module.json 解析**

   根据 OpenHarmony bundlemgr 的解析逻辑（`/src/ohos/foundation/bundlemanager/bundle_framework/services/bundlemgr/src/module_profile.cpp`），关键字段：
   - `module.name`：模块名
   - `module.type`：entry/feature/shared
   - `module.mainElement`：主 Ability 名称
   - `module.deviceTypes`：支持设备类型
   - `module.abilities`：Ability 列表
   - `module.requestPermissions`：权限声明
   - `module.pages`：页面路由表（可能是 `$profile:main_pages` 引用）

4. **pages 路由表解析**

   `module.pages` 可能是直接数组 `["pages/Index"]`，也可能是资源引用 `"$profile:main_pages"`。后者需要从 `resources/base/profile/main_pages.json` 中读取：
   ```json
   {
     "src": ["pages/Index"]
   }
   ```

5. **单元测试**

   ```kotlin
   @Test
   fun testParseHap() {
       val hapFile = File("src/test/resources/test_app.hap")
       val bundle = HapBundleLoader().parse(hapFile)

       assertEquals("entry", bundle.moduleConfig.name)
       assertEquals("app.hackeris.arkuixexample", bundle.moduleConfig.bundleName)
       assertTrue(bundle.bytecodeEntries.containsKey("ets/modules.abc"))
       assertNotNull(bundle.resourceIndex)
       assertTrue(bundle.moduleConfig.abilities.isNotEmpty())
   }
   ```

6. **准备测试用 HAP**

   使用 ArkUI-X Example 项目编译生成一个测试用 .hap 文件：
   ```bash
   cd /data/share/arkui-x-example
   # 用 hvigor 编译生成 .hap
   # 产出路径通常在 entry/build/default/outputs/default/entry-default-signed.hap
   ```

### 验证标准

- [ ] 能正确解析测试 HAP 的 module.json，提取所有 abilities 和 pages
- [ ] 能从 HAP 中提取 `ets/modules.abc` 字节数据，大小 > 0
- [ ] 能提取 `resources.index`（如果 HAP 中包含）
- [ ] 能列出 HAP 中的资源文件路径
- [ ] 单元测试全部通过
- [ ] 对格式异常的 HAP（缺少 module.json）抛出有意义的异常

---

## Stage 4: 动态 HAP 加载——从 HAP 文件加载 .abc 并执行

**目标**：取代 Stage 2 中将 .abc 预打包进 assets 的方式，实现运行时从 HAP 文件动态加载 .abc 字节码并在 Ark VM 中执行。这是实现"Wine 式"直接运行 HAP 的核心突破。

**预计耗时**：1.5 周

> 这是 MVP 最关键的阶段——完成后即实现了"选择一个 .hap 文件 → 直接运行"的核心能力。

### 实现路径

1. **HAP 文件安装管理**

   将 HAP 提取到应用私有目录，模拟 ArkUI-X 的 assets 结构：

   ```kotlin
   class HapInstaller(private val context: Context) {
       private val hapDir = File(context.filesDir, "hap-installed")

       fun install(hapFile: File): InstalledHap {
           val bundle = HapBundleLoader().parse(hapFile)
           val moduleName = bundle.moduleConfig.name
           val bundleName = bundle.moduleConfig.bundleName

           // 创建目标目录结构（与 assets/arkui-x/ 格式一致）
           val targetDir = File(hapDir, "$bundleName/$moduleName")
           targetDir.mkdirs()

           // 写入 module.json
           File(targetDir, "module.json").writeText(bundle.moduleConfig.rawJson)

           // 写入 .abc 字节码
           bundle.bytecodeEntries.forEach { (path, data) ->
               val file = File(targetDir, path)
               file.parentFile?.mkdirs()
               file.writeBytes(data)
           }

           // 写入 resources.index
           bundle.resourceIndex?.let { data ->
               File(targetDir, "resources.index").writeBytes(data)
           }

           // 写入资源文件
           bundle.rawResources.forEach { (path, data) ->
               val file = File(targetDir, path)
               file.parentFile?.mkdirs()
               file.writeBytes(data)
           }

           return InstalledHap(bundleName, moduleName, targetDir, bundle.moduleConfig)
       }
   }
   ```

2. **扩展 StageAssetProvider 以支持从 HAP 目录加载**

   ArkUI-X 的 `StageAssetProvider` 在 `StageApplication.onCreate()` 中初始化，默认从 APK assets 加载。需要修改加载策略：

   方案 A（优先）：利用 StageApplication 现有的 asset 复制机制
   - `StageApplication` 会在 `onCreate` 中将 `assets/arkui-x/` 复制到 data 目录
   - 在复制完成后，将 HAP 解压的内容覆盖到同一目录
   - 这样 ArkUI-X 的标准加载流程无需修改

   ```kotlin
   class HoaApplication : StageApplication() {
       private var pendingHap: InstalledHap? = null

       override fun onCreate() {
           super.onCreate()  // 标准初始化 + assets 复制
       }

       fun loadHap(hap: InstalledHap) {
           pendingHap = hap
           // 将 HAP 内容符号链接/复制到 StageApplication 期望的路径
           val arkuiXDir = File(filesDir, "arkui-x")
           val moduleDir = File(arkuiXDir, hap.moduleName)
           // ... 将 hap.contentDir 内容映射到 moduleDir
       }
   }
   ```

   方案 B（如果方案 A 不可行）：修改 C++ 层的 `StageAssetProvider`
   - 修改 `/src/arkui-x/foundation/arkui/ace_engine/adapter/android/entrance/java/jni/` 中的资产加载逻辑
   - 添加新的 JNI 方法，允许 Java 层注入额外的资源搜索路径
   - 风险：需要修改并重新编译 ArkUI-X native 代码

3. **HAP 选择界面**

   ```kotlin
   class MainActivity : AppCompatActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           // 显示 HAP 文件选择器
           // 用户选择 .hap 文件后 → HapInstaller.install() → 启动 HoaAbilityActivity
       }

       private fun onHapSelected(hapFile: Uri) {
           val installed = (application as HoaApplication).installHap(hapFile)
           val intent = Intent(this, HoaAbilityActivity::class.java).apply {
               putExtra("BUNDLE_NAME", installed.bundleName)
               putExtra("MODULE_NAME", installed.moduleName)
               putExtra("ABILITY_NAME", installed.mainAbility)
           }
           startActivity(intent)
       }
   }
   ```

4. **HoaAbilityActivity——HAP Ability 的 Android 宿主**

   ```kotlin
   class HoaAbilityActivity : StageActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           val bundleName = intent.getStringExtra("BUNDLE_NAME")!!
           val moduleName = intent.getStringExtra("MODULE_NAME")!!
           val abilityName = intent.getStringExtra("ABILITY_NAME")!!

           // 构建 instanceName（与 ArkUI-X 格式一致）
           setInstanceName("$bundleName:$moduleName:$abilityName:")

           super.onCreate(savedInstanceState)
       }
   }
   ```

5. **处理模块配置中的 pages 路由表**

   module.json 中的 `pages` 字段定义了页面路由。ArkUI 运行时需要此信息来解析 `router.pushUrl('pages/Index')` 调用。需要确保 `moduleConfig.json`（或 module.json）在 HAP 安装目录中可被运行时访问。

### 验证标准

- [ ] 从设备存储选择一个 .hap 文件
- [ ] 应用解析并"安装"该 HAP（文件提取到私有目录）
- [ ] 启动 HoaAbilityActivity，Ark VM 成功加载 HAP 中的 .abc 字节码
- [ ] HAP 的 UI 正确渲染到屏幕（Hello World 文本可见）
- [ ] 点击交互正常（文字变化、按钮响应等）
- [ ] 切换到另一个 HAP 文件，同样能正常加载和显示

### 关键技术难点

1. **AssetProvider 路径注入**：需要确保运行时从 HAP 安装目录而非 APK assets 加载 .abc。这是最可能遇到阻碍的点。调试方法：
   - 在 C++ 层 `StageAssetProvider::GetAssetAsCodePath()` 中加日志，确认搜索路径
   - 检查 `StageApplicationDelegate` 中 `copyAssets` 的目标目录
   - 如果方案 A 行不通，需要实施方案 B（修改 C++ 层）

2. **instanceName 格式匹配**：如果格式不对，ArkUI-X 会找不到入口类。参考 ArkUI-X Example 中已验证的格式：`app.hackeris.arkuixexample:entry:EntryAbility:`（注意末尾冒号）。

3. **systemres 可用性**：HAP 中的 UI 可能依赖系统资源。需要确保 systemres 在 HAP 加载时也可被找到。

---

## Stage 5: 资源系统适配

**目标**：实现 HAP 资源的正确加载和解析，使 `$r()` 引用、`$rawfile()` 访问、多语言/暗色模式等资源特性在 Android 上工作。

**预计耗时**：1 周

### 实现路径

1. **resources.index 解析**

   `resources.index` 的二进制格式（V2 版本）：

   ```
   Header:
     version[128]     - 版本标识
     length           - 文件总大小
     keyCount         - 限定词配置数量
     dataBlockOffset  - 数据块偏移

   KeySection (tag: "KEYS"):
     keyCount         - 参数数量
     KeyParam[]       - 限定词参数（语言、地区、分辨率、暗色模式等）

   IdSection (tag: "IDSS"):
     typeCount        - 资源类型数量
     idCount          - 资源 ID 总数
     ResTypeHeader[]  - 各类型头部
       ├── ResType (string/color/float/media/...)
       └── ResId[]    - 该类型下的资源 ID 列表

   DataBlock:
     ResInfo[]        - 资源数据项
       ├── resId      - 资源 ID
       ├── dataOffset[] - 各限定词配置下的数据偏移
       └── valueData  - 实际值数据
   ```

   解析参考：`/src/ohos/global/resource_management/include/` 中的 `res_desc.h`、`hap_manager.h`。

2. **$r() 引用解析**

   ArkTS 中 `$r('app.string.app_name')` 在编译时被转换为 `Resource(resId)`，其中 `resId` 是一个 32 位整数（如 `0x01000001`）。

   运行时解析流程：
   ```
   $r('app.string.app_name')
     → 编译为 Resource(0x01000001)
     → ArkUI 框架调用 ResourceManager.getString(0x01000001)
     → 在 resources.index 的 IdSection 中查找 resId
     → 根据当前设备配置匹配最佳限定词
     → 从 DataBlock 读取值数据
     → 返回 "ArkUIXExample"
   ```

3. **HAP 资源与 systemres 合并**

   ArkUI 运行时同时维护系统资源和应用资源：
   - `systemres/resources.index` — 默认主题、组件样式
   - `{module}/resources.index` — 应用特定资源

   需要确保两者都被 `ResourceManager` 加载。参考 `ResourceAdapterImpl::Init()` 的实现：
   ```cpp
   auto resMgr = Global::Resource::CreateResourceManager();
   resMgr->AddResource(systemResPath);   // 系统资源
   resMgr->AddResource(hapResPath);      // 应用资源
   ```

4. **设备配置适配**

   OpenHarmony 的资源限定词系统与 Android 类似，但参数不同：

   | 限定词 | OpenHarmony | Android 映射 |
   |--------|-------------|-------------|
   | 语言 | zh / en | Locale.getDefault().language |
   | 地区 | CN / US | Locale.getDefault().country |
   | 屏幕密度 | sdpi / mdpi / ldpi / xldpi / xxldpi | densityDpi 映射 |
   | 暗色模式 | dark / light | uiMode & NIGHT_MASK |
   | 屏幕方向 | vertical / horizontal | orientation |
   | 设备类型 | phone / tablet | screenLayout |

   需要将 Android 设备配置转换为 OpenHarmony 的 ResConfig：
   ```kotlin
   class DeviceConfigMapper {
       fun mapAndroidToOhos(config: Configuration): OhosResConfig {
           return OhosResConfig(
               language = config.locale.language,
               region = config.locale.country,
               density = mapDensity(config.densityDpi),
               darkMode = (config.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES,
               orientation = if (config.orientation == ORIENTATION_PORTRAIT) "vertical" else "horizontal"
           )
       }
   }
   ```

5. **$rawfile() 访问**

   `$rawfile('config.json')` 直接从 HAP 的 `resources/rawfile/` 目录读取文件。需要在 StageAssetProvider 中注册 rawfile 搜索路径指向 HAP 安装目录。

### 验证标准

- [ ] HAP 中使用 `$r('app.string.app_name')` 的文本正确显示
- [ ] 切换系统语言后，多语言资源正确切换
- [ ] 切换暗色模式后，暗色资源正确加载（如果 HAP 包含 dark 限定词资源）
- [ ] `$rawfile()` 访问的文件内容正确
- [ ] 图片资源 `$r('app.media.icon')` 正确渲染
- [ ] 系统资源（默认组件样式）正确加载，UI 组件样式正常

### 测试用 HAP 需求

需要准备一个包含丰富资源的测试 HAP：
- 多语言字符串（中文 + 英文）
- 不同密度的图片资源
- 暗色模式资源变体
- rawfile 文件

如果无法从现有 HAP 获取，可用 ArkUI-X SDK 编译一个简单的资源测试 HAP。

---

## Stage 6: 基本 Ability 生命周期与页面路由

**目标**：实现 Ability 生命周期在 Android Activity 上的正确映射，以及 ArkUI 页面路由（router）功能，使多页面 HAP 应用可以正常导航。

**预计耗时**：1 周

### 实现路径

1. **Ability → Activity 生命周期映射**

   在 `HoaAbilityActivity` 中实现完整的生命周期桥接：

   ```kotlin
   class HoaAbilityActivity : StageActivity() {
       // StageActivity 已实现基础映射，需验证并补充：

       override fun onCreate(savedInstanceState: Bundle?) {
           // → ArkTS EntryAbility.onCreate(want, launchParam)
           setInstanceName(...)
           super.onCreate(savedInstanceState)
       }

       // 以下 StageActivity 已实现，需验证回调是否正确到达 ArkTS 层：
       // onResume()   → UIAbility.onForeground()
       // onPause()    → UIAbility.onBackground()
       // onDestroy()  → UIAbility.onDestroy()

       override fun onNewIntent(intent: Intent) {
           // → UIAbility.onNewWant(want)
           // 需要将 Android Intent 转换为 OHOS Want
           super.onNewIntent(intent)
       }
   }
   ```

   验证 ArkTS 侧回调到达的方法：在 ArkTS 代码的每个生命周期回调中调用 `hilog.info()`，观察 logcat。

2. **router 导航支持**

   ArkUI 的 `router` 模块是页面导航的核心 API：

   ```typescript
   // ArkTS 代码
   import { router } from '@kit.ArkUI';

   // 跳转到新页面
   router.pushUrl({ url: 'pages/SecondPage' })

   // 返回上一页
   router.back()

   // 替换当前页面
   router.replaceUrl({ url: 'pages/LoginPage' })
   ```

   ArkUI-X 中的 router 实现在 `ace_engine/frameworks/` 中，使用页面路由栈管理。需要验证：
   - module.json 中的 `pages` 列表被正确解析
   - `router.pushUrl()` 能正确定位到 HAP 中对应的 .abc 页面入口
   - 路由栈的后进先出逻辑正确
   - 页面转场动画正常

3. **Want ↔ Intent 基础映射**

   为 `startAbility()` 提供最小支持：

   ```kotlin
   class WantMapper {
       companion object {
           fun toAndroidIntent(ohWant: OhWant): Intent {
               return Intent().apply {
                   // 显式 Want：指定 bundleName + abilityName
                   if (ohWant.elementName != null) {
                       setClassName(
                           ohWant.elementName.bundleName,
                           ohWant.elementName.abilityName
                       )
                   }
                   // 隐式 Want：通过 action 匹配
                   if (ohWant.action != null) {
                       action = ohWant.action
                   }
                   // 参数传递
                   ohWant.parameters?.forEach { (key, value) ->
                       putExtra(key, value.toString())
                   }
               }
           }
       }
   }
   ```

   注意：MVP 阶段仅支持应用内 Ability 跳转（同一 HAP 中的不同 Ability），跨应用启动需要完整的 BundleManager 支持。

4. **多 Ability HAP 支持**

   如果 HAP 的 module.json 声明了多个 abilities：

   ```json
   {
     "module": {
       "abilities": [
         { "name": "EntryAbility", "type": "page", "mainElement": true },
         { "name": "SecondAbility", "type": "page" }
       ]
     }
   }
   ```

   需要支持从 EntryAbility 启动 SecondAbility：
   ```typescript
   // ArkTS
   let want: Want = {
       bundleName: 'com.example.app',
       abilityName: 'SecondAbility'
   };
   this.context.startAbility(want);
   ```

   实现方式：`HoaAbilityManager` 接收到 `startAbility` 调用后，创建新的 `HoaAbilityActivity` 实例，设置对应的 instanceName。

### 验证标准

- [ ] HAP 启动时，ArkTS 侧的 `onCreate()` 和 `onWindowStageCreate()` 被调用
- [ ] 应用进入后台时，ArkTS 侧的 `onBackground()` 被调用
- [ ] 应用回到前台时，ArkTS 侧的 `onForeground()` 被调用
- [ ] 应用退出时，ArkTS 侧的 `onDestroy()` 被调用
- [ ] `router.pushUrl('pages/SecondPage')` 能导航到第二页
- [ ] `router.back()` 能返回第一页
- [ ] `router.replaceUrl()` 能替换当前页面
- [ ] 同一 HAP 内两个 Ability 之间的 `startAbility()` 调用能成功跳转

### 测试用 HAP 需求

需要准备一个多页面的测试 HAP：
- 第一页：包含跳转按钮
- 第二页：显示"Second Page"文本和返回按钮
- EntryAbility 中打印所有生命周期回调日志

---

## 附录 A: 各阶段依赖关系与并行可能性

```
Stage 0 (构建环境)
    │
    ├──→ Stage 1 (应用壳) ──→ Stage 2 (预编译 .abc)
    │                              │
    ├──→ Stage 3 (HAP 解析) ───────┼──→ Stage 4 (动态加载) ──→ Stage 5 (资源) ──→ Stage 6 (生命周期)
    │                              │
    │   (Stage 3 可与 Stage 1-2 并行开发)    (Stage 4 依赖 Stage 2+3)
    │
    └── Stage 3 纯 Kotlin/工具代码，不依赖 Android 运行时
```

- **Stage 0** 是所有后续阶段的前置条件
- **Stage 1-2** 与 **Stage 3** 可并行开发
- **Stage 4** 必须等 Stage 2 和 Stage 3 都完成
- **Stage 5-6** 顺序依赖 Stage 4

## 附录 B: 测试 HAP 准备方案

MVP 各阶段需要不同复杂度的测试 HAP：

| 阶段 | 所需测试 HAP | 获取方式 |
|------|-------------|---------|
| Stage 2 | 简单 Hello World | ArkUI-X Example 编译 |
| Stage 4 | 同上 | 同上（从 .hap 文件加载） |
| Stage 5 | 含多语言、多密度、暗色模式资源 | 自行编译或获取开源 HAP |
| Stage 6 | 多页面 + 多 Ability | 自行编译 |

推荐在 Stage 0 完成后，立即准备所有测试 HAP，避免后续阶段被测试数据阻塞。

## 附录 C: MVP 完成后的验收场景

完整的 MVP 验收流程：

1. 打开 HOA 应用
2. 选择一个 .hap 文件（通过文件选择器）
3. 应用解析 HAP → 提取资源 → 安装到私有目录
4. 启动 HAP 的主 Ability
5. 看到 HAP 的 UI 正确渲染（文本、图片、按钮样式正确）
6. 点击按钮触发交互（页面跳转、状态变化）
7. 通过 router 导航到其他页面，再返回
8. 切换到后台再回前台，生命周期正确
9. 退出 HAP，返回 HOA 主界面
10. 选择另一个 .hap 文件，同样正常运行
