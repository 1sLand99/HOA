# sample_in_harmonyos 分析与 HOA 支持建议

华为官方「HarmonyOS代码工坊」开源 App 的 API 覆盖分析。

**仓库**: https://github.com/harmony-on-android/sample_in_harmonyos  
**分析日期**: 2026-06-11

## 项目概况

ArkUI 组件+最佳实践 Demo 应用，包含多个 feature 模块：
- `componentlibrary` — ArkUI 组件展示（核心模块）
- `exploration` — 实践/技术文章
- `devpractices` — 开发实践 Sample
- `commonbusiness` — 公共业务组件
- `mine` — 个人中心

使用 HarmonyOS NEXT `@kit.*` 风格导入，SDK 5.1.0+。

## 使用的 API（按 kit 分组）

### ✅ 已支持

| Kit | API | HOA 状态 |
|-----|-----|---------|
| `@kit.AbilityKit` | `common`, `Want`, `AbilityConstant`, `ConfigurationConstant`, `UIAbility`, `Configuration`, `bundleManager` | ✅ |
| `@kit.BasicServicesKit` | `BusinessError`, `deviceInfo`, `emitter`, `pasteboard`, `settings` | ✅ |
| `@kit.ArkUI` | `curves`, `window`, `display`, `promptAction`, `LengthMetrics`, `Popup`, `CustomContentDialog` | ✅ |
| `@kit.ArkWeb` | `webview` | ✅ |
| `@kit.PerformanceAnalysisKit` | `hilog` | ✅ |
| `@kit.ImageKit` | `image` | ✅ |
| `@kit.MediaLibraryKit` | `photoAccessHelper` | ✅ |
| `@kit.CoreFileKit` | `picker` | ✅ |
| `@kit.ArkData` | `preferences` | ✅ |
| `@kit.ArkTS` | `JSON`, `util`, `uri` | ✅ |
| `@kit.NetworkKit` | `connection` | ✅ |
| `@kit.SensorServiceKit` | `vibrator` | ✅ |

### ❌ 未支持（按可行性分类）

#### 类别 A：ArkUI 高级组件（C++ 层，难度高）

| API | 说明 | 影响 |
|-----|------|------|
| `ArcList`, `ArcListItem` | 弧形列表（穿戴设备） | 穿戴设备专用 |
| `ArcSwiper`, `ArcSwiperController` | 弧形滑动器 | 穿戴设备专用 |
| `SegmentButton`, `SegmentButtonOptions`, `SegmentButtonTextItem` | 分段按钮 | 组件库核心示例 |
| `BuilderNode`, `FrameNode`, `NodeController` | 树形节点 API | 高级 ArkUI 模式 |
| `ComponentContent` | 组件内容容器 | 组件库示例 |
| `MeasureText` | 文本测量 | 组件库示例 |
| `TipsDialog` | 提示对话框组件 | 组件库示例 |
| `ItemRestriction` | 项目限制组件 | 组件库示例 |

**分析**：这些都是 ArkUI 引擎层的 C++ 组件，不是可 stub 的 JS 模块。HOA 的 ArkUI-X 基线如果不包含这些组件（ArkUI 6.1 → 7.0 新增），需要从上游 cherry-pick 或自行实现。

#### 类别 B：可桥接 Android API（难度中）

| API | 说明 | 方案 |
|-----|------|------|
| `systemShare` | 系统分享面板 | 桥接 Android `Intent.ACTION_SEND` / ShareSheet |
| `camera`, `cameraPicker` | 相机/拍照选择器 | 桥接 Android CameraX 或 `MediaStore.ACTION_IMAGE_CAPTURE` |
| `call` | 电话呼叫 | 桥接 Android `Intent.ACTION_DIAL` |
| `textToSpeech` | 文字转语音 | 桥接 Android `TextToSpeech` API |
| `displaySync` | 显示同步（VSync） | ArkUI-X 可能有底层支持，需评估 |
| `effectKit` | 特效（模糊/阴影等） | ArkUI-X 可能有底层支持 |

#### 类别 C：可 Pure-ABC stub（难度低）

| API | 说明 | 方案 |
|-----|------|------|
| `formBindingData`, `FormExtensionAbility`, `formInfo` | 卡片/Widget | 纯 mock 放回空数据 |
| `moduleInstallManager`, `updateManager` | 应用市场管理 | 纯 mock 放回空结果 |
| `uniformTypeDescriptor` | 统一类型描述符 | 纯 mock 放回默认值 |
| `AICaptionComponent`, `AudioData` | AI 字幕/语音 | 纯 mock |

#### 类别 D：硬件/设备依赖（不适合 stub）

| API | 说明 |
|-----|------|
| `HandwriteComponent`, `HandwriteController` | 手写笔 |
| `BackupExtensionAbility`, `BundleVersion` | 设备备份 |

## 建议路线

### 短期（可立即着手）

| 优先级 | 任务 | 难度 | 价值 |
|--------|------|------|------|
| P0 | `systemShare` — 桥接 Android ShareSheet | 中 | 高（分享是常用功能） |
| P0 | `TipsDialog` 组件 | 中 | 中（组件库示例之一） |
| P1 | Category C 4 个 Pure-ABC stub | 低 | 中（一次性补齐多个） |
| P1 | `SegmentButton` 系列 | 高 | 高（组件库核心示例） |

### 中期

- `camera`/`cameraPicker` 桥接 Android 相机
- `textToSpeech` 桥接 Android TTS
- 评估 ArkUI-X 7.0 Beta1 对缺失组件的支持情况

### 长期

- 跟踪 ArkUI-X 上游更新，同步 ArkUI 高级组件
- `displaySync`/`effectKit` 底层支持

## 特别说明

该项目使用 `@kit.*` 导入风格（HarmonyOS NEXT），与 HOA 当前使用的 `@ohos.*` 风格不同。但 ArkUI-X 内部已将 `@kit.*` 映射到对应模块，运行时层面兼容。
