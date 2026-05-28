# 方案：在 Android 上运行鸿蒙闭源 `.so` 文件的全自主托管沙箱架构

本项目旨在 Android（Bionic 库）内核上构建一个**跨 C 库、跨运行时的用户态微型沙箱转译层**。

### 📌 核心限制与已知条件
1. **HAP 目标 `.so` 文件**：完全闭源，不可修改，只能基于二进制加载。
2. **鸿蒙底层组件源码**：包括魔改版 `musl libc`、`napi`、`图形/多媒体 NDK 库`，我们均拥有绝对控制权，可深度修改。

---

## 🏛️ 1. 核心架构设计

不采用复杂的 Java-C 中间层翻译，而是利用手中拥有的鸿蒙底层源码，编译出一套专用于 Android 环境的**“鸿蒙运行基础组件包”**。
通过 Android 宿主进程（Bionic）加载魔改后的鸿蒙动态链接器（`ld-musl-android.so`），由它全权接管、链接并引引导 HAP 闭源 `.so` 运行。

```text
+-----------------------------------------------------------------------------------+

|                           Android App 进程 (同一个 PID)                             |
|                                                                                   |
|  [ Android 宿主区 ]           [ 魔改鸿蒙基础源码区 ]            [ HAP 闭源 SO 区 ]     |
|  +----------------+          +-----------------------+        +----------------+  |
|  | Android JVM    |          |  libace_napi_proxy.so |        |   hap_addon.so |  |
|  | (Bionic C 库)   |          |  (N-API 跨 C 库映射器)  |        | (只认识 Musl 库) |  |
|  +----------------+          +-----------------------+        +----------------+  |
|          |                            ^                                |          |
|          | (JNI桥接)                  | (源码级内部绑定)                | (寻找符号) |
|          v                            v                                v          |
|  +----------------+          +-----------------------+                            |
|  | Android N-API  | <======> |   ld-musl-android.so  | ===========================+  |
|  | 核心支持 SO     |          | (核心魔改:托管TLS/信号)|   (拦截全局，强制路由绑定)   |
|  +----------------+          +-----------------------+                               |
+-----------------------------------------------------------------------------------+
```

---

## 🛠️ 2. 四大核心模块的源码修改指南

### 💾 2.1 链接器魔改（修改 `third_party_musl/ldso/dynlink.c`）
作为沙箱的发动机，原生的 Musl 链接器无法识别 Android 的符号，须对其进行底层魔改：
* **符号后门（Symbol Fallback）**：
  * 修改 `find_sym` 符号查找逻辑。
  * 当 HAP 闭源 `.so` 寻找任何 `napi_*` 或图形符号且在 Musl 空间未找到时，允许链接器自动降级调用 Android Bionic 库的 `dlsym(RTLD_DEFAULT, name)`，完成跨 C 库的符号强行路由。
* **堆内存托管（全进程单一堆）**：
  * 修改 Musl 的 `malloc.c`/`free.c` 源码。
  * 将底层的分配/释放实现直接替换为调用 Android Bionic 导出的 `jemalloc` 函数指针。
  * 确保 HAP 内部申请的内存实际由 Android 统一管理，实现**零拷贝（Zero-Copy）**，彻底消除双重堆管理器引发的内存错乱及闪退。

### 🔌 2.2 N-API 框架修改（修改 `napi` 相关源码）
HAP 深度依赖 `napi_*` 函数与上层进行交互，需将鸿蒙的 N-API 库改造成一个“双面符号代理”：
* **源码重定向（Forwarding）**：
  * 在编译鸿蒙的 `libace_napi.so` 时，重写所有 `napi_*` 函数的底层实现。
  * 摒弃原本对接鸿蒙 Ark 引擎的逻辑，将其内部直接改写为**向下调用 Android 系统中同名的原生 N-API 函数**。
  * HAP 调用 Musl 世界的 N-API 时，直接穿透符号层，零开销执行 Android 原生方法。

### 🛡️ 2.3 线程与信号防崩溃（修改 `musl` 核心初始化源码）
解决 Android 虚拟机（ART）与 Musl 两套 C 库在底层争夺控制权的致命隐患：
* **信号解耦（注释抢占行为）**：
  * 切入 `third_party_musl/src/env/__libc_start_main.c` 源码。
  * **将所有自动注册 `sigaction` 信号拦截的代码全部注释掉**。强迫 Musl 彻底放弃对 Linux 系统信号（如 `SIGSEGV`、`SIGPIPE`）的控制，将控制权完整留给 Android ART 虚拟机，防止 App 瞬间闪退。
* **TLS（线程局部存储）兼容**：
  * 修改 Musl 的 `pthread_create` 源码。
  * 在创建线程时，在其 TCB（线程控制块）头部预留一段空间，用以拷贝或对齐 Android Bionic 线程的核心结构。
  * 确保 Android JVM 在该线程中触发垃圾回收（GC）时，能正确识别线程状态。

### 📺 2.4 图形与多媒体转译（修改鸿蒙 `HDF/HDI` 硬件层源码）
HAP 闭源 `.so` 深度使用了 UI 渲染和多媒体，必须在源码层将鸿蒙的硬件抽象对接到 Android 的 NDK 驱动：
* **图形渲染（NativeWindow 影子映射）**：
  * 修改鸿蒙 `libnative_window.so` 的源码，改写 `OH_NativeWindow_Create`。
  * 在内部将其封装为 Android NDK 的 **`ANativeWindow`**（由 Android 的 Java 层 `SurfaceView`/`TextureView` 传入）。
  * 当 HAP 尝试写入像素或提交缓冲区时，内部强行重定向调用 Android 的 `ANativeWindow_lock` 和 `unlockAndPost`，让画面直接在 Android 屏幕上呈现。
* **音频流水线（Audio Pipeline 转发）**：
  * 修改鸿蒙音频系统 `libohaudio.so` 的源码。
  * 将底层数据输出逻辑从原本的“写入鸿蒙内核共享内存/音频服务”修改为**直接调用 Android 的 `AAudio` 或 `OpenSL ES` 写入接口**。

---

## 🏆 3. 方案优势总结

1. **对 HAP 闭源 SO 零侵入**：HAP 闭源 SO 内部定死的符号，运行时会被魔改后的 `ld-musl-android.so` 隐式欺骗并完美对接到 Android 宿主驱动。
2. **极致性能（零拷贝）**：由于内存堆已交由 Bionic 托管，跨 C 库传递超大图片、音视频 Buffer 时，**无需进行任何 `memcpy` 深拷贝**，直接传递原始指针。
3. **商用级稳定性**：从 C 库源码级别阉割了 Musl 的信号夺权，完美兼容 Android 虚拟机的 GC 机制，剔除了不稳定的应用层 Hook 行为。
