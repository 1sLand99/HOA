# HAP Native .so 兼容方案 — 设计文档

> **当前实现**: 见 `app/src/main/cpp/` 下的 `libb.so` 源码及 `CLAUDE.md` 的 libb.so 章节。
> **交叉验证**: `agents/native-so-compat-analysis.md`（bionic/musl 源码对照，发现并修正了 3 个设计缺陷）
> **项目进展**: `agents/PROGRESS.md`

## 问题

HAP 中的闭源 `.so` 按 OHOS musl libc ABI 编译（NEEDED: `libc.so`），Android 使用 bionic libc。两个 libc 的类型布局不兼容，且 musl 线程代码通过 `tpidr_el0` 读取 `struct pthread*`——Android 线程由 bionic 创建，`tpidr_el0` 指向 bionic TCB。

## 核心矛盾

HAP .so 编译时按 musl 头文件 bake 了类型大小：

| 类型 | musl (aarch64) | bionic (aarch64 LP64) | 大小匹配？ |
|------|---------------|----------------------|:--------:|
| `pthread_mutex_t` | 40 字节（union 布局） | 40 字节（state+owner 布局） | ✅ 巧合同大小 |
| `pthread_cond_t` | 48 字节 | 4 字节 | ❌ |
| `pthread_rwlock_t` | 56 字节（union 布局） | 56 字节（state+writer_tid 布局） | ✅ 巧合同大小 |

运行时必须提供匹配 musl 布局的实现。但 musl 的 `__pthread_self()` 依赖 `tpidr_el0` 指向 musl 自己的 TCB——在 bionic 线程上这个前提不成立。

**为什么不能直接用 bionic 的 pthread_getspecific 解决**：bionic 的 `pthread_getspecific`/`pthread_setspecific` 内部同样依赖 `tpidr_el0` 指向 bionic TCB。在 musl 线程上调用 bionic 的这些函数，会按 bionic 的偏移量去解引用 musl 的内存——同样 crash。

**为什么不能切换 tpidr_el0**：保存 bionic 的 `tpidr_el0`、替换为 musl 的，调用完 HAP 代码再恢复——这会让 `__pthread_self()` 自然工作。但 HAP 代码执行期间如果 ART 触发 GC，GC 会读 `tpidr_el0` 来扫描线程栈——拿到 musl 的假 TCB → 不可预测行为。

## 方案：`gettid()` syscall + 全局映射表

放弃对 `tpidr_el0` 的依赖，改用内核线程 ID（`gettid()`）作为 key，在全局表中查找对应的 musl `struct pthread*`。

- **musl 线程**：`pthread_create` 的 `start()` 中注册 `{gettid(), struct pthread*}` 到全局表
- **bionic 线程**：首次 `__pthread_self()` 调用时分配 musl `struct pthread`，插入全局表
- **后续调用**：`gettid()` + 查表 → 同一线程命中同一表项

`gettid()` 是纯内核 syscall，不经过任何 libc 数据结构，对两类线程均安全。

被否决的替代方案：

| 方案 | 问题 |
|------|------|
| bionic `pthread_getspecific` 统一存取 | musl 线程上解引用 bionic TCB 偏移 → 内存损坏 |
| 进入 HAP 前切换 `tpidr_el0` | GC 期间读到 musl TCB → 不可预测行为 |
| `self->self == self` 校验区分线程类型 | bionic 线程上计算出的地址可能未映射 → SIGSEGV |

## 实际架构（libb.so）

> 以下为实际实现的简化描述，详细代码见 `CLAUDE.md` 和 `app/src/main/cpp/`。

```
Android App 进程
│
├─ libb.so  ← System.loadLibrary("b") 预加载
│   │          SONAME: libb.so（ELF .dynstr patch 将 libc.so 覆写为 libb.so）
│   │          链接: -Wl,-Bsymbolic（内部符号优先绑定自身）
│   │
│   ├─ [编译自 musl] pthread/      ← pthread_mutex/cond/rwlock + 修改后 __pthread_self()
│   ├─ [编译自 musl] stdio/        ← fopen/fprintf/printf (musl FILE* 布局)
│   ├─ [编译自 musl] dirent/       ← opendir/readdir (musl DIR* 布局)
│   ├─ [编译自 musl] signal/       ← sigaction ABI 转换 (musl 40-byte → kernel 32-byte)
│   ├─ [编译自 musl] setjmp/       ← 纯寄存器
│   ├─ [编译自 musl] internal/     ← syscall 包装、锁等支撑
│   ├─ [新增]   pthread_bridge.c   ← gettid() + 全局表 + TCB 分配 + errno
│   ├─ [新增]   malloc_bridge.c    ← TLS-swap → bionic scudo 分配器
│   ├─ [新增]   signal_bridge.c    ← sigaction ABI 转换
│   ├─ [新增]   clone_bridge.c     ← clone syscall 包装
│   ├─ [新增]   elf_patch.c        ← ELF .dynstr hot-patch (libc.so→libb.so)
│   └─ [新增]   libb.map           ← linker version script (local: *;)
│
├─ HAP 闭源 .so
│   ├─ NEEDED: libc.so  →  ELF patch 后 → libb.so
│   ├─ 调用 printf          → musl 实现                     ✅
│   ├─ 调用 pthread_mutex_* → musl 实现（40 字节布局）       ✅
│   ├─ 调用 sigaction       → signal_bridge → kernel syscall ✅
│   ├─ 调用 strlen          → bionic（ABI 兼容）             ✅
│   ├─ 调用 malloc          → malloc_bridge(TLS-swap) → bionic scudo ✅
│   └─ 调用 napi_*          → 全局符号（ArkUI-X NAPI 插件）   ✅
│
└─ Stub .so（jniLibs/arm64-v8a/）
    ├─ libhilog_ndk.z.so  →  OHOS hilog → Android logcat
    └─ libohaudio.so      →  音频 stub
```

## 关键设计决策

### malloc_bridge.c — TLS-swap 方案

实际实现不是简单的 alias 转发，而是 **per-thread bionic TLS block 交换**：

```
malloc/free 入口
  → enter_bionic_alloc(&saved)
    → 检查 tpidr_el0: 如果是 main bionic thread → 跳过
    → 保存当前 musl TP → get_bionic_tp() → msr tpidr_el0, bionic_tp
  → real_malloc/real_free (bionic scudo, resolved via dlsym(RTLD_NEXT))
  → leave_bionic_alloc(saved)
    → msr tpidr_el0, saved (恢复 musl TP)
```

主线程跳过 TLS 交换（fast path），musl 线程走完整的 save→swap→call→restore 路径。

### signal_bridge.c — sigaction ABI 转换

musl `struct sigaction` 为 40 字节（sa_handler@0），kernel 期望 32 字节。bridge 做布局转换后直接通过 `SYS_rt_sigaction` syscall 注册，绕过 bionic 的 sigaction 包装。

### 链接策略

- `-Wl,-Bsymbolic`: libb.so 内部符号引用优先绑定自身定义，防止被 `libsigchain.so` 等抢占
- `libb.map`: `local: *;` 隐藏未列出的符号
- ELF .dynstr hot-patch: 遍历已加载 ELF，将 "libc.so\0"（正好 8 字节）原地覆写为 "libb.so\0"

## 源码修改汇总

**musl 源码**（`third_party/musl/`，3 文件）：

| 文件 | 修改 |
|------|------|
| `src/internal/pthread_impl.h:174` | `__pthread_self()` 宏改为 `__musl_bridge_self()` 函数调用 |
| `src/thread/pthread_create.c` | `start()` / `start_c11()` 开头加 `__musl_bridge_register(self)` |
| `src/thread/pthread_key_create.c` | 移除 `__builtin_trap()`，改为安全 early return |

**HOA 源码**（`app/src/main/cpp/`，9 文件）：

| 文件 | 职责 |
|------|------|
| `pthread_bridge.c` | gettid() + 全局表 + struct pthread 分配 |
| `malloc_bridge.c` | TLS-swap → bionic scudo |
| `signal_bridge.c` | sigaction ABI 转换 (musl→kernel) |
| `clone_bridge.c` | clone syscall 包装 |
| `elf_patch.c` | ELF .dynstr hot-patch + 拓扑依赖加载 |
| `elf_patch_jni.c` | JNI 接口 |
| `libb.map` | 符号可见性控制 |
| `Makefile.musl_bridge` | 构建 (NDK 28+, -nostdlib) |
| `build_musl_bridge.sh` | 构建 wrapper |

## 各目录编译策略

| 目录 | 策略 | 原因 |
|------|------|------|
| `src/thread/` | 原样编译 | pthread 类型布局必须与 HAP .so 一致 |
| `src/stdio/` | 原样编译 | FILE* 是 opaque pointer |
| `src/internal/` | 原样编译 | libc 内部支撑 |
| `src/dirent/` | 原样编译 | DIR* 是 opaque pointer |
| `src/signal/` | 原样编译 | sigaction ABI 转换需 musl 内部结构 |
| `src/setjmp/` | 原样编译 | 纯寄存器，与 TLS 无关 |
| `src/malloc/` | 不编译 | 由 malloc_bridge.c 代理到 bionic scudo |
| `src/env/` | 不编译 | `__libc_start_main` 是进程入口，.so 不会调 |
| `src/string/`、`src/math/`、`src/ctype/`、`src/time/`、`src/stdlib/` 等 | 不编译 | ABI 兼容，运行时解析到 bionic |

## 构建

libb.so 使用 NDK 28+ 独立构建（与 APK 的 NDK 21 分离）：

```bash
cd app/src/main/cpp
MUSL=$ARKUI_X_SRC/third_party/musl bash build_musl_bridge.sh
```

**关键：include path 顺序**。musl 源文件 include `<pthread.h>` 时期望 musl 的类型定义。bridge 文件需要 NDK headers。两类文件用不同的 include flags：

```
# musl 源文件（需要 musl internal headers）
CFLAGS_musl  = -I$(MUSL)/include -I$(MUSL)/arch/aarch64 \
               -I$(MUSL)/src/internal -I$(MUSL)/arch/generic \
               -isystem $(NDK_SYSROOT)/usr/include

# bridge 文件（只用 NDK headers）
CFLAGS_bridge = -isystem $(NDK_SYSROOT)/usr/include
```

链接：`-shared -nostdlib -Wl,-Bsymbolic -Wl,--version-script=libb.map -Wl,-soname,libb.so`

## 性能

`__pthread_self()` 调用频率（详见 `native-so-compat-analysis.md` §2.5）：

| 操作 | 原来（纯 musl） | 本方案 | 影响 |
|------|----------------|--------|------|
| `__pthread_self()` | 1 条 `mrs tpidr_el0` | `gettid()` syscall + 线性扫描 | syscall ~200 cycles，扫描 < 10 条目 |
| `pthread_mutex_lock` (NORMAL) | atomics ± futex | 同上（不调 `__pthread_self()`） | **零影响** |
| `printf` 整体 | write syscall + `__lockfile` | `__lockfile` 中 `gettid()` 替代 `mrs` | write syscall > 10,000 cycles，占比 < 2% |

关键：NORMAL mutex（默认类型）的 fast path 纯 CAS，不走 `__pthread_self()`，零开销。

## 参考

| 文件 | 说明 |
|------|------|
| `agents/native-so-compat-analysis.md` | bionic/musl 源码交叉验证，struct pthread 字段偏移出处 |
| `CLAUDE.md` | libb.so 当前实现架构、malloc_bridge TLS-swap 详解 |
| `agents/PROGRESS.md` | 项目进展，已验证能力列表 |
