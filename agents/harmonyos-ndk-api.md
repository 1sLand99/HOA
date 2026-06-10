# HarmonyOS NDK Native API 全景

SDK 版本: HarmonyOS 6.x ($OHOS_SDK_HOME/sdk/default/)

## 概述

HarmonyOS NDK 提供两层 native API：

| 层 | 来源 | 目录 | 描述 |
|----|------|------|------|
| **OpenHarmony NDK** | `openharmony/native/sysroot/usr/include/` | 97 个子目录, ~1997 个 .h | 平台基础 API + 设备能力 |
| **HMS NDK** | `hms/native/sysroot/usr/include/` | 20 个子目录 | 华为移动服务专有 API |

HAP 中的 .so 文件可能链接这些库中的任意一个。HOA 当前机制（ArkUI-X plugins）覆盖了 **ArkTS/JS 层**的模块导入，但 **C/C++ NDK 层的原生函数调用链路**几乎全部未覆盖。

---

## HOA 加载机制与 NDK 的关系

```
HAP .so 调用 OHOS NDK 函数 (如 OH_LOG_Print)
  → DT_NEEDED: libhilog_ndk.z.so
  → Android linker 找不到 → dlopen 失败
  → HAP 启动崩溃
```

当前 HOA 通过以下方式处理：
- **libb.so ELF patch**: 将 `libc.so` → `libb.so`，解决 musl C 库依赖
- **hilog stub**: `libhilog_ndk.z.so` → 转发到 `__android_log_print`
- **ArkUI-X plugins**: 提供 JS/ETS 层的 NAPI 模块注册

**缺口**：HAP .so 直接调用的非 libc NDK 函数（如 `OH_LOG_Print`、`OH_Drawing_*`、`OH_Audio_*`）需要对应的 stub .so 提供符号。

---

## 一、OpenHarmony NDK — 按类别分组

### 类别 A: 已有 HOA 插件覆盖（ArkTS/JS 层）

这些 API 在 ArkUI-X plugins/ 中有对应的 NAPI 模块，JS 层 import 不会报错。但 C 层的 NDK 符号**不一定**被覆盖。

| 域 | 头文件数 | plugins/ 目录 | JS 层 | C NDK 层 | 说明 |
|----|----------|---------------|-------|----------|------|
| hilog | 1 | `hilog/` | ✅ | ✅ (HOA stub) | `OH_LOG_Print` 等 |
| hitrace | 1 | `hitrace_meter/` | ✅ | ❌ | `HiTraceId`, `HiTraceChain` |
| arkui | 15 | `arkui/` | ✅ | ❌ | 声明式 UI C API |
| web | 6 | `web/` | ✅ | ❌ | ArkWeb, scheme handler |
| net | 标准 POSIX | `net/` | ✅ | ❌ | 网络连接管理 |
| i18n | 2 | `i18n/` | ✅ | ❌ | 时区/国际化 |
| accesstoken | 1 | `ability_access_ctrl/` | ✅ | ❌ | 权限检查 |
| notification | 1 | `notification_manager/` | ✅ | ❌ | 通知发布 |
| multimedia | N/A | `multimedia/` | ✅ | ❌ | 音视频播放/编解码 |
| multimodalinput | 4 | `multimodalinput/` | ✅ | ❌ | 键盘/鼠标/触控 |
| common_event | N/A | `common_event_manager/` | ✅ | ❌ | 公共事件 |
| connectivity (BT/WiFi) | N/A | `bluetooth/`, `wifi_manager/` | ✅ | ❌ | 蓝牙/WiFi |
| crypto/framework | N/A | `security/cryptoframework/` | ✅ | ❌ | 加密框架 |
| pasteboard | N/A | `pasteboard/` | ✅ | ❌ | 剪贴板 |
| geo_location | N/A | `geo_location_manager/` | ✅ | ❌ | 位置服务 |
| display | N/A | `display/` | ✅ | ❌ | 屏幕信息 |
| buffer | N/A | `buffer/` | ✅ | ❌ | JS Buffer |

### 类别 B: 高优先级 — HAP 常见依赖但 HOA 无插件

这些是 HAP .so 最可能链接的库，缺少时会导致 HAP 启动失败。

| 域 | 头文件数 | 关键头文件 | 核心函数前缀 | 对应 .so | 说明 |
|----|---------|-----------|-------------|---------|------|
| **ohaudio/** | 12 | `native_audiorenderer.h`, `native_audiocapturer.h`, `native_audio_manager.h`, `native_audio_stream_manager.h` | `OH_Audio*` | `libohaudio.so` | 音频播放/录制/流管理 |
| **native_drawing/** | 40+ | `drawing_canvas.h`, `drawing_bitmap.h`, `drawing_text_typography.h`, `drawing_path.h`, `drawing_brush.h` | `OH_Drawing_*` | `libnative_drawing.so` | 2D 矢量图形/画布/文本排版 |
| **CryptoArchitectureKit/** | 12 | `crypto_architecture_kit.h`, `crypto_sym_cipher.h`, `crypto_digest.h`, `crypto_signature.h` | `OH_Crypto*` | `libcrypto_architecture_kit.so` | 对称/非对称加密、摘要、签名 |
| **huks/** | 5 | `native_huks_api.h`, `native_huks_param.h`, `native_huks_type.h` | `OH_Huks_*` | `libhuks.so` | 硬件密钥管理 |
| **filemanagement/** | ~10 | `oh_fileio.h`, `oh_file_uri.h`, `oh_environment.h`, `oh_file_share.h` | `OH_File*` | `libohfileio.so` 等 | 文件 I/O, URI, 分享, 云盘 |
| **network/** | 8 | `net_http.h`, `net_websocket.h`, `net_connection.h`, `net_ssl_c.h` | `OH_Net*` | `libnet_http.so` 等 | HTTP/WS/SSL 客户端 |
| **sensors/** | 4 | `oh_sensor.h`, `vibrator.h` | `OH_Sensor*`, `OH_Vibrator*` | `libohsensor.so` | 传感器/振动 |
| **window_manager/** | 7 | `oh_window.h`, `oh_display_manager.h`, `oh_display_capture.h` | `OH_Window*` | `libohwindow_manager.so` | 窗口/PiP/截屏/Display |
| **resourcemanager/** | 2 | `ohresmgr.h` | `OH_ResourceManager_*` | `libohresmgr.so` | 资源/字符串/媒体访问 |
| **rawfile/** | 3 | `raw_file_manager.h`, `raw_dir.h` | `OH_ResourceManager_*` | `librawfile.so` | HAP 内 assets 文件访问 |
| **ohcamera/** | 11 | `camera.h`, `camera_manager.h`, `capture_session.h`, `photo_output.h` | `OH_Camera*` | `libohcamera.so` | 相机管线 |

### 类别 C: 中优先级 — 特定类型 HAP 使用

| 域 | 头文件数 | 关键函数 | 对应 .so | 场景 |
|----|---------|---------|---------|------|
| **ffrt/** | 10 | `ffrt_*` 任务/协程/定时器 | `libffrt.so` | 并发/异步任务框架 |
| **ark_runtime/** | 2 | `JSVM_*` | `libark_runtime.so` | JS 虚拟机 C API |
| **mindspore/** | 7 | `OH_AI_*` | `libmindspore-lite.so` | AI 推理 (ONNX/MindIR) |
| **neural_network_runtime/** | 3 | `OH_NN_*` | `libneural_network_runtime.so` | 硬件加速 NN 推理 |
| **telephony/** | 3 | `OH_Telephony_*` | `libtelephony.so` | 蜂窝网络状态 |
| **inputmethod/** | 9 | `OH_InputMethod_*` | `libinputmethod.so` | 输入法框架 |
| **GameControllerKit/** | 5 | `OH_GameController_*` | `libgamecontroller.so` | 游戏手柄 |
| **database/rdb/** | ~8 | `OH_Rdb_*` | `libnative_rdb.so` | 关系型数据库 (SQLite) |
| **database/preferences/** | ~2 | `OH_Preferences*` | `libnative_preferences.so` | KV 偏好设置 |
| **database/pasteboard/** | ~2 | `OH_Pasteboard*` | `libnative_pasteboard.so` | 剪贴板 |
| **bundle/** | 3 | `OH_NativeBundle_*` | `libnative_bundle.so` | 应用包查询 |
| **AbilityKit/** | 7 | `OH_Ability_*` | `libabilitykit.so` | Ability 生命周期/Context |
| **BasicServicesKit/** | ~15 | `OH_CommonEvent*`, `OH_Battery*`, `OH_Print*`, `OH_Scan*` | 多个 .so | 电池/打印/扫描/账户 |
| **IPCKit/** | 5 | `OH_IPC_*` | `libipc_capi.so` | 跨进程通信 |

### 类别 D: 低优先级 — 特殊场景/硬件驱动

| 域 | 头文件数 | 说明 |
|----|---------|------|
| **distributedhardware/** | 2 | 分布式设备发现 |
| **DataProtectionKit/** | 1 | DLP 数据防泄漏 |
| **asset/** | 2 | 安全存储 (密码/令牌) |
| **device_certificate/** | 2 | 证书管理 |
| **TEEKit/** | 30+ | 可信执行环境 (iTrustee) |
| **hidebug/** | 2 | 调试/性能数据 |
| **hiappevent/** | 4 | App 事件打点 |
| **hicollie/** | 1 | 看门狗/ANR 检测 |
| **transient_task/** | 2 | 后台任务豁免 |
| **background_process_manager/** | 1 | 后台进程模式 |
| **qos/** | 1 | 线程 QoS |
| **purgeable_memory/** | 1 | 可回收内存 |
| **hid/** | 2 | HID 设备驱动 |
| **usb/** | 2 | USB 设备驱动 |
| **usb_serial/** | 2 | USB 串口 |
| **scsi_peripheral/** | 2 | SCSI 外设 |
| **ddk/** | 2 | 通用驱动框架 |

### 类别 E: 标准/POSIX/GPU — 无需 HOA 处理

这些由系统内核/GPU 驱动/musl libc 提供，HOA 只需确保符号可见：

| 域 | 说明 |
|----|------|
| GL/, GLES2/, GLES3/, EGL/ | OpenGL ES (GPU 驱动提供) |
| vulkan/ | Vulkan (GPU 驱动提供) |
| SLES/ | OpenSL ES (系统音频) |
| sys/ | POSIX 系统调用 (musl/bionic) |
| net/, netinet/ | BSD socket (musl/bionic) |
| arpa/ | DNS (musl) |
| aarch64-linux-ohos/ | AArch64 ABI 定义 |
| asm-generic/, asm-mips/, asm-riscv/ | 架构定义 |
| fortify/ | musl buffer overflow 保护 |
| sound/, video/ | ALSA/V4L2 内核头 |
| drm/, rdma/ | GPU/RDMA 内核头 |
| scsi/, xen/ | 内核驱动头 |
| unicode/ | ICU4C (musl 可选) |

---

## 二、HMS NDK

HMS NDK 全部位于 `hms/native/sysroot/usr/include/`，共 20 个域。当前 HOA HMS plugins/ 全部是 ArkTS/JS 层 mock（ABC-only），**C NDK 层零覆盖**。

| HMS 域 | 头文件数 | 关键 C 函数前缀 | 对应 .so | HOA JS 插件 | 说明 |
|---------|----------|----------------|---------|-------------|------|
| **AppGalleryKit** | 1 | `HMS_ModuleInstall_*` | `libhmsmoduleinstall.so` | ❌ | 按需模块安装 |
| **GameServiceKit** | 1 | `HMS_GamePerformance_*` | `libgame_performance.z.so` | ❌ | 游戏性能遥测 |
| **StoreKit** | 1 | `HMS_ModuleInstall_*` (已废弃) | `libhmsmoduleinstall.so` | ✅ (`iap/`) | IAP 已迁移到 AppGalleryKit |
| **NetworkBoostKit** | 3 | `HMS_NetworkBoost_*` | `libnetwork_boost.so` | ❌ | 网络加速/多路径 |
| **RemoteCommunicationKit** | 1 | `HMS_Rcp_*` (大型 HTTP 库) | `librcp_c.so` | ❌ | curl-based HTTP 客户端 |
| **OnlineAuthenticationKit** | 1 | `HMS_FIDO2_*` | `libfido2_ndk.z.so` | ❌ | FIDO2/WebAuthn |
| **DeviceSecurityKit** | 3 | `HMS_DSM_*`, `HMS_Security*` | 3 个 .so | ✅ (`security/`) | 安全模式/防病毒/审计 |
| **CANNKit** | 5 | `HIAI_*` | `libhiai*.so` | ❌ | AI 硬件加速 |
| **PreviewKit** | 3 | `HMS_OpenFileBoost_*`, `HMS_FileCacheBoost_*` | 2 个 .so | ❌ | 文件预读/缓存 |
| **AR Engine (ar/)** | 1 | `AREngine_*` | `libarengine_ndk.z.so` | ❌ | AR 会话/锚点/跟踪 |
| **FASTKit** | 3 | `FAST_*` | (集成库) | ❌ | 应用加速 |
| **graphics_game_sdk** | 6 | ABR/帧生成/上采样 | 多个 | ❌ | 游戏图形增强 |
| **dataaugmentation** | 6 | `OH_Aip_*` | `libnative_aip_retrieval_ndk.so` | ❌ | 向量检索/知识库 |
| **hiai_foundation** | 5 | `HIAI_*` (HiAI 推理) | `libhiai_*.so` | ❌ | HiAI 推理引擎 |
| **xengine** | 15 | `XEG_*` (Vulkan/GLES 扩展) | `libxengine.so` | ❌ | 可变速率着色/光追/上采样 |
| **color_picker** | 1 | `HMS_GCP_*` | `libcolorpicker_ndk.z.so` | ❌ | 系统取色器 |
| **handwrite** | 1 | `HMS_HandWrite_*` | `libhandwrite_ndk.z.so` | ❌ | 手写预测 |
| **service_collaboration** | 1 | `HMS_ServiceCollaboration_*` | `libservice_collaboration_ndk.z.so` | ❌ | 跨设备服务协同 |
| **spatial** | 1 | `HMS_SpatialRecon_*` | `libspatial_recon_ndk.z.so` | ❌ | 3D 空间重建 |
| **tss2** | 6 | `Tss2_Sys_*` | `libtss2-sys.so` | ❌ | TPM 2.0 可信计算 |

---

## 三、HOA 当前 NDK 层覆盖

### 已有 C NDK stub (真正可被 HAP .so 链接)

| .so | 来源 | 覆盖的 NDK 函数 | 状态 |
|-----|------|----------------|------|
| `libb.so` | HOA 自建 (musl bridge) | pthread/stdio/dirent/signal/malloc 等 musl C 库 | ✅ 核心 |
| `libhilog_ndk.z.so` | HOA CMake | `OH_LOG_Print`, `OH_LOG_PrintMsg`, `OH_LOG_IsLoggable` | ✅ |
| `libohaudio.so` | HOA CMake (ohaudio_stub.c) | `OH_Audio*` (空实现) | ⚠️ stub only |
| `libace_napi.z.so` | HOA CMake | NAPI 基础设施 | ✅ |

### ArkTS/JS 层 mock (不提供 C NDK 符号)

ArkUI-X plugins/ 中的所有 HMS stub（`hms_*_stub.cpp`）通过 `napi_module_with_js_register` 注册 NAPI 模块，使 JS import 成功。但**不导出 C 符号**供 HAP .so 的 `DT_NEEDED` 链接。

---

## 四、关键发现

### 1. JS 层覆盖 ≠ C 层覆盖

HOA 当前有 7 个 HMS plugins（account/security/push/iap/hds/share），全部是 ABC-only mock。它们处理的是 ArkTS `import from '@kit.XXX'` 的场景，对应 HAP 中的 `modules.abc` (ArkTS 字节码)。

HAP 中的 `libs/arm64-v8a/*.so` 可以通过 `DT_NEEDED` 直接链接 NDK 库，走的是完全不同的路径。例如：
- `libhilog_ndk.z.so` → HOA 有 C stub ✅
- `libnative_drawing.so` → HOA 无 C stub ❌
- `librcp_c.so` (HMS HTTP) → HOA 无 C stub ❌
- `libohaudio.so` → HOA 有空 stub ⚠️

### 2. 优先级判断标准

一个 NDK 域是否需要 stub，取决于：
1. 是否有已知 HAP 的 .so 链接了该库（观测到的 DT_NEEDED）
2. 该域的使用普遍性（如 audio/drawing 比 spatial/AR 更常用）

### 3. 典型 HAP 示例

| HAP | 可能 DT_NEEDED 的 NDK 库 | 说明 |
|-----|--------------------------|------|
| zbox (DOSBox-X) | `libc.so` (→ libb.so), `libohaudio.so` | SDL audio → OHOS audio |
| ohbili | `libc.so` (→ libb.so), media/codec | ijkplayer .so |
| 音乐播放器 | `libohaudio.so`, `libnative_drawing.so` | 音频 + 波形可视化 |
| 网络应用 | `librcp_c.so` 或 `libnet_http.so` | HTTP 请求 |

---

## 五、建议路线

### 短期 — 被现有 HAP 触达的

1. **libohaudio.so stub 完善** — 当前是空 stub，需要至少提供能工作（不崩溃）的空实现链路
2. **libnative_drawing.so stub** — 如果遇到图形密集型 HAP

### 中期 — 通用覆盖率

3. **CryptoArchitectureKit** — 加密是常用功能
4. **filemanagement** — 文件 I/O 是基础能力
5. **network** — HTTP/WebSocket 客户端

### 长期

6. 基于 ArkUI-X plugin 模式扩展 C NDK stub 框架
7. 按 HAP 实测需求逐个补齐

---

## 参考

- 相关文档: `agents/hms-api-coverage.md` (ArkTS 层 HMS API 覆盖分析)
