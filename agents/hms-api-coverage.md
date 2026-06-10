# HMS API 覆盖分析

HMS (Huawei Mobile Services) API 在 HarmonyOS SDK 中的声明情况，与 HOA 已有的 stub 覆盖对比。

**分析日期**: 2026-06-10  
**SDK 路径**: `/apps/harmony/sdk/default/hms/ets/api/`  
**声明文件**: 174 个

## 已 stub 的 HMS API（5 个模块，覆盖 ~36 个 SDK 声明）

| HOA Stub | 覆盖的 SDK API | 模式 |
|----------|---------------|------|
| `plugins/hms/hds` | `hdsBaseComponent`, `HdsActionBar`, `HdsSideBar`, `HdsSideMenu`, `HdsSnackBar`, `HdsStyle`, `HdsVisualComponent`, `hdsMaterial`, `hdsDrawable`, `symbolRegister` (10) | 嵌入式 ABC（ViewV2 mock） |
| `plugins/hms/security` | `deviceCertificate`, `safetyDetect`, `AAID` (3) | 嵌入式 ABC |
| `plugins/hms/push` | `pushService`, `pushCommon`, `PushExtensionAbility`, `PushExtensionContext`, `RemoteLocationExtensionAbility`, `RemoteLocationExtensionContext`, `RemoteNotificationExtensionAbility`, `RemoteNotificationExtensionContext`, `VoIPExtensionAbility`, `VoIPExtensionContext`, `serviceNotification` (11) | 嵌入式 ABC |
| `plugins/hms/account` | `extendservice`, `LoginComponent`, `invoiceAssistant`, `minorsProtection`, `realname`, `shippingAddress` (6) | 嵌入式 ABC |
| `plugins/hms/iap` | `iap`, `cashierComponent`, `paymentService`, `ecnyPaymentService`, `promotionService`, `realNameService`, `thirdPaymentService`, `walletPass`, `walletTransitCard` (9) | 嵌入式 ABC |

## 未 stub 的 HMS API（按类别）

### 类别 A：设备/硬件强依赖，不适合 stub

| 模块 | SDK API 数 | 原因 |
|------|-----------|------|
| `hms.nearlink.*` | 7 | 星闪近场通信，硬件依赖 |
| `hms.telephony.*` | 4 | 电话/SIM 卡，纯手机功能 |
| `hms.carService.*` | 2 | 车机服务 |
| `hms.pcService.*` | 6 | PC 模式服务 |
| `hms.stylus.*` | 2 | 手写笔硬件 |
| `hms.gameAcceleration.*` | 4 | 游戏加速，设备特定 |
| `hms.health.*` | 3 | 健康传感器 |
| `hms.graphics.spatialRender` | 1 | 空间渲染，硬件 |
| `hms.enterpriseSpaceService.*` | 2 | 企业空间 |

**小计: 31 个 API — 不需要 stub**

### 类别 B：需要 Android 原生桥接，难度中-高

| 模块 | SDK API 数 | 行数 | 说明 |
|------|-----------|------|------|
| `hms.core.map.*` | 8 | ~14K | 地图组件（MapComponent、导航、站点搜索），需要对接 Android 地图 SDK |
| `hms.ai.vision.*` | 4 | ~1.5K | AI 视觉（物体检测、骨架检测、主体分割），需要 ML Kit 或类似 |
| `hms.ai.*` (其他) | 13 | ~8K | AI 语音/OCR/NLP/人脸，均需 ML 后端 |
| `hms.collaboration.*` | 7 | ~3K | 协同分享（systemShare、harmonyShare、设备选择器），需要 Android ShareSheet |
| `hms.data.*` | 4 | ~500 | RAG/知识库/本地模型，需 LLM 后端 |
| `hms.core.authentication` | 1 | 1.4K | 华为账号认证，需 HMS Core SDK |
| `hms.core.ar.*` | 2 | ~1K | AR 引擎/视图，需 ARCore 或类似 |

**小计: 39 个 API — 需要显著投入**

### 类别 C：可实现纯 JS stub（低难度）

| 模块 | SDK API 数 | 说明 |
|------|-----------|------|
| `hms.core.scan.*` | 5 | 扫码（customScan、detectBarcode、generateBarcode、scanBarcode、scanCore），可返回 mock 结果 |
| `hms.bundle.*` | 2 | applinking（deferredLink）、sceneManager，类型简单 |
| `hms.networkboost.*` | 3 | 网络加速（handover、netBoost、netquality），返回空/默认值 |
| `hms.ai.insightIntent.*` | 2 | 意图洞察，返回空 Promise |
| `hms.officeservice.*` | 4 | Office 服务（PDF 预览、图片选择器），可桥接 Android Intent |
| `hms.core.liveview.*` | 3 | 锁屏实时视图，返回 mock |
| `hms.core.ringtone` | 1 | 铃声服务，返回 mock |
| `hms.core.weather` | 1 | 天气服务，返回 mock 数据 |
| `hms.filemanagement.*` | 2 | 文件管理/预览，可桥接 Android |
| `hms.collaboration.rcp` | 1 | 远程协同，返回空 |
| `hms.core.readerservice.*` | 2 | 阅读器服务（bookParser、readerComponent），mock |

**小计: 26 个 API — 可通过 Pure-ABC 或简单 stub 覆盖**

### 类别 D：HDS 视觉完善（已有 stub，需补属性）

| 组件 | 当前状态 | 缺失 |
|------|---------|------|
| `HdsActionBar` | ViewV2 Row+Button 基本结构 | `innerSpace` 按钮间距、`isHorizontal` 垂直布局、`shadowStyle`、`backgroundBlurStyle` |
| `HdsSideBar` | 简化为空占位 | 完整布局 |
| `HdsSideMenu` | 简化为空占位 | 完整布局 |
| `HdsSnackBar` | no-op stub | 基本 Toast/SnackBar 显示 |
| `HdsVisualComponent` | 未实现 | 视觉组件 |

**不是新 stub 问题，是已有 stub 的视觉完善**

### 类别 E：HMS Security 扩展

| 模块 | 说明 | 难度 |
|------|------|------|
| `hms.security.soter` | 生物认证 | 中 |
| `hms.security.fido` / `fido2` | FIDO 认证 | 中 |
| `hms.security.ifaa` | 本地生物认证 | 中 |
| `hms.security.trustedAuthentication` | 可信认证 | 中 |
| `hms.security.trustedAppService` | 可信应用 | 中 |
| `hms.security.superPrivacyMode` | 超级隐私 | 低（返回 false） |
| `hms.security.antifraudPicker` | 反欺诈 | 低（返回 mock） |
| `hms.security.businessRiskIntelligentDetection` | 风险检测 | 低（返回 mock） |
| `hms.security.dlpAntiPeep` | 防窥屏 | 低 |
| `hms.security.securityAudit` | 安全审计 | 低 |

## 优先级建议

| 优先级 | 范围 | 数量 | 投入 |
|--------|------|------|------|
| **P0** | 类别 D — HDS 视觉完善 | 5 组件 | 修改已有 JS，不新增模块 |
| **P1** | 类别 C — 低难度 Pure-ABC | ~26 API | 新增 10-15 个 stub 模块 |
| **P2** | 类别 E — HMS Security 扩展 | ~10 API | 扩展现有 security stub |
| **P3** | 类别 B — 需要 Android 桥接 | ~39 API | 每个需数天 |
| **N/A** | 类别 A — 硬件依赖 | 31 | 不需要 |

## 附注

- 已有 5 个 HMS stub 均为"嵌入式 ABC"模式（JS → ABC 嵌入 .so → `napi_module_with_js`），非 Pure-ABC
- 新增 HMS stub 建议先评估是否可以用 Pure-ABC（更简单），只有需要 C++ 逻辑或子模块共享 ABC 时才用嵌入式
- `hms.core.appgalleryservice.*`（8 个 API）是应用市场服务，对 HAP 兼容无影响，不需要 stub
