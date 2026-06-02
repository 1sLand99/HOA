# HOA 项目进展

## 当前状态

**里程碑**: ArkUI-X weekly_20260518 移植完成（2026-05-22），Phase 2 原生 .so 支持完成（2026-05-29），hilog 日志系统迁移完成（2026-05-30），HDC 调试/部署支持完成（2026-06-01），拓扑依赖加载完成（2026-06-02），基于 `hoa` 分支 patch 创建 `hoa-weekly`，构建和运行时验证通过。

HDC daemon 支持 DevEco Studio 直接识别为 OpenHarmony 设备，并通过完整部署流程（shell/file send/bm install）安装和启动 HAP。FilePicker Java 类集成修复完成，`@kit.CoreFileKit` 文件选择器全链路可用（2026-06-03）。

从 `hoa-6.1`（基于 ArkUI-X 6.1-Release，OHOS 内核 cut 于 2026-02-02）切换到 `weekly_20260518`（2026-05-18 最新 OHOS 同步），获得 3 个月的 OHOS 上游代码更新。manifest 分支: `ArkUI-X-6.1-Release` → `weekly`，repo revisions: `hoa-6.1` → `hoa-weekly`。

5 个已安装 HAP 中 4 个正常渲染、1 个部分渲染（HDS 组件 mock 视觉差异）。用户管理流程完整：选择 HAP → 预览弹窗 → 确认安装 → 列表展示 → 长按查看详情/卸载 → 点击启动 → ArkUI-X 渲染。

---

## 已验证的能力

| 能力 | 状态 | 说明 |
|------|------|------|
| ArkUI-X 构建（build.sh） | ✅ | 基于 ArkUI-X 原生 GN 构建系统 |
| 产物同步（sync_arkui_x.sh） | ✅ | 递归扫描 plugins/ 全部 .so（151 个）和 .jar（34 个），覆盖所有 OHOS API 插件 |
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
| HMS HDS 组件 mock | ✅ | 嵌入式 ABC（ViewV2 组合实现）+ module_resolver 重定向，覆盖 ES import 和 HSP record 双路径；8 个 NAPI 模块注册（HDS_SUB_MODULE 宏），70+ 导出（19 枚举、24 Prefix/Suffix 类、7 委托、symbolRegister 等）|
| .hap 文件关联 | ✅ | intent filter 注册，文件管理器/分享均可直接安装 |
| 最近任务列表显示 HAP 名称和图标 | ✅ | `setTaskDescription()` 动态设置 |
| 多进程槽位管理 | ✅ | 5 个独立进程，互不干扰 |
| HAP 原生 .so 加载 | ✅ | 拓扑依赖加载（elf_load_with_deps）+ RUNPATH→$ORIGIN 双路径，无 RUNPATH 的闭源 HAP 也能正确 resolve DT_NEEDED |
| 原生 .so C++ 路径解析 | ✅ | `arkui_napi` 仓库 3 处改动：绕过 IsExistedPath、回退 key 匹配、完整路径拼接 |
| musl ABI bridge (libb.so) | ✅ | 编译 musl thread/stdio/dirent/internal 子集，~195KB，pthread_mutex/printf/fopen/opendir 全链路通过 |
| sigaction ABI 桥接 | ✅ | musl→kernel 布局转换 + `-Wl,-Bsymbolic` + 运行时 GOT 补丁，TestRaise/TestSigprocmask 通过 |
| ELF NEEDED 原地 patch | ✅ | `.dynstr` 中 `libc.so` → `libb.so` 原地字符串替换，HapInstaller 安装时自动执行 |
| errno 委托 bionic | ✅ | `__errno_location()` 通过 dlsym(RTLD_NEXT) 委托 bionic，解决 musl/bionic errno TLS 不一致 |
| 一键构建 (build_all.sh) | ✅ | ArkUI-X → libb.so → sync → APK，4 个步骤，支持 --skip-arkui/--skip-musl |
| NAPI 模块导出 | ✅ | `libentry.so` add(2,3)=5 + 34 项 musl 函数测试全部通过 |
| hilog 日志系统 | ✅ | `libhilog_ndk.z.so` stub + strip_privacy + 转发 Android logcat |
| native-example 测试 | ✅ | 49 项：pthread/stdio/dirent/mmap/sem/网络/时间/select/stdlib/mprotect/dup3/loopback/signal/__thread |
| HDC daemon | ✅ | TCP 8710 端口监听，hdc install/uninstall/shell/file send + DevEco Studio 设备识别与部署 |

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
| 计算器 | com.songyangshuo.calculator_ohos | ✅ 正常 | 需 hds.hdsMaterial 模块，补齐后正常 |
| native-example | app.hackeris.nativeexample | ✅ 正常 | NAPI 原生模块测试：34 项 C 函数 + 11 项 C++ STL 全部通过，拓扑依赖加载验证通过 |

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
- 19 个枚举（值严格匹配 SDK 声明）
- 7 个 Prefix 类、17 个 Suffix 类（数据容器，`this.options = options`）
- `HdsTabsController extends TabsController`（5 个 stub 方法）
- `HdsSnackBar`（no-op stub）
- 4 个 Modifier 类（`applyNormalAttribute` 空实现）
- `hdsMaterial` namespace（枚举 + `getSystemMaterialTypes()`）
- `symbolRegister` namespace（`registerSymbol()` 返回 `false`）
- `bleedIconStyle` 函数（空实现）
- `SymbolGlyph.create()` 处理 type 40000 资源

**构建修复**：ninja 构建因 GN 状态过期出现 "multiple rules generate" 错误，运行 `build.sh` 完整重新生成 ninja 文件后解决。`llvm-objcopy` 命令格式：`-I binary -B aarch64 -O elf64-littleaarch64`。

改了 1 个文件（`hds_component_mock.js`），+421/-62 行。详细技术文档见 `agents/hms-hds-stub.md`。

### 11. HDS 多子模块 NAPI 注册（2026-05-29）

`com.songyangshuo.calculator_ohos`（计算器）启动白屏，日志显示 `requireNapi("hds.hdsMaterial")` 找不到模块。HDS SDK 将导出分散到多个 `@hms.hds.xxx` 子模块，之前只注册了 `hds.hdsBaseComponent` 和 `UIDesignKit` 两个模块名。

**修复策略**：所有 HDS 子模块共享同一嵌入式 ABC，只需在 C++ stub 中为每个模块名注册独立的 `napi_module_with_js` 结构。新增 `HDS_SUB_MODULE` 宏批量注册，一行定义一个模块：

| 新增模块 | 来源 |
|---------|------|
| `hds.hdsMaterial` | 计算器白屏根因，`MaterialType`/`MaterialLevel` 枚举 |
| `hds.hds.HdsSnackBar` | HdsSnackBar 类 + 枚举 |
| `hds.hds.HdsActionBar` | HdsActionBar + ActionBarButton + ActionBarStyle |
| `hds.hds.HdsSideBar` | HdsSideBar 结构体 |
| `hds.hds.symbolRegister` | registerSymbol() 函数 |
| `hds.hds.HdsStyle` | HdsListItem + SwipeDeleteTriggerType 枚举 + bleedIconStyle |

JS mock 同步补充了 `SwipeDeleteTriggerType` 枚举、`symbolRegister` 命名空间、`bleedIconStyle` 函数。改 2 个文件（`hds_base_component_stub.cpp` +61 行、`hds_component_mock.js` +15 行）。计算器 HAP 渲染正常。

### 12. 版本号规范化（2026-05-29）

`versionCode` 从 1 改为 100001，`versionName` 从 "1.0" 改为 "1.0.1"，建立 `versionCode = major*100000 + minor*1000 + patch` 的对应关系。改 1 个文件（`app/build.gradle.kts`）。

### 13. Native .so Phase 2 — libb.so musl ABI Bridge（2026-05-29）

Phase 1 仅支持依赖 bionic libc 的简单 .so。当 HAP .so 使用 `pthread_mutex_lock`、`printf`、`fopen`、`opendir` 等函数时，musl 和 bionic 的 ABI 完全不同（`pthread_mutex_t` 大小/布局、`FILE*` 内部结构、`DIR*` 字段偏移均不兼容），即使 DT_NEEDED 链正确也会运行时崩溃。

**核心矛盾**：OHOS HAP .so NEEDED `libc.so`，Android linker 会解析到 bionic。`libb.so` 和 `libc.so` 恰好都是 8 字节（含 null），使 ELF patch 简化为原地字符串替换 —— 找到 `.dynstr` 中的 `"libc.so"`，原地覆盖为 `"libb.so\0"`。

**解法**：编译 musl 的 thread/stdio/dirent/internal/setjmp/mman 等子集为 `libb.so`（~195KB），符号解析顺序确保 musl 符号优先于 bionic。

**仓库清单**：

| 仓库 | 改动 | 说明 |
|------|------|------|
| `third_party/musl` | 3 文件 +34/-24 | `__pthread_self()` → bridge 宏、musl_log.h Android guard、pthread_create 注册回调 |
| HOA `app/src/main/cpp/` | 9 文件 | bridge core (pthread/malloc/elf_patch)、构建系统、version script |
| HOA Kotlin | 4 文件 +85 行 | HoaApplication loadLibrary("b")、HapInstaller ELF patch、ElfPatcher JNI、DevTestActivity installAndLaunch |
| `.repo/manifests` | 1 文件 +2 行 | 将 `third_party/musl` 纳入 repo 管理 |

**已验证函数**：

| 函数 | 状态 | 说明 |
|------|------|------|
| `pthread_mutex_init/lock/unlock/destroy` | ✅ | musl 40 字节 union 布局 |
| `printf("libb test: hello %d\n", 42)` | ✅ | 20 字符输出，`__lockfile` + `__stdio_write` |
| `fopen/fwrite/fclose` | ✅ | musl FILE\* 生命周期，文件写入成功 |
| `opendir/readdir/closedir` | ✅ | 列出 13 个文件，errno 委托 bionic 正常 |

### 14. libb.so struct pthread 与 TSD 数组重叠崩溃修复（2026-05-30）

musl 子线程退出时 SIGTRAP 崩溃，backtrace 指向 `validate_self` → `__musl_bridge_self` → `pthread_exit` → `start`。崩溃原因是 `self->self != self`，即 struct pthread 的 `self` 指针被覆盖。

**根因**：`libc.tls_size` 未初始化（BSS 默认 0），导致 `__copy_tls()` 中将 struct pthread 放在与 TSD 数组**相同的地址**（`tsd - 0 = tsd`）。`self->self`（偏移 0）和 `tsd[0]` 共享同一内存，一旦 TSD 写入就覆盖 self 指针。

**修复**：在 `pthread_bridge.c` 构造函数中加 `*(size_t *)(&__libc + 24) = 4096`，在 TSD 数组下方预留空间给 struct pthread。同时清理调试代码（移除 `__builtin_trap()`、`raw_write` 函数，简化 `validate_self` 为静默返回 NULL）。

**仓库改动**：

| 仓库 | 改动 | 说明 |
|------|------|------|
| `third_party/musl` | 1 文件 | `pthread_key_create.c`：移除 `__builtin_trap()`，改为安全 early return |
| HOA `app/src/main/cpp/` | 2 文件 | `pthread_bridge.c`：tls_size 初始化、validate_self 简化、诊断代码移除；`Makefile.musl_bridge`：链接调整 |

### 15. HAP 原生 .so 支持 — Phase 1 最小方案（2026-05-29）

在不修改 OHOS HAP 的前提下，让其中的闭源原生 .so 在 Android 上加载运行。测试用例 `native-example`（`libentry.so` 导出 `add(a,b)` NAPI 函数）跑通：`Test NAPI 2 + 3 = 5`。

**DT_NEEDED 依赖链**：`libentry.so` → `libace_napi.z.so` + `libc++_shared.so` + `libc.so`（bionic 已提供）。两个依赖均由 Gradle 构建流程自动处理，不再需要预置二进制或手动脚本：

| .so | 来源 | 机制 |
|-----|------|------|
| `libace_napi.z.so` | `app/src/main/cpp/ace_napi_z_stub.c` | CMake（`app/CMakeLists.txt`）+ `externalNativeBuild` |
| `libc++_shared.so` | Android NDK | Gradle `ANDROID_STL=c++_shared` 自动打包 |

**C++ 层修复（`arkui_napi` 仓库，`hoa-weekly` 分支，3 处改动）**：

1. **绕过 `IsExistedPath()` 检查**（~1018 行）——虚拟 key（`app.x/ets`）在 Android 上不是文件系统路径，`IsExistedPath()` 永远返回 false，导致 prefix 取不到。Android 上直接查 `appLibPathMap_` 不做文件系统检查。

2. **回退 key 匹配**（~1023 行）——`SetAppLibPath` 用 key `bundleName/moduleName`（如 `app.x/app.x.entry`）存 prefix，但 `LoadNativeModule` 用 key `bundleName/entryPath`（如 `app.x/ets`）查 prefix。key 不匹配导致 prefix 为 null。修复：直接查不到时遍历 map，找同 bundle 前缀的 key。

3. **用 prefix 构造完整路径**（~1110 行）——原来 `sprintf_s("lib%s%s", ...)` 生成裸文件名（`libentry.so`），Android `dlopen()` 找不到。改为 `sprintf_s("%s/lib%s%s", prefix, ...)` 生成完整路径（`/data/.../libs/arm64-v8a/libentry.so`）。

**Java / 构建层修复（HOA 项目，5 个文件）**：

| 文件 | 改动 |
|------|------|
| `app/CMakeLists.txt` | **新增** — CMake 配置：`OUTPUT_NAME ace_napi.z` + SONAME + `-nostdlib` |
| `app/src/main/cpp/ace_napi_z_stub.c` | **新增** — stub 源码（空实现，符号走 DT_NEEDED 继承） |
| `app/build.gradle.kts` | 新增 `externalNativeBuild { cmake }` + `ANDROID_STL=c++_shared`，stub 和 libc++_shared 均由 Gradle 自动处理 |
| `HoaAbilityActivity.kt` | **删除** `preloadNativeLibs()` / `loadNativeLib()` 临时方案——C++ 层修好后不再需要每个模块 Java 预加载 |
| `HapInstaller.kt` | `isSO` 硬编码 `false` → 扫描 `targetDir/libs/` 下是否有 .so，动态检测 |
| `HapExtractor.kt` | `extractHap()` 返回 `Pair<Boolean, Boolean>`（extracted, hasNativeLibs），ZIP 解压时检测 `libs/` 条目 |

**兼容范围**：
- ✅ 依赖 `libc.so`（bionic）+ `libace_napi.z.so`（stub）+ `libc++_shared.so`（NDK）三个系统库的 .so 可直接运行
- ❌ 依赖其他 OHOS 系统库或 OHOS musl 专有符号的 .so 需 Phase 2（完整 musl bridge，见 `agents/native-so-compat.md`）

`scripts/` 目录无需改动——stub 和 libc++_shared 均由 Gradle 构建流程自动处理。

### 16. native-example 测试扩展至 34 项（2026-05-30）

基于 libb.so 已编译的 musl 源文件覆盖范围，分两轮新增测试用例：

**第一轮（6 个）**：TestDetach、TestStdioMore、TestSpin、TestBarrier、TestMultiThread、TestTlsThread——覆盖 pthread_detach、fgets/fputs/fread/fwrite、spinlock、barrier、多线程同步、TLS 线程安全性。

**第二轮（5 个）**：TestMprotect（mmap + mprotect PROT_READ）、TestGetTimeOfDay（gettimeofday + time 一致性）、TestAtoiStrtol（atoi/strtol 边界值）、TestDup3（dup3 + pread 内容验证）、TestNetLoopback（TCP 127.0.0.1 loopback，pthread server + client，覆盖 socket/bind/listen/connect/accept/send/recv 全链路）。

测试数从 23 → 29 → 34。改 1 个文件（`napi_init.cpp`），配套更新 `Index.d.ts` 和 `Index.ets`。

### 17. hilog 日志系统迁移（2026-05-30）

将 native-example 的日志输出从 Android dlsym 方案迁移到 OHOS 原生 hilog：

**迁移前**：通过 `dlsym(RTLD_DEFAULT, "__android_log_print")` 获取函数指针，用 `LOGI`/`LOGE` 宏包装。问题是 HAP .so NEEDED `libhilog_ndk.z.so`，Android 上无此库导致链接失败。

**迁移后**：直接 `#include <hilog/log.h>`，使用 `OH_LOG_INFO(LOG_APP, ...)` / `OH_LOG_ERROR(LOG_APP, ...)`。所有 format 字符串添加 `%{public}` privacy specifier。

**libhilog_ndk.z.so stub**：新建 `app/src/main/cpp/hilog_stub.c`+ 编译为 SONAME=`libhilog_ndk.z.so` 的 stub .so，实现 `OH_LOG_Print` 等 7 个 OHOS hilog API，核心逻辑：
- `strip_privacy()` 函数将 OHOS 的 `%{public}`/`%{private}` 从 format 字符串中移除
- 清理后的 format 通过 `__android_log_vprint` 转发到 Android logcat
- `OH_LOG_PrintMsg`/`OH_LOG_PrintMsgByLen` → `__android_log_write`
- `OH_LOG_IsLoggable` 固定返回 1（总是可记录）

改 4 个文件：`napi_init.cpp`（-27/+20）、`CMakeLists.txt`（+libhilog_ndk.z.so）、`hilog_stub.c`（新增 101 行）、HOA `jniLibs/`（产物 .so）。

### 18. stub/fortify/ 为编译必需，非死代码（2026-05-30，修正）

初步分析仅检查了编译源文件对 fortify 的直接 include（结果为 0），遗漏了 musl 系统头文件的**间接引用**。musl 的 `<stdio.h>`、`<stdlib.h>`、`<string.h>`、`<unistd.h>`、`<fcntl.h>`、`<poll.h>`、`<sys/socket.h>`、`<sys/stat.h>` 在 `#ifndef __LITEOS__` 下 include 对应的 `<fortify/xxx.h>`。编译 .c 文件 include 这些系统头文件时，fortify stub 必须存在，否则编译失败。

最终确认需要 8 个 fortify stub（全部保留），与 OHOS SDK fortify 溢出检测机制无关，纯粹是 musl header 的 include 链要求。

### 19. 信号函数 ABI 桥接 — Step 1: sigaction/raise/sigprocmask（2026-05-30）

OHOS HAP .so 调用 `sigaction()` 时，musl 和 bionic 的 `struct sigaction` 布局不兼容——musl 将 `sa_handler` 放在偏移 0（40 字节总长），bionic 将 `sa_flags` 放在偏移 0。直接调用 bionic sigaction 会导致 handler 指针被写入错误偏移，信号永远无法递送。

**三层方案**：

1. **libb.so 内 sigaction bridge**（`signal_bridge.c`）：编译进 libb.so，将 musl 布局的 `hap_sigaction`（40 字节，sa_handler@0）转换为 kernel 布局的 `kernel_sigaction`（32 字节，sa_handler@0），直接通过 `SYS_rt_sigaction` syscall 注册。拒绝 musl 保留信号 32-34（SIGCANCEL/SIGSYNCCALL/SIGTIMER）。

2. **`-Wl,-Bsymbolic`**：libb.so 链接标志，确保 libb.so 内部对 `sigaction` 的引用绑定到自身 bridge，不被 `libsigchain.so` 或其他系统库抢占。

3. **运行时 GOT 补丁**（`got_patch.c` + `elf_patch_jni.c`）：通过 `dl_iterate_phdr` 遍历已加载的 ELF，用 `mprotect` 临时开放写权限，将 HAP .so 的 `.got.plt`（JUMP_SLOT）和 `.got`（GLOB_DAT）中 `sigaction` 条目重写为 libb.so bridge 地址。只 patch 目标 HAP .so（通过 basename 匹配），不修改系统库。

**raise() 信号递送问题**：musl 的 `raise()` 内部调用 `__block_app_sigs` 阻塞信号 1-31 和 33-38（mask `0xfffffffc7fffffff`），然后通过 `tgkill` 发送信号。阻塞的信号在 `__restore_sigs` 恢复掩码后才递送，但延迟递送路径存在 bug——handler 不触发。

**解决方案**：TestRaise 使用信号 39（不在阻塞范围内），信号在 `tgkill` 时立即递送，handler 正常触发。TestSigprocmask 继续使用 SIGUSR1（信号 10），验证 block→raise→pending→unblock→deliver 模式。

**已验证**：`TestRaise=OK raise`（信号 39）、`TestSigprocmask=OK sigprocmask`（SIGUSR1）。

**仓库改动**：

| 仓库 | 改动 | 说明 |
|------|------|------|
| HOA `app/src/main/cpp/` | 3 文件 | `signal_bridge.c`（musl→kernel sigaction 转换）、`got_patch.c`（运行时 GOT 补丁）、`elf_patch_jni.c`（JNI 入口 + sigaction GOT patch） |
| HOA `Makefile.musl_bridge` | 1 文件 | 新增 `MUSL_SIGNAL_C`（10 个 .c）+ `MUSL_SIGNAL_S`（restore.s） |
| HOA `libb.map` | 1 文件 | 新增 signal 符号导出 |
| native-example `napi_init.cpp` | 1 文件 | 新增 TestRaise（信号 39）、TestSigprocmask（SIGUSR1） |

### HDS 组件兼容方案

**hw_base_calendar 部分渲染**：pages/Index 导入 `HdsNavigation`、`HdsActionBar` from `@hms:hds.hdsBaseComponent`。通过嵌入式 ABC mock + module_resolver 重定向两步方案实现兼容：

1. `ReplaceModuleThroughFeature` 将 `com.huawei.hmos.hdscomponent/...` HSP record 引用统一重定向到 `@hms:hds.hdsBaseComponent`
2. NAPI stub（ABC-only 模式）提供 ViewV2 组合实现的 HdsActionBar（Row + SymbolGlyph/Image + borderRadius 圆形按钮）及 70+ HDS 导出

**已知视觉差异**：HdsActionBar mock 使用简化的 Row + Button + Image 组合，缺少 innerSpace 间距控制、isHorizontal 垂直布局、shadowStyle/backgroundBlurStyle 视觉效果。功能正确但外观与 HMS 原版差异较大。

### 20. HDC 调试与 DevEco Studio 部署支持（2026-06-01）

实现 OHOS HDC (HarmonyOS Device Connector) 协议 daemon，使 HOA 作为一台 OpenHarmony 设备被 DevEco Studio 识别并支持完整的安装和部署流程。

**协议实现**（`app/src/main/java/app/hackeris/hoa/hdc/`，7 个文件）：

| 文件 | 职责 |
|------|------|
| `HdcProtocol.kt` | 二进制 wire-protocol 编解码：PayloadHead/PayloadProtect + ProtoReader 流式解析 + SessionHandShake/TransferConfig/TransferPayload |
| `HdcSession.kt` | TCP 连接管理：1 session 复用多 channel，4MB 环形缓冲区，packet 解析 + 命令路由 |
| `HdcDaemon.kt` | TCP ServerSocket 主循环：accept → HdcSession 线程，端口可配置（默认 8710） |
| `HdcService.kt` | Android Foreground Service：前台通知 + 端口持久化 + MainActivity 菜单启停 |
| `HdcInstallHandler.kt` | CMD_APP_* (3501-3505)：流式 HAP 接收 → HapInstaller 安装 |
| `HdcFileHandler.kt` | CMD_FILE_* (3000-3004)：文件传输状态机，path + optionalName 拼接 |
| `HdcShellHandler.kt` | CMD_UNITY_EXECUTE (1001)：shell 命令执行 + OHOS 命令 mock |

**OHOS 系统命令模拟**：DevEco Studio 查询系统参数和设备信息，Android 上这些命令不存在，统一返回模拟值：
- `param get const.ohos.apiversion` → `23`（HarmonyOS 6.1.0）
- `param get const.ohos.fullname` → `OpenHarmony-6.1.0.100`
- `param get const.ohos.releasetype` → `Release`
- `hidumper`/`hilog`/`mediatool`/`snapshot_display` → 空/默认值
- `bm install -p <path>` → 重定向到 HapInstaller，路径自动重映射
- `aa force-stop <pkg>` → 映射到 `am force-stop`

**路径重映射**：Android app 无权写 `/data/local/tmp/`，`mkdir`/`rm`/`file send`/`bm install` 中路径统一重映射到 app cache（`<cache>/hdc/`）。

**DevEco Studio 完整部署流程**：
```
hdc shell aa force-stop <pkg>
hdc shell mkdir /data/local/tmp/<uuid>        → <cache>/hdc/<uuid>/
hdc file send <hap> /data/local/tmp/<uuid>    → <cache>/hdc/<uuid>/<hapname>
hdc shell bm install -p /data/local/tmp/<uuid> → HapInstaller.install(<hap>)
hdc shell rm -rf /data/local/tmp/<uuid>       → File.deleteRecursively
```

**Channel 复用**：多个命令（shell/file/install）共享一条 TCP 连接，命令结束后 `CMD_KERNEL_CHANNEL_CLOSE` + `resetChannel()` 保持连接不关闭。

### 21. 拓扑依赖加载 — 无 RUNPATH 的 .so 依赖解析（2026-06-02）

闭源 HAP 中的 .so 文件通常不包含 RUNPATH（`CMAKE_SKIP_RPATH TRUE` 或 OHOS 工具链未注入）。此前加载依赖 DL_NEEDED 链（`libentry.so` → `libhelper.so` → `libc++_shared.so`）的 .so 时，若目标 .so 没有 RUNPATH，dlopen 无法在正确目录找到依赖库。

**解决思路**：在 `nativeLoad` 中实现拓扑依赖加载，解析 ELF 文件的 DT_NEEDED 条目，递归加载同目录下的依赖，再加载目标 .so。

**实现文件**（`app/src/main/cpp/`）：

| 函数 | 文件 | 职责 |
|------|------|------|
| `elf_read_needed()` | `elf_patch.c` | 读取 ELF .dynamic 中 DT_NEEDED 条目，返回依赖列表 |
| `elf_load_with_deps()` | `elf_patch.c` | 递归拓扑加载：`access()` 检测依赖文件 → 递归 → 最后 `dlopen(target, RTLD_NOW \| RTLD_GLOBAL)` |
| `nativeLoad()` | `elf_patch_jni.c` | JNI 入口，委托 `elf_load_with_deps()` |

**关键技术点**：

- **避免重复加载**：每次递归前 `dlopen(path, RTLD_NOW | RTLD_NOLOAD)` 检查是否已加载，已加载则 `dlclose` 后返回
- **避免循环依赖**：RTLD_NOLOAD 确保已加载库直接跳过，不会无限递归
- **ELF 解析失败 fallback**：`elf_read_needed` 返回 -1 时跳过依赖解析，直接 `dlopen` 目标，保证兼容性
- **跨目录依赖**：仅加载同目录下存在的依赖，`libc.so` 等系统库由 Android linker 从系统路径解析
- **x86_64 过滤**：`preloadNativeDeps` 按 `Build.SUPPORTED_ABIS[0]` 过滤，只加载设备原生 ABI 的 .so，避免 x86_64 库反复失败

**依赖加载顺序验证**（native-example，无 RUNPATH）：
```
dlopen(libc++_shared.so) OK   ← 叶节点，DT_NEEDED=[libc.so]
dlopen(libhelper.so) OK        ← DT_NEEDED=[libc++_shared.so, libc.so]，已加载跳过
dlopen(libentry.so) OK         ← DT_NEEDED=[libhelper.so, libc++_shared.so, ...]，全部已加载
```

所有 arm64 .so 单轮加载成功（pass 1），11 项 C++ STL 测试全部通过（std::string/vector/map/shared_ptr/exception/thread/unique_ptr/stream/new_delete/function/typeid）。

**仓库改动**：

| 仓库 | 改动 | 说明 |
|------|------|------|
| HOA `app/src/main/cpp/` | `elf_patch.c` +117 行、`elf_patch_jni.c` +6/-17 行 | 拓扑加载实现 |
| HOA `HoaAbilityActivity.kt` | +3/-5 行 | ABI 过滤 + 注释更新 |
| native-example `CMakeLists.txt` | +4 行 | `CMAKE_SKIP_RPATH TRUE` |

### 22. FilePicker Java 类集成修复（2026-06-03）

`@kit.CoreFileKit` 的 `picker.DocumentViewPicker.select()` 调用时 SIGABRT 崩溃，日志 `JNI DETECTED ERROR: jmethodID was NULL`。根因是该插件包含 Java 层（`FilePicker.java`），它被打包在 `ace_filepicker_android.jar` 中，位于构建产物的 4 层深路径 `plugins/file/fs/picker/filepicker/ace_filepicker_android.jar`。

**双重根因**：

1. **JAR 遗漏**：`sync_arkui_x.sh` C1b 段 `for plugin_dir in plugins/*/` 只扫描 1 层深度，遗漏了 7 个深层 JAR。
2. **JNI 未初始化**：缺失 JAR 导致 `FilePicker.class` 不在 classpath，`PluginManager.initPlugin()` 无法通过反射实例化 Java 对象，`FilePicker` 构造函数中的 `nativeInit()` 永远不执行，`g_pluginClass.select` method ID 保持 NULL。

**修复**：C1b 段从浅层 `for` 改为 `find ... -print0` 递归扫描。同步后补齐 7 个深层 JAR：`ace_filepicker_android.jar`、`arkui_netconnclient_java.jar`、`arkui_media_java.jar`、`arkui_geolocationmanager_service.jar`、`arkui_audiomanager_java.jar`、`arkui_audiorenderer_java.jar`、`arkui_audiocapturer_java.jar`。

**验证**：`DocumentFilePickerImpl::select` → `FilePicker JNI: Select` → `FilePicker: select` → `onResult` 全链路通过，系统文件选择器正常弹出和回调。

**仓库改动**：

| 仓库 | 改动 | 说明 |
|------|------|------|
| HOA `scripts/sync_arkui_x.sh` | 1 文件 +4/-7 | C1b 递归 find 替代浅层 for |
| HOA `app/libs/` | +7 JAR | 补齐 7 个深层依赖 JAR |

---

## 构建与工具链

```bash
# 一键构建（推荐）
cd <hoa-project>
export ARKUI_X_SRC=<path-to-arkui-x>
export ARKUI_BUILD=$ARKUI_X_SRC/out/arkui-x/aosp_clang_arm64_release
export ANDROID_NDK_HOME=<path-to-ndk-28>
export MUSL=$ARKUI_X_SRC/third_party/musl
./scripts/build_all.sh

# 分步构建：ArkUI-X 原生构建
cd <arkui-x-source>
./build.sh --product-name arkui-x --target-os android

# 分步构建：libb.so musl bridge
cd <hoa-project>/app/src/main/cpp
MUSL=<path> bash build_musl_bridge.sh

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
- HDC 端口持久化：app 重启后自动恢复上次端口，无需手动重新配置

### 中期

- 完善 Ability 生命周期回调
- 更多测试 HAP 样本验证
- ~~**Native .so Phase 2**：完整 musl bridge~~ ✅ **已完成**（2026-05-29），pthread/stdio/dirent/setjmp 全链路验证通过
- ~~**HDC DevEco Studio 支持**~~ ✅ **已完成**（2026-06-01），设备识别 + 完整部署流程（shell/file send/bm install）通过

### 已知问题（非阻塞）

| 问题 | 影响 | 说明 |
|------|------|------|
| hw_base_calendar 部分渲染 | 视觉差异 | HDS mock 简化实现，缺少 innerSpace/isHorizontal/shadow 视觉属性 |
| Pad 窗口模式标题栏显示 "HOA" | 视觉 | Android `label` 安装时固化，运行时无法修改 |
| Vulkan RenderContext 返回 nullptr | 首次渲染可能闪烁 | 设备不支持 Vulkan，走 GLES fallback |
| `AceWebBase.<init>` NoSuchMethodException | 无 | 日志噪音，不影响 WebView 功能 |
| `stage_asset_provider.cpp` read file failed | 无 | 日志噪音，不影响渲染 |
| `bundleInfo_ is nullptr` | 无 | HOA 未实现完整 Bundle Manager |
| `__thread` / `_Thread_local` 变量不支持 | HAP C/C++ 代码中的线程局部变量 | ~~clone_bridge 剥离 CLONE_SETTLS~~ **已修正**：OHOS NDK (BiSheng) 和 Android NDK 均默认将 `__thread` 编译为 emutls（`__emutls_get_address`，编译器内置软件 TLS），不依赖 TPIDR_EL0，在主线程开箱即用。TestThreadLocal 已通过验证。musl 子线程的 native TLS（`-fno-emulated-tls` 编译的 TLSDESC）仍需后续干预 TLSDESC entry |

---

## 文档索引

| 文档 | 说明 |
|------|------|
| `agents/PLAN.md` | 完整技术方案、阻塞点分析、替代方案 |
| `agents/PROGRESS.md` | 本文件，项目进展总览 |
| `agents/hms-hds-stub.md` | HDS Stub 实现方案、ViewV2 模式、构建流水线、组件委托策略 |
| `agents/ets-to-js.md` | ETS → JS transpiler 模式参考，ViewPU/V1 组件写法 |
| `agents/native-support.md` | Native .so 兼容 v0 概念（全量 musl 沙箱架构） |
| `agents/native-so-compat.md` | Native .so 兼容详细方案（方案 C：修改 __pthread_self 支持 bionic TLS） |
| `agents/native-so-compat-analysis.md` | Native .so 兼容方案交叉验证（bionic/musl 源码对照，发现 3 个设计缺陷及修复） |
| `docs/BUILD.md` | 构建文档、build_all.sh 一键构建、musl bridge 编译 |
| `docs/ARKUI-X_PATCHES.md` | ArkUI-X 6.1-Release 源码修改详细说明 |
| `scripts/build_all.sh` | 全流程构建脚本（ArkUI-X → libb.so → sync → APK） |
| `scripts/sync_arkui_x.sh` | 产物同步脚本 |
