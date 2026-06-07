# OpenHarmony API 兼容性分析

基于 ArkUI-X weekly_20260518 构建的运行时，对照 OpenHarmony SDK API 声明文件，分析已支持和缺失的 API。

**OHOS SDK 版本**: API 22 (6.0.2), 共 619 个 API 声明文件  
**ArkUI-X 基线**: weekly_20260518  
**分析日期**: 2026-05-23

---

## 总体概览

| 类别 | 数量 |
|------|------|
| OHOS SDK API 声明文件 | ~619 |
| ArkUI-X 已实现插件 (.so) | ~152 |
| 其中 ace_engine NAPI kits（router/prompt/animator 等） | ~30 |
| 插件目录模块 (plugins/) | ~106 |
| arkui 组件 (.so) | ~16 |

---

## 一、已支持的 API

### 1.1 基础工具类 (完整覆盖)

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.buffer` | plugins/buffer |
| `@ohos.convertxml` | plugins/convertxml |
| `@ohos.hilog` | plugins/hilog |
| `@ohos.hiTraceMeter` | plugins/hitrace_meter |
| `@ohos.i18n` | plugins/i18n |
| `@ohos.intl` | plugins/intl |
| `@ohos.process` | plugins/process |
| `@ohos.uri` | plugins/uri |
| `@ohos.url` | plugins/url |
| `@ohos.util.*` (ArrayList, Deque, HashMap, HashSet, LightWeightMap, LightWeightSet, LinkedList, List, PlainArray, Queue, Stack, TreeMap, TreeSet, json, stream) | plugins/util/* |
| `@ohos.xml` | plugins/xml |
| `@ohos.zlib` | plugins/zlib |
| `@ohos.taskpool` | plugins/taskpool |
| `@ohos.worker` | plugins/worker |
| `@ohos.systemDateTime` | plugins/system_date_time |

### 1.2 事件与通知

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.commonEventManager` | plugins/common_event_manager |
| `@ohos.events.emitter` | plugins/events/emitter |
| `@ohos.notificationManager` | plugins/notification_manager |
| `@ohos.hiviewdfx.hiAppEvent` | plugins/hiviewdfx/hiappevent |

### 1.3 文件与数据

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.file.fs` | plugins/file/fs |
| `@ohos.file.hash` | plugins/file/hash |
| `@ohos.file.picker` | plugins/file/picker |
| `@ohos.file.photoAccessHelper` | plugins/file/photo_access_helper |
| `@ohos.file.statvfs` | plugins/file/statvfs |
| `@ohos.data.preferences` | plugins/data/preferences |
| `@ohos.data.relationalStore` | plugins/data/relationalstore |
| `@ohos.data.distributedKVStore` | plugins/data/distributedkvstore |
| `@ohos.data.dataSharePredicates` | plugins/data/datasharepredicates |
| `@ohos.data.unifiedDataChannel` | plugins/data/unifieddatachannel |
| `@ohos.data.uniformTypeDescriptor` | plugins/data/uniformtypedescriptor |

### 1.4 网络

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.net.connection` | plugins/net/connection |
| `@ohos.net.http` | plugins/net/http |
| `@ohos.net.socket` | plugins/net/socket |
| `@ohos.net.webSocket` | plugins/net/websocket |
| `@ohos.request` | plugins/request |
| `@ohos.wifiManager` | plugins/wifi_manager |

### 1.5 多媒体

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.multimedia.audio` | plugins/multimedia/audio |
| `@ohos.multimedia.media` | plugins/multimedia/media |

### 1.6 蓝牙（部分）

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.bluetooth.access` | plugins/bluetooth/access |
| `@ohos.bluetooth.a2dp` | plugins/bluetooth/a2dp |
| `@ohos.bluetooth.baseProfile` | plugins/bluetooth/baseprofile |
| `@ohos.bluetooth.ble` | plugins/bluetooth/ble |
| `@ohos.bluetooth.connection` | plugins/bluetooth/connection |

### 1.7 安全（部分）

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.security.cert` | plugins/security/cert |
| `@ohos.security.cryptoFramework` | plugins/security/cryptoframework |
| `@ohos.abilityAccessCtrl` | plugins/ability_access_ctrl |

### 1.8 设备与系统

| OHOS API | ArkUI-X 实现 |
|----------|-------------|
| `@ohos.accessibility` | plugins/accessibility |
| `@ohos.deviceInfo` | plugins/device_info |
| `@ohos.display` | plugins/display |
| `@ohos.geoLocationManager` | plugins/geo_location_manager |
| `@ohos.pasteboard` | plugins/pasteboard |
| `@ohos.runningLock` | plugins/running_lock |
| `@ohos.vibrator` | plugins/vibrator |

### 1.9 ArkUI 核心 API（通过 ace_engine NAPI kits）

这些 API 位于 `foundation/arkui/ace_engine/interfaces/napi/kits/`，构建为独立 .so 文件：

| OHOS API | so 文件 | 说明 |
|----------|---------|------|
| `@ohos.router` | librouter.so | 页面路由 |
| `@ohos.promptAction` | libpromptaction.so | 对话框/Toast/ActionSheet |
| `@ohos.prompt` | (kits/prompt) | 基础 prompt |
| `@ohos.animator` | libanimator.so | 动画器 |
| `@ohos.font` | libfont.so | 字体管理 |
| `@ohos.measure` | libmeasure.so | 文本测量 |
| `@ohos.mediaquery` | libmediaquery.so | 媒体查询 |
| `@ohos.pluginComponent` | libplatformview.so | 插件组件 |
| `@ohos.arkui.inspector` | libarkui_inspector.so | UI 检查 |
| `@ohos.arkui.dragController` | libarkui_dragcontroller.so | 拖拽控制 |
| `@ohos.arkui.componentSnapshot` | libarkui_componentsnapshot.so | 组件截图 |
| `@ohos.arkui.componentUtils` | libarkui_componentutils.so | 组件工具 |
| `@ohos.arkui.observer` | libarkui_observer.so | UI 观察者 |
| `@ohos.arkui.drawableDescriptor` | libarkui_drawabledescriptor.so | Drawable 描述符 |
| `@ohos.configuration` | libconfiguration.so | 配置管理 |
| `@ohos.overlay` | liboverlay.so | 浮层管理 |
| `@ohos.graphics.text` | libgraphics_text.so | 图形文本 |
| `@ohos.effectKit` | libeffectkit.so | 特效 |
| `@ohos.web.webview` | libweb_webview.so | WebView |

### 1.10 ArkUI 框架层 API（StageApplication 内置）

这些 API 由 ArkUI-X 的 StageApplication/StageAssetProvider 在框架层实现，不需要独立 .so：

| OHOS API | 实现方式 | 说明 |
|----------|---------|------|
| `@ohos.window` | StageApplication | WindowStage、loadContent、窗口生命周期等核心 API 已工作。EntryAbility 的 onWindowStageCreate 正常回调 |
| `@ohos.resourceManager` | StageAssetProvider | `$r()` 资源引用、系统资源发现（systemres ABC）通过 StageAssetProvider 实现，19 个 hap-example 测试页面均正常使用 |

### 1.11 ArkUI 高级组件

| OHOS API | so 文件 |
|----------|---------|
| `@ohos.arkui.advanced.Dialog` | libarkui_advanced_dialog.so |
| `@ohos.arkui.advanced.Chip` / `.ChipGroup` | libarkui_advanced_chip*.so |
| `@ohos.arkui.advanced.ComposeListItem` | libarkui_advanced_composelistitem.so |
| `@ohos.arkui.advanced.ComposeTitleBar` | libarkui_advanced_composetitlebar.so |
| `@ohos.arkui.advanced.Counter` | libarkui_advanced_counter.so |
| `@ohos.arkui.advanced.EditableTitleBar` | libarkui_advanced_editabletitlebar.so |
| `@ohos.arkui.advanced.ExceptionPrompt` | libarkui_advanced_exceptionprompt.so |
| `@ohos.arkui.advanced.Filter` | libarkui_advanced_filter.so |
| `@ohos.arkui.advanced.FoldSplitContainer` | libarkui_advanced_foldsplitcontainer.so |
| `@ohos.arkui.advanced.GridObjectSortComponent` | libarkui_advanced_gridobjectsortcomponent.so |
| `@ohos.arkui.advanced.Popup` | libarkui_advanced_popup.so |
| `@ohos.arkui.advanced.ProgressButton` | libarkui_advanced_progressbutton.so |
| `@ohos.arkui.advanced.SegmentButton` | libarkui_advanced_segmentbutton.so |
| `@ohos.arkui.advanced.SelectionMenu` | libarkui_advanced_selectionmenu.so |
| `@ohos.arkui.advanced.SelectTitleBar` | libarkui_advanced_selecttitlebar.so |
| `@ohos.arkui.advanced.SplitLayout` | libarkui_advanced_splitlayout.so |
| `@ohos.arkui.advanced.SubHeader` | libarkui_advanced_subheader.so |
| `@ohos.arkui.advanced.SwipeRefresher` | libarkui_advanced_swiperefresher.so |
| `@ohos.arkui.advanced.TabTitleBar` | libarkui_advanced_tabtitlebar.so |
| `@ohos.arkui.advanced.ToolBar` | libarkui_advanced_toolbar.so |
| `@ohos.arkui.advanced.TreeView` | libarkui_advanced_treeview.so |

### 1.12 Browser/ArkTS 内建模块

| 模块 | 说明 |
|------|------|
| `@ohos.curves` | 动画曲线（系统内建 NATIVE_MODULE） |
| `@ohos.matrix4` | 4×4 矩阵（系统内建 NATIVE_MODULE） |
| `@ohos.base` (console/timer) | 基础运行时（console 内建，timer 由 plugins/timer 提供） |

---

## 二、已支持但需验证的 API

这些 API 有对应的 .so 文件，但部分可能仅在 HAP 实际运行时存在问题：

| OHOS API | 潜在问题 | 验证状态 |
|----------|---------|---------|
| `@ohos.router` | 路由栈管理可能与 OHOS 不同，back/push 行为需验证 | ✅ hap-example NavigationTest 通过 |
| `@ohos.data.preferences` | OHOS Preferences 在 Android 上可能有兼容问题 | ✅ hap-example PreferencesTest 通过 |
| `@ohos.data.relationalStore` | OHOS RDB 在 Android 上可能有兼容问题 | ✅ hap-example RelationalStoreTest 通过 |
| `@ohos.data.distributedKVStore` | 分布式 KV 在单机上的行为 | ✅ hap-example KVStoreTest 通过 |
| `@ohos.file.picker` | 走 Android SAF，UI 交互不同 | ✅ hap-example FilePickerTest 通过 |
| `@ohos.web.webview` | 底层走 Android WebView，能力集不同 | ✅ hap-example WebViewTest 通过 |
| `@ohos.settings` | 桩实现，仅内存 KV 存储 | ✅ hap-example SettingsTest 通过 |
| `@ohos.deviceInfo` | 走 Android Build 类 | ✅ hap-example DeviceInfoTest 通过，35 属性均可读 |
| `@ohos.display` | 走 Android Display API | ✅ hap-example DisplayTest 通过（width/height/density 正常，部分属性未实现） |
| `@ohos.process` | 进程信息 | ✅ hap-example ProcessTest 通过（pid/tid/uid/is64Bit 正常，isIsolatedProcess=true） |
| `@ohos.i18n` | 国际化/区域信息 | ⚠️ hap-example I18nTest — getSystemLanguage 正常，getSystemRegion/getSystemLocale 未实现 |
| `@ohos.intl` | 国际化格式化 | ⚠️ hap-example IntlTest — Locale 正常，DateTimeFormat/NumberFormat 未实现 |
| `@ohos.util.*` (HashMap/ArrayList/LinkedList) | 数据结构 | ✅ hap-example UtilTest — HashMap/ArrayList 正常 |
| `@ohos.file.hash` | 文件哈希 | ⚠️ 模块加载正常，算法名格式不兼容 (插件期待 SHA-256 格式) |
| `@ohos.url` | URL 解析 | ✅ hap-example UrlTest 通过（URLSearchParams/URL 全部正常） |
| `@ohos.convertxml` | XML→JS 对象 | ✅ hap-example ConvertXmlTest 通过 |
| `@ohos.buffer` | 二进制数据 | ✅ hap-example BufferTest 通过（alloc/byteLength 正常） |
| `@ohos.file.fs` | 文件系统操作 | ✅ hap-example FileFsTest 通过（listFile/stat/mkdir 全部正常） |
| `@ohos.events.emitter` | 事件发射器 | ✅ hap-example EmitterTest 通过（on/emit 回调正常） |
| `@ohos.net.http` | HTTP 请求 | ⚠️ 模块加载/createHttp 正常，网络请求超时（需设备网络） |
| `@ohos.vibrator` | 走 Android VibratorManager，需要设备有振动马达 | ⚠️ hap-example VibratorTest — 模块加载正常，API 调用正常，但平板无振动马达报错 |
| `@ohos.file.statvfs` | 文件系统空间统计 | ✅ hap-example StatvfsTest 通过（getFreeSizeSync/getTotalSizeSync 正常） |
| `@ohos.net.connection` | 网络连接状态 | ⚠️ hap-example NetConnectionTest — getDefaultNetSync 正常，部分 API 未实现（undefined is not callable） |
| `@ohos.bluetooth.*` | 底层调用 Android Bluetooth API 而非 OHOS Bluetooth，profile 可能空实现 | 未验证 |
| `@ohos.wifiManager` | 走 Android Wi-Fi API | 未验证 |
| `@ohos.geoLocationManager` | 走 Android Location API | 未验证 |
| `@ohos.notificationManager` | 走 Android Notification API | 未验证 |
| `@ohos.pasteboard` | 依赖 Android Clipboard API | 未验证 |
| `@ohos.runningLock` | 可能空实现 | 未验证 |
| `@ohos.net.socket` | UDP/TCP socket 一般没问题 | 未验证 |
| `@ohos.multimedia.audio` | 走 Android Audio API，能力集不同 | 未验证 |
| `@ohos.multimedia.media` | 走 Android MediaPlayer/MediaCodec | 未验证 |

---

## 三、缺失的 API（按优先级排列）

### P0 — 核心必备，影响大部分应用

| OHOS API | 说明 | 难度 |
|----------|------|------|
| `@ohos.app.ability.UIAbility` | UIAbility 基类 — ArkUI-X 的 StageApplication 已部分实现，但完整的 UIAbility 生命周期回调 API 未暴露给 ArkTS。 | 高 |
| `@ohos.app.ability.common` | 应用上下文类型 — UIAbilityContext, ApplicationContext 等类型定义。 | 中 |
| `@ohos.app.ability.wantAgent` | Want 代理 — 启动 Ability、发送 Want 的能力。 | 中 |
| `@ohos.multimedia.image` | 图片处理 — 解码、编码、PixelMap 操作。**常用**。 | 中 |
| `@ohos.web.netErrorList` | WebView 网络错误码 — WebView 已支持，补充类型声明即可。 | 低 |

### P1 — 常用功能，影响中等比例应用

| OHOS API | 说明 | 难度 |
|----------|------|------|
| `@ohos.security.huks` | 通用密钥管理 (HUKS) — 加密、签名、密钥存储。 | 高 |
| `@ohos.rpc` | IPC/RPC 通信 — WantAgent 和 ability 间通信依赖。 | 高 |
| `@ohos.bundle.bundleManager` | 包管理 — 查询已安装 HAP 信息。HOA 已有 Java 实现可桥接。 | 中 |
| `@ohos.settings` | 设置存储 — 系统级 key-value 配置。**已实现** (2026-06-07) — `settings_stub.c` + CMake `libsettings_napi.so`。 | 低 |
| `@ohos.backgroundTaskManager` | 后台任务管理。 | 中 |
| `@ohos.sensor` | 传感器 — 加速度、陀螺仪、光线等。 | 中 |
| `@ohos.inputMethod` | 输入法 — 软键盘控制、输入法切换。 | 中 |
| `@ohos.reminderAgentManager` | 提醒/闹钟代理。 | 中 |
| `@ohos.multimodalInput.keyEvent` | 按键事件 — keyCode 已有，但 keyEvent 等类型声明缺失。 | 低 |
| `@ohos.multimodalInput.touchEvent` | 触摸事件 — 同 keyEvent。 | 低 |
| `@ohos.multimodalInput.mouseEvent` | 鼠标事件 — 同上。 | 低 |
| `@ohos.multimodalInput.pointer` | 指针样式管理。 | 低 |
| `@ohos.multimodalInput.inputDevice` | 输入设备管理。 | 中 |

### P2 — 设备/平台特定，有限场景使用

| OHOS API | 说明 |
|----------|------|
| `@ohos.multimedia.camera` | 相机 — 高硬件依赖，Android 桥接复杂 |
| `@ohos.telephony.*` | 电话/短信/SIM — 只在手机上有意义 |
| `@ohos.bluetoothManager` | 蓝牙管理器（顶层） |
| `@ohos.bluetooth.constant` / `.hfp` / `.hid` / `.map` / `.opp` / `.pan` / `.pbap` / `.socket` / `.wearDetection` | 更多蓝牙 profile |
| `@ohos.nfc.*` | NFC — 硬件相关 |
| `@ohos.usbManager` / `@ohos.usb` | USB — 硬件相关 |
| `@ohos.userIAM.*` | 生物识别认证 — 指纹、人脸 |
| `@ohos.power` | 电源管理 |
| `@ohos.batteryInfo` | 电池信息 |
| `@ohos.thermal` | 热管理 |
| `@ohos.stationary` | 静止检测 |
| `@ohos.screenLock` | 锁屏管理 |
| `@ohos.wallpaper` | 壁纸 |
| `@ohos.screenshot` | 截屏 |
| `@ohos.privacyManager` | 隐私管理 |
| `@ohos.contact` | 联系人 |
| `@ohos.calendarManager` | 日历 |
| `@ohos.print` | 打印 |
| `@ohos.document` | 文档 |
| `@ohos.file.fileAccess` | 文件访问框架 |
| `@ohos.file.securityLabel` | 文件安全标签 |
| `@ohos.file.environment` | 文件环境 |
| `@ohos.file.cloudSync` | 云同步 |
| `@ohos.file.fileuri` | 文件 URI |
| `@ohos.fileshare` | 文件分享 |
| `@ohos.multimedia.avsession` | 音频会话 |
| `@ohos.multimedia.drm` | DRM |
| `@ohos.multimedia.cameraPicker` | 相机选择器 |
| `@ohos.multimedia.audioHaptic` | 音频触觉 |
| `@ohos.multimedia.videoProcessingEngine` | 视频处理引擎 |
| `@ohos.multimedia.movingphotoview` | 动态照片视图 |
| `@ohos.net.ethernet` / `.policy` / `.sharing` / `.vpn` / `.vpnExtension` / `.mdns` / `.netFirewall` / `.statistics` | 网络高级功能 |
| `@ohos.enterprise.*` (adminManager, accountManager 等 15 个) | 企业设备管理 |
| `@ohos.security.asset` / `.certManager` / `.certManagerDialog` | 安全资产/证书管理 |
| `@ohos.secureElement` | 安全元件 |
| `@ohos.connectedTag` | 碰一碰 |
| `@ohos.distributedDeviceManager` | 分布式设备管理 |
| `@ohos.dlpPermission` | DLP 权限 |
| `@ohos.deviceAttest` | 设备认证 |
| `@ohos.identifier.oaid` | OAID |
| `@ohos.customization.customConfig` | 定制配置 |
| `@ohos.continuation.continuationManager` | 任务接续 |
| `@ohos.data.rdb` | 关系数据库（可能有但未签入） |
| `@ohos.data.cloudData` / `.cloudExtension` / `.distributedData` / `.distributedDataObject` / `.intelligence` / `.sendablePreferences` / `.sendableRelationalStore` / `.storage` | 分布式数据等 |
| `@ohos.resourceschedule.*` (backgroundProcessManager, backgroundTaskManager, deviceStandby, systemload, usageStatistics, workScheduler) | 资源调度 |
| `@ohos.graphics.*` (colorSpaceManager, common2D, displaySync, drawing, hdrCapability, scene, uiEffect) | 图形高级功能 |
| `@ohos.multimodalAwareness.*` (deviceStatus, metadataBinding, motion) | 多模态感知 |

### P3 — 已废弃或不应支持

| 模块 | 说明 |
|------|------|
| `@system.*` (21 个) | 旧版 system API，已废弃，不应花时间 |
| `@ohos.ability.*` (ability, dataUriUtils, errorCode, featureAbility, particleAbility, screenLockFileManager, wantConstant) | 旧版 FA 模型，已废弃 |
| `@ohos.application.*` (AccessibilityExtensionAbility, BackupExtensionAbility, Configuration, ConfigurationConstant, Want, abilityDelegatorRegistry, appManager, formBindingData, formError, formInfo, formProvider, testRunner, uriPermissionManager) | 旧版 FA 模型，已废弃 |
| `ability/`, `application/`, `commonEvent/`, `continuation/`, `advertising/`, `app/`, `bundle/`, `data/rdb/`, `global/`, `notification/`, `security/`, `tag/`, `wantAgent/` 等裸模块 | 旧版声明文件，已被 @ohos.* 取代 |

---

## 四、建议优先级排序

基于典型 HAP 应用的实际依赖，建议按以下顺序扩展：

1. **`@ohos.bundle.bundleManager`** — HOA 已有 Java 侧实现 (HapBundleLoader/HapInstaller)，可做 JNI 桥接，中等难度
2. **`@ohos.web.netErrorList`** — WebView 已支持，仅需补充类型声明，极低难度
3. **`@ohos.multimedia.image`** — 图片编解码，常用
4. **`@ohos.app.ability.*` (UIAbility/AbilityContext/Want)** — 补齐 Ability 生命周期和启动能力
5. **`@ohos.security.huks`** — 加密/签名（金融、登录类应用依赖）
6. **`@ohos.rpc`** — IPC 通信基础（很多系统 API 依赖它）
7. **`@ohos.sensor`** — 中等难度、常用
8. **`@ohos.inputMethod`** — 软键盘控制

> **已从列表移除**: `@ohos.window` 和 `@ohos.resourceManager` — 核心功能已通过 ArkUI-X StageApplication/StageAssetProvider 工作，hap-example 19 个测试页面验证通过。

---

## 五、技术策略建议

### 策略 A: 桩实现 (Stub)
适用于暂时不需要真正功能的 API（如 `@ohos.backgroundTaskManager`）。返回默认值或空操作，让应用不崩溃。

### 策略 B: Android 桥接
适用于有 Android 对应功能的 API（如 `@ohos.sensor` → Android Sensor API, `@ohos.inputMethod` → Android InputMethodManager）。通过 JNI 调用 Android SDK。

### 策略 C: 从 OHOS 源码移植
适用于纯软件逻辑的 API（如 `@ohos.multimedia.image`、`@ohos.security.huks`、`@ohos.rpc`）。需要分析 OHOS 源码依赖并裁剪。

### 策略 D: HOA Java 侧实现 + JNI 桥接
适用于 HOA 已有 Java 实现的 API（如 `@ohos.bundle.bundleManager`、`@ohos.window`）。利用现有 Java 层能力，通过 JNI 暴露给 ArkTS。

---

## 六、已知局限

1. **硬件强依赖 API 无法完全兼容**：NFC、Telephony、Biometric 等需要硬件支持，Android 设备差异大
2. **系统服务依赖 API 需 Android 对应**：权限管理、企业设备管理等 OHOS 系统服务在 Android 上没有对应
3. **分布式 API 不可用**：`distributedDeviceManager`、`continuation`、`distributedData` 等依赖 OHOS 分布式框架
4. **部分 API 功能降级**：即使桥接到 Android API，功能集也可能与 OHOS 不一致

---

## 附录: 分析方法

- OHOS API 声明文件来源: `/apps/harmony/sdk/default/openharmony/ets/api/`
- ArkUI-X 插件来源: `plugins/plugin_lib.gni` + `foundation/arkui/ace_engine/interfaces/napi/kits/`
- 已构建 .so 文件来源: `app/src/main/jniLibs/arm64-v8a/`
