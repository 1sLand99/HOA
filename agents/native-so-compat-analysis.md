# Native .so 兼容方案 — musl/bionic 源码交叉验证

> **设计文档**: `agents/native-so-compat.md`（方案概述、架构图、构建说明）
> **当前实现**: `CLAUDE.md` libb.so 章节 + `app/src/main/cpp/` 源码

对照 bionic 和 OHOS musl 源码，验证方案关键假设。bionic 源码位于本机 `bionic/` 目录，musl 源码位于 `third_party/musl/`。

---

## 1. TLS / TCB 布局对比

### musl (aarch64)

`tpidr_el0` → `TP_ADJ(self) = (char*)self + sizeof(struct pthread)`（TCB 末尾，TLS 起始处）。

`__pthread_self()`:
```c
#define __pthread_self() ((pthread_t)(__get_tp() - sizeof(struct __pthread) - TP_OFFSET))
// TP_OFFSET=0 on aarch64 → = tp - sizeof(struct __pthread)
```

`struct __pthread` 前 12 个字段（偏移 0-47）：

| 偏移 | 大小 | 字段 | 类型 |
|------|------|------|------|
| 0 | 8 | `self` | `struct pthread *` |
| 8 | 8 | `prev` | `struct pthread *` |
| 16 | 8 | `next` | `struct pthread *` |
| 24 | 8 | `sysinfo` | `uintptr_t` |
| 32 | 4 | `tid` | `int` |
| 36 | 4 | `pid` | `int` |
| 40 | 4 | **`proc_tid`** | `int` |
| 44 | 4 | **`errno_val`** | `int` |
| 48 | 4 | `by_vfork` | `int` |
| 52 | 4 | `detach_state` | `volatile int` |

### bionic (aarch64)

`tpidr_el0` → `&tcb->tls_slot(0)`（TCB 起点，DTV slot）。`bionic_tcb` 是一个 11 元素的 `void*` 数组。

| Slot | 索引 | 用途 |
|------|------|------|
| `TLS_SLOT_BIONIC_TLS` | -1 | `bionic_tls*` 指针 |
| `TLS_SLOT_DTV` | 0 | ELF 动态线程向量 |
| `TLS_SLOT_THREAD_ID` | **1** | `pthread_internal_t*` |

`__get_thread()`:
```cpp
// tpidr_el0[1] — 即 slot TLS_SLOT_THREAD_ID
return static_cast<pthread_internal_t*>(__get_tls()[TLS_SLOT_THREAD_ID]);
```

`pthread_internal_t` 前几个字段（LP64）：

| 偏移 | 字段 |
|------|------|
| 0 | `next` (pthread_internal_t*) |
| 8 | `prev` (pthread_internal_t*) |
| 16 | `tid` (pid_t) |
| 20 | `cached_pid_` (uint32_t 位域) |
| 24 | `attr` (48 字节) |
| … | … |
| ~64 | `errno_value` (int) |

### 关键差异

- musl 的 `self`（偏移 0）指向自身；bionic 无等效字段
- musl 从 TP 向下找 TCB（`tp - sizeof`）；bionic 从 TP 向上找线程指针（`tp[1]`）
- **互不兼容**：musl 线程上的 `tpidr_el0` 指向 musl TLS block，bionic `__get_thread()` 对它读 `[1]` 拿到 musl TLS 数据当指针 → crash

---

## 2. 已验证关键问题

### 2.1 bionic rwlock 在 musl 线程上崩溃（设计缺陷）

bionic 的 `pthread_rwlock_t`（`int32_t __private[14]`，56 字节）内部结构（`bionic/libc/bionic/pthread_rwlock.cpp`）：

```cpp
struct pthread_rwlock_internal_t {
    atomic_int state;       // bit31=owned_by_writer, bits30-2=reader_count
    atomic_int writer_tid;  // 存储 writer 的 tid
    // ... pending queues, wakeup serials, etc.
};
```

对方案的影响：

| bionic rwlock 函数 | 调用 `__get_thread()->tid` 吗？ | musl 线程能安全调用？ |
|---------------------|-------------------------------|---------------------|
| `rdlock` (fast/tryrdlock) | 否（纯 atomic CAS） | ✅ |
| `rdlock` (slow/timedrdlock) | **是**（死锁检测，line 292） | ❌ crash |
| `wrlock` (trywrlock) | **是**（存储 `writer_tid`，line 355） | ❌ crash |
| `wrlock` (slow/timedwrlock) | **是**（死锁检测 + 存储 tid） | ❌ crash |
| `unlock` | **是**（所有权校验，line 492） | ❌ crash |

**结论**：当前设计中的 `bionic_rdlock`/`bionic_wrlock`/`bionic_unlock` 仅在 bionic 线程上安全。`__musl_bridge_register()` 在 musl 线程的 `start()` 中调用——**musl 线程上执行 bionic_wrlock 必然 crash**。

即使 wrlock 的 trywrlock 路径能成功（无竞争时可能走 fast path），unlock 路径始终需要校验 `writer_tid == __get_thread()->tid`，而 `__get_thread()` 在 musl 线程上读的是垃圾。

**修复**：不用 bionic rwlock，改用纯 futex + atomics（不依赖任何 libc TLS）：

```c
static _Atomic int table_lock = 0;

static void lock_table(void) {
    while (__atomic_exchange_n(&table_lock, 1, __ATOMIC_ACQUIRE))
        __syscall(SYS_futex, &table_lock, FUTEX_WAIT, 1, 0, 0, 0);
}
static void unlock_table(void) {
    __atomic_store_n(&table_lock, 0, __ATOMIC_RELEASE);
    __syscall(SYS_futex, &table_lock, FUTEX_WAKE, 1, 0, 0, 0);
}
```

Futex syscall 是纯内核调用，不经过任何 libc 数据结构，对两类线程均安全。

替换后，原设计中的 `dlsym(RTLD_NEXT, ...)` 查找 bionic rwlock 函数指针的步骤可以删掉。

> 注：这个锁保护的是只增不减的 128 槽位数组，写操作（register + bionic-thread 首次分配）极少。即使不用 rwlock 用简单的 mutex，读路径被写阻塞的概率也极低（< 10 个线程 x 每线程至多 1 次写）。

### 2.2 `struct pthread` 前向声明缺少 `proc_tid`（内存损坏）

当前设计的前向声明：

```c
struct pthread {
    struct pthread *self;
    struct pthread *prev, *next;
    uintptr_t sysinfo;
    int tid;
    int pid;
    int errno_val;   // ← 实际在偏移 40（应该是 44）
};
```

`pid` 是 4 字节 int 在偏移 36，所以 `errno_val` 在偏移 40。但真实 musl 布局中 `errno_val` 在偏移 44（`proc_tid` 在 40）。

**后果**：HAP .so 通过 musl 编译的 `__errno_location()` 访问 `self->errno_val`，使用真实偏移 44。如果 bridge 分配的这个 struct 只有 forward-declared 大小（~44 字节），偏移 44 处的 4 字节写入会**越界** → heap corruption。

更深层问题：`sizeof(struct pthread)` 在 forward declaration 中约 44-48 字节，但真实 musl struct 有 **200+ 字节**。musl 代码还会访问更深层的字段（`self->locale` 偏移 160、`self->dlerror_buf` 偏移 176 等），都需要在分配的内存范围内。

**修复**：不用 forward declaration 的 sizeof，分配一个足够大的缓冲区（至少覆盖 musl 真实 struct 大小 ~400 字节）：

```c
// 8192 字节远远大于任何 musl 平台上的 struct pthread
#define MUSL_PTHREAD_MAX_SIZE 8192

struct pthread *new_self = calloc(1, MUSL_PTHREAD_MAX_SIZE);
```

同时修正前向声明，补全 `proc_tid` 字段：

```c
struct pthread {
    struct pthread *self;
    struct pthread *prev, *next;
    uintptr_t sysinfo;
    int tid;
    int pid;
    int proc_tid;
    int errno_val;
};
```

### 2.3 musl 源代码用 NDK headers 编译时的类型冲突

musl 源码（`src/thread/pthread_mutex_lock.c` 等）include `<pthread.h>`，期望读到 musl 的 `pthread_mutex_t`（union of int[10], 40 字节）。用 NDK clang 编译时，默认 include path 指向 NDK sysroot，`<pthread.h>` 会给 bionic 的类型定义。

尽管两者在 aarch64 上都是 40 字节，内部布局完全不同：

| 字段 | musl 访问方式 | bionic 布局位置 |
|------|-------------|---------------|
| `_m_type` | `m->_m_type` (bits 0-3 of int slot 0) | 对应 bionic `state` 的低 4 bits |
| `_m_lock` | `m->_m_lock` (int slot 0, bits 4-7) | 对应 bionic `state` 的 bits 4-7 |
| `_m_waiters`等 | 其余 int slots | 对应 bionic 的 `owner_tid`/`pi_mutex`/`__reserved` |

虽然在最简单的 NORMAL mutex lock/unlock（纯 CAS 在 slot 0）时恰好部分兼容，但对于递归/errorcheck 类型、condvar、rwlock 等复杂类型完全不兼容。

**修复**：Makefile 中为 musl 源文件设置 include path 时，musl header 目录必须在 NDK sysroot 之前：

```
# musl 源文件编译 flags
CFLAGS_musl = -I$(MUSL)/include -I$(MUSL)/arch/aarch64 \
              -I$(MUSL)/src/internal -I$(MUSL)/arch/generic \
              -isystem $(NDK_SYSROOT)/usr/include
```

bridge 文件（`pthread_bridge.c`、`malloc_bridge.c`）不需要 musl headers，使用默认 NDK include path。

### 2.4 bionic 线程列表排除 musl 线程（可接受）

bionic 维护全局双向链表 `g_thread_list`（`pthread_internal.cpp`）。通过 musl `__clone()` 创建的线程不在该列表中。影响：

| 受影响功能 | 影响分析 |
|-----------|---------|
| `pthread_join()` (bionic 端) | 不能 join 到 musl 线程 — 但 HAP 线程由 HAP 管理，不受影响 |
| `android_run_on_all_threads()` | 不会向 musl 线程发送信号 — 可接受 |
| stack MTE 重映射 | 跳过 musl 线程 — 可接受 |

这些限制在风险表中已有对应，无需额外修复。

### 2.5 `__pthread_self()` 调用频率分析

基于 musl 源码审计（完整列表见探索报告，此处只列热路径）：

| 调用点 | 是否每个操作都调 | 说明 |
|--------|:---:|------|
| `pthread_mutex_lock` (NORMAL, uncontended) | **否** | 纯 CAS，零开销 |
| `pthread_mutex_lock` (recursive/errorcheck) | **是** | 需要 `self->tid` 做 owner 校验 |
| `pthread_cond_timedwait` | **是 (3x+)** | 无条件调用 |
| `pthread_rwlock_*` (musl) | **否** | musl rwlock 用纯 atomic，不调 `__pthread_self()` |
| `__lockfile` (stdio `fread`/`fwrite` 等) | **是** | 每次 FILE 操作都会调，检测递归锁 |
| `pthread_setcancelstate` | **是** | 无条件调用 |
| `__errno_location` | **是** | 每次读/写 errno 都会调 |

**性能要点**：
- NORMAL mutex（默认类型）的 fast path 不走 `__pthread_self()`，当前方案不影响此类 mutex 性能
- stdio 的 `__lockfile` 在每次 `fread`/`fwrite`/`printf` 时都会走 `__pthread_self()` → `gettid()` + 查表，是性能敏感点
- `pthread_cond_timedwait` 每调用 3 次以上 `__pthread_self()`，在信号等待等场景下有影响

---

## 3. 无需修改的已验证结论

### 3.1 bionic NORMAL mutex 在 musl 线程上安全

bionic 的 `pthread_mutex_lock` (NORMAL) fast path：
```cpp
// 仅 atomic_compare_exchange_strong(state, UNLOCKED → LOCKED_UNCONTENDED)
```
不调用 `__get_thread()`，不读 TLS。这意味着 bridge 文件中的 `self_map_lock` 如果用 bionic mutex（NORMAL 类型），从 musl 线程调用也安全。

但方案 2.1 已改为 futex 方案，不再需要这个验证。

### 3.2 `pthread_mutex_t` / `pthread_rwlock_t` 大小匹配

| 类型 | musl aarch64 | bionic LP64 | 匹配？ |
|------|-------------|-------------|:---:|
| `pthread_mutex_t` | 40 字节 | 40 字节 | ✅ |
| `pthread_rwlock_t` | 56 字节 | 56 字节 | ✅ |
| `pthread_cond_t` | 48 字节 | 4 字节 | ❌ 不匹配但独立使用 |

大小匹配是巧合——内部布局完全不兼容，但各自在封闭的代码路径中使用，互不干扰。

### 3.3 musl rwlock 不调 `__pthread_self()`

musl 的 `pthread_rwlock_rdlock.c`、`pthread_rwlock_wrlock.c` 等使用纯 atomic 操作（CAS、fetch_add），**不调用 `__pthread_self()`**。这与 bionic 的 rwlock（需要存储 `writer_tid`）形成对比。这意味着 musl rwlock 在两类线程上都能正常工作，不受桥接影响。

不过需注意：rwlock 的慢速路径调用 `__timedwait()` → `__pthread_setcancelstate()` → `__pthread_self()`，但此时线程必须已经注册（或首次调用 auto-register），不是问题。

### 3.4 bionic scudo 分配器的 TLS slot 冲突（已解决：TLS-swap）

bionic 的 scudo 分配器（`malloc` 的底层实现）使用 `TLS_SLOT_SANITIZER`（aarch64 上 `__get_tls()[6]`）存储每线程分配器状态：

```cpp
// bionic/libc/platform/scudo_platform_tls_slot.h
inline uintptr_t* getPlatformAllocatorTlsSlot() {
    return reinterpret_cast<uintptr_t*>(&__get_tls()[TLS_SLOT_SANITIZER]);
}
```

在 musl 创建的线程上，`__get_tls()` 返回 musl 的 TP（指向 musl TLS block），`[6]` 访问的是 musl TLS 区域内的内存。如果 scudo 向此地址写入每线程状态指针，会**损坏 musl 内部数据**。

**实际解决方案（malloc_bridge.c）**：不再规避此问题，而是在每次进入 bionic 分配器前切换到 per-thread bionic TLS block：

```
malloc/free 入口
  → enter_bionic_alloc(&saved)
    → 检查 tpidr_el0: 如果是 main bionic thread → 跳过（fast path）
    → 保存当前 musl TP → get_bionic_tp() → msr tpidr_el0, bionic_tp
  → real_malloc/real_free (bionic scudo)
  → leave_bionic_alloc(saved)
    → msr tpidr_el0, saved (恢复 musl TP)
```

- Per-thread bionic TLS block 用 `real_calloc(1, 64)` 分配，零初始化，scudo 可以安全读写 TLS_SLOT_SANITIZER
- TLS table 用 futex-based spinlock 保护
- 主线程的 bionic_tp 在 constructor 中捕获（`mrs tpidr_el0`），用于 bootstrap 新 TLS block 分配
- `real_malloc`/`real_free` 通过 `dlsym(RTLD_NEXT, ...)` 解析，避免 PLT 递归

> **原始分析（已修正）**：早期方案计划"musl 线程的 malloc/free 走 musl 自己的 mallocng，禁止 musl 自建线程"。实际实现完全解决了此问题，musl 线程的 malloc/new/delete 均通过 TLS-swap 正常工作。详见 `CLAUDE.md` 的 malloc_bridge.c 章节。

### 3.5 musl `__thread` / `_Thread_local` 变量

**结论（2026-05-31 修正）**：OHOS NDK (BiSheng) 和 Android NDK 均默认将 `__thread` 编译为 **emutls**（`__emutls_get_address`，LLVM 编译器内置的用户态 TLS），不生成 native ELF TLS 重定位。emutls 基于全局哈希表 + 线程私有存储，不依赖 TPIDR_EL0，在 bionic 线程开箱即用。只有显式传 `-fno-emulated-tls` 才会生成 native TLSDESC/GD 重定位。

实测验证：`static __thread int entry_tls_var = 0xCAFE` 在 HAP .so (OHOS NDK 编译) 中产生 `__emutls_v._ZL13entry_tls_var` 符号和 `__emutls_get_address@plt` 调用，主线程读写隔离正确，ALL 49 TESTS PASSED。

**原始分析（已修正）**：

> ~~AArch64 上共享库默认使用 General Dynamic (GD) 模型，访问 `__thread` 变量时调用 `__tls_get_addr(module_id, offset)` 走 GOT，可被 GOT patching 拦截。~~

这个假设错误——aarch64 LLVM 工具链（包括 OHOS BiSheng 和 Android NDK）的默认 TLS 策略是：
1. **默认**：emutls（`-femulated-tls`，LLVM 默认）——纯用户态实现，生成 `__emutls_get_address` 调用
2. **`-fno-emulated-tls`**：TLSDESC（aarch64 原生 TLS ABI）——生成 `R_AARCH64_TLSDESC_*` 重定位，由 linker 的 TLSDESC resolver 处理，不调 `__tls_get_addr`
3. **`-fno-emulated-tls -ftls-model=global-dynamic`**：实测仍生成 TLSDESC（aarch64 上 GD=TLSDESC+Lazy）

**对 HOA 的影响**：
| 场景 | 编译器 | 模型 | 状态 |
|------|--------|------|------|
| HAP .so `__thread`（默认构建） | OHOS BiSheng | emutls | ✅ 开箱可用（bionic 线程） |
| HAP .so `__thread` + `-fno-emulated-tls` | OHOS BiSheng | TLSDESC | ❌ 需干预 TLSDESC entry（musl 线程） |
| libb.so `__thread` | Android NDK | emutls | ✅ 开箱可用 |

**后续：musl 线程的 native TLS**：
若 HAP 显式编译为 native TLS（`-fno-emulated-tls`），需要在 musl 线程上干预 TLSDESC entry：
- 注册 HAP .so 的 PT_TLS 到 musl `libc.tls_head` 链（`pthread_bridge.c` 已预留基础设施）
- TLSDESC entry 级 patch（非 GOT 符号 patch）使 musl 线程返回 musl TP 兼容的偏移
- bionic 线程保持原 TLSDESC resolver 不变

### 3.6 bridge struct 必须初始化的字段（源码审计结论）

通过审计 musl 所有 `__pthread_self()->field` 访问路径，确定 bridge 分配的 `struct pthread` 最小初始化清单：

| 字段 | 偏移 | 必需值 | 理由 |
|------|------|--------|------|
| `self` | 0 | `= self` | musl 代码可能检查 |
| `prev` / `next` | 8 / 16 | `= self`（自环） | 线程链表操作；`__pthread_exit` 会 unlink |
| `tid` | 32 | `= gettid()` | `__lockfile`、`pthread_cond_timedwait`、mutex owner 检查 |
| `pid` | 36 | `= getpid()` | `pthread_kill` 等 |
| `errno_val` | 44 | `= 0` | `__errno_location()` 返回值 |
| **`tsd`** | 120 | **必须指向有效的 `void*[128]`** | **NULL 会在 `pthread_getspecific`/`pthread_setspecific` 时 crash**（直接 `self->tsd[k]`，无 NULL 检查）。`pthread_key_create` 在 `tsd==NULL` 时会指向 `__pthread_tsd_main`，导致 TSD 与主线程共享——数据损坏 |
| **`locale`** | 160 | **必须指向有效 `struct __locale_struct`** | **NULL 会在 `CURRENT_UTF8`、`strerror()`、`%m`、任何 wide-char 函数中 crash**。应设为 `&libc.global_locale` |
| `robust_list.head` | 128 | `= &self->robust_list.head` | mutex robust 列表初始化 |
| `detach_state` | 52 | `= DT_JOINABLE` (2) | `pthread_detach`/`pthread_join` 依赖此值 |
| `killlock[1]` | 168 | `= 0`（未锁定） | `pthread_kill` 和 `__pthread_exit` 会 lock/unlock 此字段 |
| `canceldisable` | 220 | `= PTHREAD_CANCEL_DISABLE` (1) | 安全默认：禁止取消 |
| `cancelasync` | 221 | `= 0` | 延迟取消模式 |
| `cancel` | 216 | `= 0` | 未请求取消 |
| `dlerror_flag` | 56 (位域) | `= 0` | 无 dlerror 待处理 |
| `dlerror_buf` | 176 | `= NULL` | 无 dlerror 消息 |

`tsd` 数组需要额外 1024 字节（`PTHREAD_KEYS_MAX=128 × sizeof(void*)=8`）。应在 `MUSL_PTHREAD_MAX_SIZE` 中留出空间，或单独分配。

### 3.7 信号 ABI 桥接（已实现：signal_bridge.c）

> **当前实现**: `signal_bridge.c` 完整解决了 musl/bionic sigaction ABI 不兼容问题，见 `CLAUDE.md`。

musl 使用信号 33（`SIGCANCEL = SIGRTMIN+1`，内核编号）做线程取消。bionic 的内核信号布局：

| 内核信号 | bionic 用途 | 是否阻塞 | 
|---------|-----------|---------|
| 32 | POSIX timers (`BIONIC_SIGNAL_POSIX_TIMERS`) | **始终阻塞**（用户代码不可见） |
| 33 | libbacktrace (`BIONIC_SIGNAL_BACKTRACE`) | 保持不阻塞，无默认 handler |
| 34 | debuggerd (`BIONIC_SIGNAL_DEBUGGER`) | 保持不阻塞 |

musl 的 SIGCANCEL = 33。bionic 对信号 33 **保持不阻塞且无默认 handler**，因此 musl 可以为它安装 handler。信号 33 可用，bionic 不做拦截。如果 HAP 代码从不调 `pthread_cancel()`（常见情况），则 SIGCANCEL handler 不会被安装，完全无冲突。

实际 signal_bridge.c 实现：
- musl `struct sigaction`（40 字节，sa_handler@0）→ kernel 布局（32 字节）转换
- 直接通过 `SYS_rt_sigaction` syscall 注册，绕过 bionic 的 sigaction 包装
- 拒绝 musl 保留信号 32-34（SIGCANCEL/SIGSYNCCALL/SIGTIMER）
- 配合 `-Wl,-Bsymbolic` 防止被 `libsigchain.so` 抢占

### 3.8 线程退出与 tid 回收

musl 的 `__pthread_exit()` 在退出前执行 `self->tid = 0`（注释："After the kernel thread exits, its tid may be reused. Clear it to prevent inadvertent use"）。这意味着：

- **musl 线程**：正常退出后，`self->tid = 0`，后续 `gettid()` 不会命中此条目。安全。
- **bionic 线程**：不经过 musl 的 `__pthread_exit()`，bridge struct 的 `tid` 保持原值。线程退出后，kernel 可能将 tid 分配给新线程。新线程 `gettid()` 命中旧条目 → 返回旧 `struct pthread`。

实际的 bionic 线程场景：
- 主线程：进程生命周期 = 主线程生命周期，不会退出
- 线程池线程：复用，不退出
- 临时线程：少数，tid 回收概率低

**风险等级**：低。HAP 调用场景下线程数极少（< 5），128 槽位足以覆盖整个进程生命周期。旧条目的 TSD null 状态与新线程相同，功能上正确。唯一的隐患是旧线程的 TSD 值泄露（如果旧线程使用过 `pthread_setspecific` 且新线程恰好 hit 同一条目），但 HAP 代码自己管理 TSD key，概率极低。

后续优化：利用 bionic 的 `pthread_key_create` 注册析构函数检测线程退出，但当前阶段不必要。

---

## 4. 修改要点汇总

| # | 问题 | 修改位置 | 修改内容 |
|---|------|---------|---------|
| 1 | bionic rwlock 在 musl 线程上 crash | `pthread_bridge.c` | 用 `futex + atomic` 替代 bionic rwlock |
| 2 | 前向声明 sizeof 不足以覆盖 musl 真实 struct | `pthread_bridge.c` | 用固定大缓冲区（8192B）替代 `sizeof(struct pthread)`；同时补 `proc_tid` 字段 |
| 3 | NDK headers 编译冲突 | `Makefile.musl_bridge` | musl 源文件的 include path 中将 musl headers 放在 NDK sysroot 之前 |
| 4 | `self->tsd` = NULL 导致 crash | `pthread_bridge.c` | 分配 128×void* TSD 数组，写入偏移 120 |
| 5 | `self->locale` = NULL 导致 crash | `pthread_bridge.c` | 设置偏移 160 为 musl `&libc.global_locale`（extern 声明） |
| 6 | `self->prev`/`next` = NULL 导致 `__pthread_exit` unlink 时 crash | `pthread_bridge.c` | 初始化为自环 |
| 7 | `robust_list.head` 未初始化 | `pthread_bridge.c` | 指向自身 |
| 8 | `detach_state` = 0 导致 `pthread_exit` 不清理 | `pthread_bridge.c` | 设为 DT_JOINABLE (2) |
| 9 | `canceldisable` = 0（允许取消）在 bionic 线程上不安全 | `pthread_bridge.c` | 设为 1（PTHREAD_CANCEL_DISABLE） |
| 10 | scudo TLS slot 冲突（musl 线程上 bionic malloc） | `malloc_bridge.c` | **已解决**：TLS-swap（enter_bionic_alloc/leave_bionic_alloc），每次分配前切换到 per-thread bionic TLS block |

## 参考

| 文件 | 说明 |
|------|------|
| `bionic/libc/bionic/pthread_mutex.cpp` | bionic mutex 实现 |
| `bionic/libc/bionic/pthread_rwlock.cpp` | bionic rwlock 实现 |
| `bionic/libc/platform/bionic/tls.h` | `__get_tls()` 内联 (aarch64: `mrs tpidr_el0`) |
| `bionic/libc/platform/bionic/tls_defines.h` | TLS slot 常量 |
| `bionic/libc/bionic/pthread_internal.h` | `pthread_internal_t` 结构 + `__get_thread()` |
| `bionic/libc/bionic/pthread_internal.cpp` | 全局线程链表 |
| `bionic/libc/bionic/pthread_exit.cpp` | 线程退出清理 |
| `bionic/libc/bionic/__errno.cpp` | errno 存储 |
| `bionic/libc/bionic/pthread_key.cpp` | `pthread_getspecific` — 为什么在 musl 线程上 crash |
| `third_party/musl/src/internal/pthread_impl.h` | musl `struct __pthread` + `__pthread_self()` |
| `third_party/musl/arch/aarch64/pthread_arch.h` | musl `__get_tp()` |
