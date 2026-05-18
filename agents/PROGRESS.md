# HOA 项目进展

## 当前状态

**里程碑**: 多 HAP 验证通过，完整管理闭环就绪（2026-05-18）。

5 个已安装 HAP 中 4 个正常渲染，轻松来记账因缺失 `libdata_distributedkvstore.so` 卡启动图的问题已修复。hw_base_calendar 因依赖华为专有 HMS SDK 无法运行（非 HOA 代码问题）。

用户管理流程完整：选择 HAP → 预览弹窗（应用信息/权限/Ability 列表）→ 确认安装 → 列表展示（含图标和名称）→ 长按查看详情/卸载 → 点击启动 → ArkUI-X 渲染。

---

## 已验证的能力

| 能力 | 状态 | 说明 |
|------|------|------|
| ArkUI-X 构建（build.sh） | ✅ | 基于 ArkUI-X 原生 GN 构建系统 |
| 产物同步（sync_arkui_x.sh） | ✅ | 遍历 plugins/*/ 全部子目录，151 个 .so，覆盖所有 OHOS API 插件 |
| HAP 解析与安装 | ✅ | HapBundleLoader 解压 HAP → HapInstaller 写入 filesDir/hap/ |
| 安装前预览弹窗 | ✅ | 展示 bundleName、module、version、SDK、权限、Ability 列表 |
| 应用信息详情 | ✅ | 长按 HAP 列表弹出菜单，查看完整信息（含 vendor、大小、页面列表） |
| resources.index 解析 | ✅ | V1/V2 双版本解析器，`$string:xxx` 标签正确 resolve |
| HAP 列表图标与名称 | ✅ | 从 module.json + resources.index 动态解析 |
| ETS VM 创建 | ✅ | ES module 模式 |
| OHOS ABC 加载 | ✅ | modules.abc 中的 EntryAbility + Index 均加载成功 |
| ArkUI 渲染 | ✅ | StageActivity + SurfaceView + Skia 管线正常 |
| MainActivity HAP 管理 | ✅ | 安装 / 预览 / 列表 / 启动 / 详情 / 卸载 功能完整 |
| @ohos NAPI 模块全量加载 | ✅ | plugins/*/ 全部 .so 随 APK 分发 |
| OHOS → Android 权限映射 | ✅ | INTERNET 等普通权限绕过 JNI 直接授予，危险权限走运行时流程 |
| .hap 文件关联 | ✅ | intent filter 注册，文件管理器/分享均可直接安装 |
| 最近任务列表显示 HAP 名称和图标 | ✅ | `setTaskDescription()` 动态设置 |
| 多进程槽位管理 | ✅ | 5 个独立进程，互不干扰 |

---

## 关键突破

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
| 轻松来记账 | com.qiuhaotc.billingrecords | ✅ **已修复** | 补齐 libdata_distributedkvstore.so 后正常 |
| hw_base_calendar | com.hw.base_calendar | ❌ 不兼容 | 依赖 `@hms:hds.hdsBaseComponent`（HMS Design System），华为专有 SDK，ArkUI-X 不含 |

### 不兼容分析

**hw_base_calendar 白屏**：pages/Index 尝试导入 `HdsNavigation` from `@hms:hds.hdsBaseComponent`。该模块在 ArkUI-X 中不存在（`LoadNativeModule @hms:hds.hdsBaseComponent failed`），导致 `initialRender()` 抛出 `SyntaxError` → 白屏。这是平台兼容性差距，非 HOA 代码 bug。

无法修复：HMS SDK 是华为专有闭源组件，仅在真实 HarmonyOS 设备上可用。

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

- WebView 插件 init 失败（`NoSuchMethodException: AceWebBase.<init>` — JAR 版本不匹配）
- Vulkan RenderContext 创建失败时明确回退到 OpenGL ES
- `@ohos.pulltorefresh` 第三方 ohpm 包支持（wan-harmony 多个页面依赖）
- `@ohos.promptAction` 插件补齐

### 中期

- 完善 Ability 生命周期回调
- 更多测试 HAP 样本验证

### 已知问题（非阻塞）

| 问题 | 影响 | 说明 |
|------|------|------|
| hw_base_calendar 白屏 | 该 HAP 不可用 | 依赖 HMS SDK，ArkUI-X 不含 |
| Pad 窗口模式标题栏显示 "HOA" | 视觉 | Android `label` 安装时固化，运行时无法修改 |
| Vulkan RenderContext 返回 nullptr | 首次渲染可能闪烁 | 设备不支持 Vulkan，走 GLES fallback |
| WebView 插件 init 失败 | WebPage 页面不可用 | `AceWebBase.<init>` 构造函数签名不匹配 |
| `stage_asset_provider.cpp` read file failed | 无 | 日志噪音，不影响渲染 |
| `bundleInfo_ is nullptr` | 无 | HOA 未实现完整 Bundle Manager |

---

## 文档索引

| 文档 | 说明 |
|------|------|
| `agents/PLAN.md` | 完整技术方案、阻塞点分析、替代方案 |
| `agents/PROGRESS.md` | 本文件，项目进展总览 |
| `docs/BUILD.md` | 构建文档、sync 脚本用法、产物清单 |
| `docs/ARKUI-X_PATCHES.md` | ArkUI-X 源码修改详细说明 |
| `scripts/sync_arkui_x.sh` | 产物同步脚本 |
