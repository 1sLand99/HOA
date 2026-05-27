# HOA 项目进展

## 当前状态

**里程碑**: ArkUI-X weekly_20260518 移植完成（2026-05-22），基于 `hoa` 分支 patch 创建 `hoa-weekly`，构建和运行时验证通过。

从 `hoa-6.1`（基于 ArkUI-X 6.1-Release，OHOS 内核 cut 于 2026-02-02）切换到 `weekly_20260518`（2026-05-18 最新 OHOS 同步），获得 3 个月的 OHOS 上游代码更新。manifest 分支: `ArkUI-X-6.1-Release` → `weekly`，repo revisions: `hoa-6.1` → `hoa-weekly`。

5 个已安装 HAP 中 4 个正常渲染、1 个部分渲染（HDS 组件 mock 视觉差异）。用户管理流程完整：选择 HAP → 预览弹窗 → 确认安装 → 列表展示 → 长按查看详情/卸载 → 点击启动 → ArkUI-X 渲染。

---

## 已验证的能力

| 能力 | 状态 | 说明 |
|------|------|------|
| ArkUI-X 构建（build.sh） | ✅ | 基于 ArkUI-X 原生 GN 构建系统 |
| 产物同步（sync_arkui_x.sh） | ✅ | 遍历 plugins/*/ 全部子目录，151 个 .so，覆盖所有 OHOS API 插件 |
| HAP 解析与安装 | ✅ | HapBundleLoader 解析元数据 → HapInstaller 流式写入 filesDir/hap/ |
| 大文件 HAP 安装 | ✅ | 从 ZIP 流式 copyTo 磁盘，避免 ByteArray 全量缓存 OOM |
| 安装前预览弹窗 | ✅ | 展示 bundleName、module、version、SDK、权限、Ability 列表 |
| 应用信息详情 | ✅ | 长按 HAP 列表弹出菜单，查看完整信息（含 vendor、大小、页面列表） |
| resources.index 解析 | ✅ | V1/V2 双版本解析器，`$string:xxx` 标签正确 resolve |
| HAP 列表图标与名称 | ✅ | 从 module.json + resources.index 动态解析 |
| ETS VM 创建 | ✅ | ES module 模式 |
| OHOS ABC 加载 | ✅ | modules.abc 中的 EntryAbility + Index 均加载成功 |
| ArkUI 渲染 | ✅ | StageActivity + SurfaceView + Skia 管线正常 |
| WebView 加载 HAP 资源 | ✅ | shouldInterceptRequest 拦截，绕过 Chrome WebView 118 file:// 导航拦截 |
| MainActivity HAP 管理 | ✅ | 安装 / 预览 / 列表 / 启动 / 详情 / 卸载 功能完整 |
| @ohos NAPI 模块全量加载 | ✅ | plugins/*/ 全部 .so 随 APK 分发 |
| OHOS → Android 权限映射 | ✅ | INTERNET 等普通权限绕过 JNI 直接授予，危险权限走运行时流程 |
| HMS HDS 组件 mock | ✅ | 嵌入式 ABC（ViewV2 组合实现）+ module_resolver 重定向，覆盖 ES import 和 HSP record 双路径；30+ 导出（18 枚举、24 Prefix/Suffix 类、HdsSnackBar、hdsMaterial 等）|
| .hap 文件关联 | ✅ | intent filter 注册，文件管理器/分享均可直接安装 |
| 最近任务列表显示 HAP 名称和图标 | ✅ | `setTaskDescription()` 动态设置 |
| 多进程槽位管理 | ✅ | 5 个独立进程，互不干扰 |

---

## 关键突破

### 0. ArkUI-X 6.1-Release 移植（2026-05-21）

基于旧版 ArkUI-X 的 `hoa` 分支 patch 全部移植到 `hoa-6.1`（6.1-Release）。共涉及 5 个仓库：

| 仓库 | 已提交 (cherry-pick) | 新提交 (manual) |
|------|---------------------|-----------------|
| `arkcompiler/ets_runtime` | 2 | 0 |
| `foundation/appframework` | 2 | 4 |
| `foundation/arkui/ace_engine/adapter/android` | 3 | 4 |
| `foundation/arkui/napi` | 2 | 0 |
| `build` | 1 | 0 |

**测试 HAP 白屏修复（最关键的 6.1 新问题）**——`RSUIDirector` 未创建：
6.1 中 `Window::CreateSurfaceNode()` 直接调用 `RSSurfaceNode::Create(config)` 不传 `RSUIContext`，
且 `GetRSUIDirector()`/`GetRSUIContext()` 均返回 `nullptr`。ArkUI-X 原生流程中 RSUIDirector 由外部
注入，HOA 的 HAP 宿主流程无此环节。修复：`CreateSurfaceNode()` 中创建 `RSUIDirector`，将其
`RSUIContext` 传入 `RSSurfaceNode::Create`，同时 `GetMultiInstanceEnabled()` 和
`GetRSClientMultiInstanceEnabled()` 均改为 `true` 开启多实例渲染。

**模块名拼接一致性**——`SplicingModuleName` 产生 `bundleName.moduleName`：
HOA 解压 HAP 到 `filesDir/hap/<bundleName>.<moduleName>/`，`SplicingModuleName()` 将 `entry` 拼为
`app.hackeris.harmonyexample.entry`（33 字符）。此全名是整条运行时链路的 canonical module key。
`MAX_MODULE_NAME` 从 31 扩到 255 以容纳拼接名；`app_main.cpp` 动态模块路径使用拼接名；
`module_profile.cpp` `TransformTo()` 将拼接名同步写入 `module.name` 和 `packageName`；
`StageActivity.java` `setInstanceName` 同步拼接。

详细变更见 `docs/ARKUI-X_PATCHES.md`。

### 1. resources.index 解析器重写（2026-05-18）

V1 parser（OHOS 5.0.1 格式，136B 头，KEYS→IDSS→IdParam→IdItem 结构）原先使用启发式字节扫描，存在误匹配导致 3 个 V1 格式 HAP 的标签解析失败。完全按 OHOS 5.0.1 `hap_parser.cpp` 的二进制格式重写后，所有 5 个 HAP 标签解析正确。

V2 parser（ArkUI-X/RestoolV2 格式，140B 头，含 dataBlockOffset）已在前一阶段完成，继续正常工作。

### 2. 插件 .so 全量补齐（2026-05-18）

`sync_arkui_x.sh` A4 节原先采用白名单方式逐个手选约 15 个插件，遗漏大量 `plugins/` 下的子目录。`com.qiuhaotc.billingrecords`（轻松来记账）因缺少 `libdata_distributedkvstore.so`，`@ohos.data.distributedKVStore` 模块的 `SecurityLevel` export 为 undefined，导致 EntryAbility.onCreate 失败，卡在启动图。

修复：将 A4 节改为 `for plugin_dir in plugins/*/; do copy_so_dir` 遍历全部子目录，jniLibs 从 61 → 151 个 .so，补齐所有 ArkUI-X 跨平台插件。

### 3. 安装前预览弹窗（2026-05-17）

新增 `HapBundleLoader.previewConfig()` 轻量解析方法——仅读取 module.json 不提取 bytecode/resources。MainActivity 从文件管理器选取 .hap 后先弹出预览对话框展示 bundleName、module、version、SDK、权限、Ability 列表，用户确认后再执行完整安装。

### 4. 长按菜单 + 应用信息（2026-05-17）

HAP 列表支持长按弹出菜单：应用信息 / 卸载。应用信息对话框展示完整 module.json 元数据——bundleName、label（解析 `$string:` 引用）、vendor、version、SDK、大小、权限、Ability、页面列表。

### 5. 权限流程修复（2026-05-16）

`ohos.permission.INTERNET` 映射到 `android.permission.INTERNET`（普通权限），修复了普通权限经过运行时流程导致 Promise 永不 resolve 的问题。

### 6. ABC Record 名匹配 & 白屏修复（2026-05-15）

4 个 git 仓库 12 个文件的定向 Patch 解决双维度差异。详见 `docs/ARKUI-X_PATCHES.md`。

---

## 测试 HAP 状态

| HAP | bundleName | 状态 | 说明 |
|-----|-----------|------|------|
| harmonyexample | app.hackeris.harmonyexample | ✅ 正常 | 内嵌测试 HAP，Hello World |
| wan-harmony | top.wangchenyan.wanharmony | ✅ 正常 | 玩安卓，6 个 Tab 页 |
| 脆脆 | com.cuocuo.cn | ✅ 正常 | V1 resources.index 格式 |
| 轻松来记账 | com.qiuhaotc.billingrecords | ✅ 正常 | 补齐 libdata_distributedkvstore.so 后正常 |
| 留白阅读 | liubai.yuedu.hos | ✅ **已修复** | SDK 6.0 ABC record 名格式兼容（3 处回退 patch） |
| hw_base_calendar | com.hw.base_calendar | ⚠️ 部分渲染 | HDS 组件通过嵌入式 ABC mock 加载成功，ActionBar 可见但视觉与原版差异大 |

### 6. SDK 6.0 ABC Record 名兼容（2026-05-18）

留白阅读（`liubai.yuedu.hos`）为 OHOS SDK 6.0 构建，ABC record 名使用 `bundleName/entry/ets/...` 格式（无 `&` 包裹、无 `src/main/` 段）。`IsNormalizedOhmUrlPack()` 对 SDK 5.0 和 6.0 均返回 true，无法在 `GetOutEntryPoint` 区分版本。

**修复策略**：`GetOutEntryPoint` 保持 SDK 5.0 输出，在 3 个 record 查找路径添加 SDK 6.0 回退：

1. **`ExecuteModuleBuffer`** — EntryAbility 首次加载，回退 `bundleName/filename`
2. **`GetExportObject`** — `JsAbility::Init` 模块 export 查找，回退 `bundleName/file`（检测 `IsUndefined()`）
3. **`ExecuteFromFile`** — 页面路由加载，转换 `&` 格式 → `bundleName/...` 格式

详见 `docs/ARKUI-X_PATCHES.md` SDK 6.0 章节。

### 7. WebView 加载 HAP 资源 ERR_ACCESS_DENIED 修复（2026-05-22）

Chrome WebView 118+ 在导航层直接拦截 `file://` URL，`shouldInterceptRequest` 根本不被调用，`setAllowFileAccess(true)` 无效。ArkUI-X 的 `GetRawFileUrl()` 返回裸路径 `/data/user/0/.../rawfile/xxx.html`，Android WebView 内部自动补 `file://` 前缀后被拦截。

**修复策略**：在 AceWeb.java 新增 `rewriteFileUrl()` 将 `file://` 和裸 `/data/` 路径改写为 `http://hoa.internal/` 虚拟主机 URL，确保 `shouldInterceptRequest` 被正常调用。`handleFileRequest()` 识别 `hoa.internal` 主机，通过 `FileInputStream` 直接返回 `WebResourceResponse`。

改了 1 个文件（AceWeb.java），位于 `foundation/arkui/ace_engine/adapter/android` 仓库 `hoa-weekly` 分支。

### 8. HAP 大文件安装 OOM 修复（2026-05-22）

`HapBundleLoader.parse()` 中的 `extractResources()` 和 `extractNativeLibs()` 使用 `it.readBytes()` 将整个 ZIP 条目读入 ByteArray，大 HAP（>5MB 资源文件）直接触发 OutOfMemoryError。

**修复策略**：将文件提取从 `parse()`（纯内存）移到 `installFromBundle()`（流式磁盘写入）。HapBundle 不再携带 ByteArray 数据，只保留 hapFile 路径；安装阶段重新打开 ZIP，用 `copyTo` 8KB 缓冲区流式写入目标目录。3 个文件改动，代码净减少 42 行。

### 9. HDS 组件嵌入式 ABC Mock（2026-05-25）

`hw_base_calendar` 依赖 HMS Design System 组件（`@hms:hds.hdsBaseComponent`），华为专有 SDK 在 ArkUI-X 中完全不存在——无 `system_kits_config.json`、无 `SetHmsModuleList`、无 HDS ABC 实现。

**修复策略**：两步方案覆盖 HDS 的双解析路径：

1. **ES module import 路径**（`@kit.UIDesignKit` / `@hms:hds.hdsBaseComponent`）：嵌入式 ABC mock 通过 `napi_module_with_js`（ABC-only，`nm_register_func = nullptr`）注册，提供 ViewPU 组合实现的 HdsActionBar 及所有 HDS 导出。

2. **ABC record 路径**（`com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/...`）：在 `module_resolver.cpp:ReplaceModuleThroughFeature()` 中将 HSP record 引用统一重定向到 `@hms:hds.hdsBaseComponent`，由上述 mock 处理。

改了 2 个仓库：`plugins`（3 文件 +333/-96）、`arkcompiler/ets_runtime`（1 文件 +10）。

### 10. HDS Stub 全面扩展（2026-05-27）

基于 SDK 声明文件和编译器白名单，将 HDS stub 从 ~20 个导出扩展到 70+，覆盖所有已声明的 HDS 组件和类型：

**从 ViewPU 迁移到 ViewV2**：`HdsActionBar` 最初继承 `ViewPU`，`isPrimaryIconChanged` 响应式更新失效。根因是 SDK 声明为 `@ComponentV2`，父组件使用 V2 专属的 `@Local` + `ObserveV2` 追踪。改为继承 `ViewV2`，用 `@Param`/`initParam`/`updateParam`/`resetParam` 替代 V1 的 `SynchedProperty` 体系。参考 `advanced_ui_component/arcbutton/interfaces/arcbutton.js`（手写 ViewV2 的完整实现）。

**闭包变量捕获修复**：`observeComponentCreation2` 回调中的值必须在回调**内部**读取 `this.xxx`，不能用外部局部变量捕获。V2 的 `updateDirtyElements()` 重执行回调时，回调内的 `this` 读取被 ObserveV2 追踪，但闭包中的局部变量已在 `initialRender()` 执行时固化。

**新增导出（50+）**：
- 18 个枚举（值严格匹配 SDK 声明）
- 7 个 Prefix 类、17 个 Suffix 类（数据容器，`this.options = options`）
- `HdsTabsController extends TabsController`（5 个 stub 方法）
- `HdsSnackBar`（no-op stub）
- 4 个 Modifier 类（`applyNormalAttribute` 空实现）
- `hdsMaterial` namespace（枚举 + `getSystemMaterialTypes()`）
- `SymbolGlyph.create()` 处理 type 40000 资源

**构建修复**：ninja 构建因 GN 状态过期出现 "multiple rules generate" 错误，运行 `build.sh` 完整重新生成 ninja 文件后解决。`llvm-objcopy` 命令格式：`-I binary -B aarch64 -O elf64-littleaarch64`。

改了 1 个文件（`hds_component_mock.js`），+421/-62 行。详细技术文档见 `agents/hms-hds-stub.md`。

### HDS 组件兼容方案

**hw_base_calendar 部分渲染**：pages/Index 导入 `HdsNavigation`、`HdsActionBar` from `@hms:hds.hdsBaseComponent`。通过嵌入式 ABC mock + module_resolver 重定向两步方案实现兼容：

1. `ReplaceModuleThroughFeature` 将 `com.huawei.hmos.hdscomponent/...` HSP record 引用统一重定向到 `@hms:hds.hdsBaseComponent`
2. NAPI stub（ABC-only 模式）提供 ViewV2 组合实现的 HdsActionBar（Row + SymbolGlyph/Image + borderRadius 圆形按钮）及 70+ HDS 导出

**已知视觉差异**：HdsActionBar mock 使用简化的 Row + Button + Image 组合，缺少 innerSpace 间距控制、isHorizontal 垂直布局、shadowStyle/backgroundBlurStyle 视觉效果。功能正确但外观与 HMS 原版差异较大。

---

## 构建与工具链

```bash
# ArkUI-X 原生构建
cd <arkui-x-source>
./build.sh --product-name arkui-x --target-os android

# 产物同步到 HOA 项目
cd <hoa-project>
ARKUI_BUILD=<path-to-build> ./scripts/sync_arkui_x.sh

# APK 打包
./gradlew assembleDebug
```

---

## 待完成

### 短期

- HdsActionBar mock 视觉完善（innerSpace 按钮间距、isHorizontal 垂直布局、shadowStyle/backgroundBlurStyle）—— 结构已就绪（ViewV2），需补充视觉属性
- Vulkan RenderContext 创建失败时明确回退到 OpenGL ES
- `@ohos.pulltorefresh` 第三方 ohpm 包支持（wan-harmony 多个页面依赖）
- `@ohos.promptAction` 插件补齐

### 中期

- 完善 Ability 生命周期回调
- 更多测试 HAP 样本验证

### 已知问题（非阻塞）

| 问题 | 影响 | 说明 |
|------|------|------|
| hw_base_calendar 部分渲染 | 视觉差异 | HDS mock 简化实现，缺少 innerSpace/isHorizontal/shadow 视觉属性 |
| Pad 窗口模式标题栏显示 "HOA" | 视觉 | Android `label` 安装时固化，运行时无法修改 |
| Vulkan RenderContext 返回 nullptr | 首次渲染可能闪烁 | 设备不支持 Vulkan，走 GLES fallback |
| `AceWebBase.<init>` NoSuchMethodException | 无 | 日志噪音，不影响 WebView 功能 |
| `stage_asset_provider.cpp` read file failed | 无 | 日志噪音，不影响渲染 |
| `bundleInfo_ is nullptr` | 无 | HOA 未实现完整 Bundle Manager |

---

## 文档索引

| 文档 | 说明 |
|------|------|
| `agents/PLAN.md` | 完整技术方案、阻塞点分析、替代方案 |
| `agents/PROGRESS.md` | 本文件，项目进展总览 |
| `agents/hms-hds-stub.md` | HDS Stub 实现方案、ViewV2 模式、构建流水线、组件委托策略 |
| `agents/ets-to-js.md` | ETS → JS transpiler 模式参考，ViewPU/V1 组件写法 |
| `docs/BUILD.md` | 构建文档、sync 脚本用法、产物清单 |
| `docs/ARKUI-X_PATCHES.md` | ArkUI-X 6.1-Release 源码修改详细说明 |
| `scripts/setup_arkui_x.sh` | ArkUI-X 源码初始化（hoa-6.1 分支） |
| `scripts/sync_arkui_x.sh` | 产物同步脚本 |
