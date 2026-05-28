# HAP Native .so 兼容方案

> 交叉验证报告：`agents/native-so-compat-analysis.md`（bionic/musl 源码对照，发现并修正了 3 个设计缺陷）

## 问题

HAP 中的闭源 `.so` 按 OHOS musl libc ABI 编译（NEEDED: `libc.musl-aarch64.so.1`），Android 使用 bionic libc。两个 libc 的类型布局不兼容，且 musl 线程代码通过 `tpidr_el0` 读取 `struct pthread*`——Android 线程由 bionic 创建，`tpidr_el0` 指向 bionic TCB，直接编译 musl `src/thread/` 会导致 `__pthread_self()` 返回垃圾指针。

## 核心矛盾

HAP .so 编译时按 musl 头文件 bake 了类型大小：

| 类型 | musl (aarch64) | bionic (aarch64 LP64) | 大小匹配？ |
|------|---------------|----------------------|:--------:|
| `pthread_mutex_t` | 40 字节（union 布局） | 40 字节（state+owner 布局） | ✅ 巧合同大小 |
| `pthread_cond_t` | 48 字节 | 4 字节 | ❌ |
| `pthread_rwlock_t` | 56 字节（union 布局） | 56 字节（state+writer_tid 布局） | ✅ 巧合同大小 |

运行时必须提供匹配 musl 布局的实现。但 musl 的 `__pthread_self()` 依赖 `tpidr_el0` 指向 musl 自己的 TCB——在 bionic 线程上这个前提不成立。

**为什么不能直接用 bionic 的 pthread_getspecific 解决**：bionic 的 `pthread_getspecific`/`pthread_setspecific` 内部同样依赖 `tpidr_el0` 指向 bionic TCB。在 musl 线程上调用 bionic 的这些函数，会按 bionic 的偏移量去解引用 musl 的内存——同样 crash。两类线程的 TLS 机制是**互不兼容**的，不能互相调用对方的 TLS 函数。

**为什么不能切换 tpidr_el0**：保存 bionic 的 `tpidr_el0`、替换为 musl 的，调用完 HAP 代码再恢复——这会让 `__pthread_self()` 自然工作。但 HAP 代码执行期间如果 ART 触发 GC，GC 会读 `tpidr_el0` 来扫描线程栈——拿到 musl 的假 TCB → 不可预测行为。GC 时机不可控，风险无法消除。

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

## 架构

```
Android App 进程
│
├─ libmusl_bridge.so  ← System.loadLibrary 预加载
│   │
│   ├─ [编译自 musl] stdio/         ← musl 的 fopen/fprintf/printf
│   ├─ [编译自 musl] thread/        ← pthread_mutex/cond/rwlock + 修改后 __pthread_self()
│   ├─ [编译自 musl] internal/      ← syscall 包装、锁等支撑
│   ├─ [编译自 musl] dirent/        ← opaque DIR*
│   ├─ [编译自 musl] setjmp/        ← 纯寄存器
│   ├─ [新增]   pthread_bridge.c    ← gettid() + 全局表 + TCB 分配
│   ├─ [新增]   malloc_bridge.c     ← __libc_malloc_impl → bionic jemalloc
│   ├─ [新增]   libmusl_bridge.map  ← linker version script
│   │
│   ├─ [UNDEFINED]  string/        不编译，运行时解析到 bionic
│   ├─ [UNDEFINED]  math/          不编译，运行时解析到 bionic
│   ├─ [UNDEFINED]  signal/        不编译，信号全部走 bionic
│   └─ [UNDEFINED]  ... 其他 ABI-compatible 目录
│
├─ HAP 闭源 .so
│   ├─ NEEDED: libc.musl-aarch64.so.1  →  软链接到 libmusl_bridge.so
│   ├─ 调用 printf          → musl 实现                     ✅
│   ├─ 调用 pthread_mutex_* → musl 实现（40 字节布局）       ✅
│   ├─ 调用 strlen          → bionic（ABI 兼容）             ✅
│   ├─ 调用 malloc          → malloc_bridge → bionic jemalloc（单堆） ✅
│   └─ 调用 napi_*          → 全局符号（ArkUI-X NAPI 插件）   ✅
│
└─ Stub .so（jniLibs/arm64-v8a/）
    ├─ libc.musl-aarch64.so.1  →  软链接到 libmusl_bridge.so
    └─ libhilog_ndk.z.so 等   →  OHOS 专有库最小 stub
```

## 修改清单

### 1. `pthread_impl.h` — 替换 `__pthread_self()` 宏

**文件**：`third_party/musl/src/internal/pthread_impl.h:174`

原来（aarch64 + TLS_ABOVE_TP）：

```c
#define __pthread_self() ((pthread_t)(__get_tp() - sizeof(struct __pthread) - TP_OFFSET))
```

改为委托 `pthread_bridge.c` 中的统一实现：

```c
// __pthread_self 统一走 gettid() + 全局映射表（pthread_bridge.c）
extern hidden struct pthread *__musl_bridge_self(void);
#define __pthread_self() __musl_bridge_self()
```

`__get_tp()` 宏保留（musl 线程入口 `start()` 中仍用它计算初始 self 地址来注册）。

### 2. `pthread_create.c` — 新线程注册到全局表

**文件**：`third_party/musl/src/thread/pthread_create.c:203`

`start()` 开头加一行注册。此时 TP 已设置，`__get_tp()` 计算 safe：

```c
static int start(void *p)
{
    struct start_args *args = p;
    // 注册到全局映射表，保证后续 __pthread_self() 能通过 gettid() 找到本线程
    __musl_bridge_register(
        (struct pthread *)(__get_tp() - sizeof(struct __pthread) - TP_OFFSET));
    // ... 以下不变
    int state = args->control;
    ...
}
```

`start_c11()` 同理。

### 3. 新增 `pthread_bridge.c` — 全局映射表 + `__pthread_self()`

此文件**不 include `pthread_impl.h`**（避免 `#define pthread __pthread` 宏替换污染）。同步锁使用纯 futex + atomics，**不依赖任何 libc TLS**（bionic rwlock 内部调用 `__get_thread()->tid` 做所有权校验，在 musl 线程上 `tpidr_el0` 指向 musl TCB → 读到垃圾 → crash。详见 `agents/native-so-compat-analysis.md` §2.1）：

```c
#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <linux/futex.h>

// ── futex 自旋锁（纯内核调用，不经过任何 libc TLS）──────────────
// 为什么不用 bionic rwlock：bionic 的 wrlock/unlock 在 LP64 上调
// __get_thread()->tid（存储/校验 writer_tid）。musl 线程 tpidr_el0
// 指向 musl TCB，bionic 的 __get_thread() 对它读 slot[1] → crash。
// futex 是纯内核 syscall，对两类线程均安全。
static _Atomic int table_lock = 0;

static void lock_table(void) {
    while (__atomic_exchange_n(&table_lock, 1, __ATOMIC_ACQUIRE))
        syscall(SYS_futex, &table_lock, FUTEX_WAIT_PRIVATE, 1, 0, 0, 0);
}
static void unlock_table(void) {
    __atomic_store_n(&table_lock, 0, __ATOMIC_RELEASE);
    syscall(SYS_futex, &table_lock, FUTEX_WAKE_PRIVATE, 1, 0, 0, 0);
}

// ── 全局映射表：gettid() → struct pthread * ──────────────────────
#define MAX_SELF_ENTRIES 128   // HAP 调用线程数远小于此值
static struct {
    pid_t tid;
    struct pthread *self;
} self_table[MAX_SELF_ENTRIES];
static int self_count = 0;

// struct pthread 前向声明（前 8 字段精确对齐 musl aarch64 真实布局）
// 其余 ~200 字节字段通过偏移量访问，见下方初始化代码
struct pthread {
    struct pthread *self;     // offset 0
    struct pthread *prev;     // offset 8
    struct pthread *next;     // offset 16
    uintptr_t sysinfo;        // offset 24
    int tid;                  // offset 32
    int pid;                  // offset 36
    int proc_tid;             // offset 40
    int errno_val;            // offset 44
};

// bionic 线程上分配 musl struct pthread 时，不能用 sizeof（前向声明
// 只有 48 字节，真实 musl struct 有 200+ 字节）。musl 代码会通过返回
// 的指针访问深处字段，必须分配足够空间。
#define MUSL_PTHREAD_MAX_SIZE 8192  // 远远大于任何 musl 平台的 struct pthread

// musl PTHREAD_KEYS_MAX = 128，每个 key 一个 void*，TSG 数组需要 1024 字节
#define MUSL_TSD_SIZE (128 * sizeof(void*))  // 1024 on LP64

// musl 线程入口调用：将 self 插入全局表
void __musl_bridge_register(struct pthread *self)
{
    lock_table();
    if (self_count < MAX_SELF_ENTRIES) {
        self_table[self_count].tid  = self->tid;  // musl 的 tid 就是 gettid()
        self_table[self_count].self = self;
        self_count++;
    }
    unlock_table();
}

// 统一入口：gettid() + 查表，两类线程均适用
struct pthread *__musl_bridge_self(void)
{
    pid_t tid = gettid();

    // 查表（表只增不减，无锁读安全——只读 self_count 和 table 数据）
    for (int i = 0; i < self_count; i++) {
        if (self_table[i].tid == tid)
            return self_table[i].self;
    }

    // 未找到 → bionic 线程首次调用，分配 musl struct pthread
    struct pthread *new_self = calloc(1, MUSL_PTHREAD_MAX_SIZE);

    // ── offset 0-44: 前向声明字段 ──────────────────────────
    new_self->self = new_self;
    new_self->prev = new_self;             // 自环链表（避免 unlink 时 NULL 解引用）
    new_self->next = new_self;
    new_self->tid  = tid;
    new_self->pid  = getpid();
    new_self->errno_val = 0;

    // ── offset 52: detach_state = DT_JOINABLE ──────────────
    // pthread_detach/pthread_join/pthread_exit 依赖此字段
    *(volatile int *)((char*)new_self + 52) = 2;  // DT_JOINABLE

    // ── offset 120: tsd → void*[128] 数组 ────────────────
    // NULL 会在 pthread_getspecific/setspecific 时 crash
    void **tsd = calloc(128, sizeof(void*));
    *(void ***)((char*)new_self + 120) = tsd;

    // ── offset 128: robust_list.head → 自身 ───────────────
    *(void **)((char*)new_self + 128) = (char*)new_self + 128;

    // ── offset 160: locale → 全局 locale ─────────────────
    // NULL 会在 CURRENT_UTF8、strerror、%m 时 crash
    extern void *global_locale;  // musl libc.global_locale（BSS，链接时解析）
    *(void **)((char*)new_self + 160) = &global_locale;

    // ── offset 220: canceldisable = 1（禁止取消）───────────
    // offset 221: cancelasync = 0（延迟取消）
    ((volatile unsigned char *)(new_self))[220] = 1;  // PTHREAD_CANCEL_DISABLE

    lock_table();
    if (self_count < MAX_SELF_ENTRIES) {
        self_table[self_count].tid  = tid;
        self_table[self_count].self = new_self;
        self_count++;
    }
    unlock_table();
    return new_self;
}
```

**关键设计决策**：

- **不 include `pthread_impl.h`**：避免 `#define pthread __pthread` + 头文件包含顺序问题。bridge 文件不调任何 musl pthread 函数，struct 深处字段通过偏移量访问（字段偏移在特定 musl commit + aarch64 下稳定，见 `agents/native-so-compat-analysis.md` §3.5 偏移量出处）
- **futex 替代 bionic rwlock**：详见 §2.1。`FUTEX_WAIT_PRIVATE`/`FUTEX_WAKE_PRIVATE` 是进程内 futex，省去跨进程开销
- **查表无锁化**：表只增不减（线程退出后不删除），内核保证 `gettid()` 在同一进程内并发线程唯一。`self_count` 和 `self_table[]` 的写入先于 `self_count++`（C11 acquire-release 语义），读者先读 `self_count` 再遍历——可能读到表正在写入中的槽位（tid=0），但不影响正确性（不会误匹配有效 tid）
- **固定大缓冲区分配**：`MUSL_PTHREAD_MAX_SIZE = 8192` 字节。前向声明只有 48 字节而真实 struct 有 200+ 字节，musl 代码会访问深处字段。过度分配确保所有字段访问均落在合法内存范围内
- **前向声明补齐 `proc_tid`**：musl 真实布局中 `errno_val` 在偏移 44（前面有 `proc_tid` 在偏移 40）
- **TSG 数组单独分配**：`calloc(128, sizeof(void*))` 分配 1024 字节。musl 的 `pthread_getspecific`/`pthread_setspecific` 不解引用 NULL 检查 `tsd` 指针——必须指向有效数组。不可指向 `__pthread_tsd_main`（主线程静态数组），否则多线程共享 TSD → 数据损坏
- **locale 指向 musl libc 全局 locale**：`global_locale` 是 musl 内部 BSS 符号（`struct __locale_struct`）。`CURRENT_UTF8` 宏展开为 `__pthread_self()->locale->cat[LC_CTYPE]`——NULL locale 导致 `printf("%m")`/`strerror()`/wide-char 函数 crash
- **`prev`/`next` 自环**：`__pthread_exit` 退出时 unlink 线程（`self->next->prev = self->prev`），NULL 指针会 SIGSEGV
- **`robust_list.head` 指向自身**：标准 musl 初始化模式
- **`detach_state = DT_JOINABLE`**：pthread_detach/pthread_join 依赖此值。不等于 0 时 `pthread_exit` 才走清理逻辑
- **`canceldisable = 1`**：禁止取消的安全默认（bionic 线程没有 musl cancel handler 的完整上下文）
- **calloc 走 bionic**：`malloc_bridge.c` 已将 `calloc` → `__libc_calloc` → bionic `calloc`。bionic 线程上 bionic calloc 读写 errno 走 `__get_thread()` 安全
- **scudo TLS slot 冲突**：musl 自建线程上调用 bionic `malloc`（经 `malloc_bridge.c`）时，scudo 可能读写 `TLS_SLOT_SANITIZER`（`tpidr_el0[6]`）。在 musl 线程上此偏移是 musl TLS 内部数据——潜在内存损坏。初期 musl 线程被禁止自建（见风险表），因此不触发；`malloc_bridge.c` 注释中标明此限制

### 4. 新增 `malloc_bridge.c` — 堆统一

musl 通过 `glue.h` 将 `malloc` → `__libc_malloc_impl`。不编译 musl 的 `mallocng/malloc.c` 和 `mallocng/free.c`，改为桥接 bionic：

```c
#include <stdlib.h>

void *__libc_malloc_impl(size_t n)       { return malloc(n); }
void  __libc_free(void *p)               { free(p); }
void *__libc_realloc(void *p, size_t n)  { return realloc(p, n); }
void *__libc_calloc(size_t m, size_t n)  { return calloc(m, n); }

extern void *malloc(size_t) __attribute__((alias("__libc_malloc_impl")));
extern void  free(void *)   __attribute__((alias("__libc_free")));
```

效果：musl 内部、HAP .so、Android 宿主三方所有内存操作统一走 bionic jemalloc，单进程单堆。

### 5. 新增 `libmusl_bridge.map` — 符号可见性控制

```
{
    global:
        malloc; free; realloc; calloc;
        pthread_*;
        printf; fprintf; sprintf; snprintf; vfprintf; vsnprintf;
        fopen; fclose; fread; fwrite; fseek; ftell; fflush;
        putc; putchar; fputc; fputs; puts;
        getc; fgetc; getchar; fgets; gets;
        opendir; readdir; closedir;
        setjmp; longjmp;
        __errno_location;
        __libc_malloc_impl; __libc_free; __libc_realloc; __libc_calloc;
    local:
        *;
}
```

musl 内部符号（`__lockfile`、`__ofl`、`__stdio_read`、`__stdio_write` 等）设为 `local`，不污染全局符号表。

### 6. 各目录编译策略

| 目录 | 策略 | 原因 |
|------|------|------|
| `src/thread/` | 原样编译 | pthread 类型布局必须与 HAP .so 一致 |
| `src/stdio/` | 原样编译 | FILE* 是 opaque pointer |
| `src/internal/` | 原样编译 | libc 内部支撑 |
| `src/dirent/` | 原样编译 | DIR* 是 opaque pointer |
| `src/setjmp/` | 原样编译 | 纯寄存器，与 TLS 无关 |
| `src/malloc/mallocng/` | 编译，排除 `malloc.c`、`free.c` | 其余文件内部调 `__libc_malloc_impl`，由 `malloc_bridge.c` 提供 |
| `src/signal/` | **不编译** | 信号全部走 bionic |
| `src/env/` | **不编译** | `__libc_start_main` 是进程入口，.so 不会调 |
| `src/string/`、`src/math/`、`src/ctype/`、`src/time/`、`src/stdlib/` 等 | **不编译** | ABI 兼容，运行时解析到 bionic |
| OHOS 专有目录 | **不编译** | 由独立 stub .so 提供 |

### 7. 构建系统

新建 `libmusl_bridge/` 目录，独立 Makefile（不依赖 OHOS GN），NDK r28c clang，target `aarch64-linux-android26`。

**关键：include path 顺序**。musl 源文件（thread/、stdio/、internal/ 等）include `<pthread.h>` 时期望 musl 的类型定义（`pthread_mutex_t` = union of int[10]，字段通过 `_m_type`/`_m_lock` 等宏访问）。如果用 NDK sysroot 的 `<pthread.h>`，会得到 bionic 的布局（`state`/`owner_tid`/`__reserved`），musl 源码中的字段访问宏会访问错误偏移。同时 bridge 文件（`pthread_bridge.c`、`malloc_bridge.c`）需要 NDK headers（不调任何 musl 内部函数）。因此两类文件用不同的 include flags：

```
# musl 源文件（需要 musl internal headers）
CFLAGS_musl  = -I$(MUSL)/include -I$(MUSL)/arch/aarch64 \
               -I$(MUSL)/src/internal -I$(MUSL)/arch/generic \
               -isystem $(NDK_SYSROOT)/usr/include

# bridge 文件（只用 NDK headers）
CFLAGS_bridge = -isystem $(NDK_SYSROOT)/usr/include
```

musl header 目录放在 `-isystem` 之前，确保 `<pthread.h>`、`<stdlib.h>` 等先在这些目录中查找。

编译源文件：
- `src/stdio/`、`src/thread/`、`src/internal/`、`src/dirent/`、`src/setjmp/` 所有 .c（用 `CFLAGS_musl`）
- `src/malloc/mallocng/` 除 `malloc.c`、`free.c` 外的所有 .c（用 `CFLAGS_musl`）
- arch-specific：`src/*/aarch64/*.[sS]`（clone.s、setjmp.s、longjmp.s 等）
- 新增：`pthread_bridge.c`、`malloc_bridge.c`（用 `CFLAGS_bridge`）

生成头文件：`bits/alltypes.h`（sed 从 `alltypes.h.in` 生成）、`bits/syscall.h`（复制自 `syscall.h.in`）、`version.h`。

链接：`-shared -nostdlib -Wl,--allow-shlib-undefined -Wl,--version-script=libmusl_bridge.map -Wl,-soname,libc.musl-aarch64.so.1`

### 8. Stub .so + HOA 运行时集成

- `libc.musl-aarch64.so.1` → 软链接到 `libmusl_bridge.so`，放到 `app/src/main/jniLibs/arm64-v8a/`
- OHOS 专有库 → 最小 stub，导出符号但空实现或转发
- `HapInstaller.kt` 第 127 行：`isSO` 从固定 `false` → 检测 HAP 内是否有 `libs/` 目录
- `HoaApplication.kt`：`System.loadLibrary("musl_bridge")` 预加载桥接库

## OHOS 源码修改汇总

**改 2 个文件**：

| 文件 | 修改 |
|------|------|
| `src/internal/pthread_impl.h:174` | `__pthread_self()` 宏改为 `__musl_bridge_self()` 函数调用 |
| `src/thread/pthread_create.c:203,220` | `start()` / `start_c11()` 开头加 `__musl_bridge_register(self)` |

其余全部通过新增文件实现（`pthread_bridge.c`、`malloc_bridge.c`、`libmusl_bridge.map`、`Makefile`）。

## 性能分析

`__pthread_self()` 调用频率（基于 musl 源码审计，详见 `agents/native-so-compat-analysis.md` §2.5）：

| 调用点 | 频率 | 每条调几次 |
|--------|:----:|:---------:|
| `pthread_mutex_lock` (NORMAL, uncontended) | — | **0**（纯 CAS，不调 `__pthread_self()`） |
| `pthread_mutex_lock` (recursive/errorcheck) | 每次 | 1 |
| `pthread_cond_timedwait` | 每次 | **3+** |
| `pthread_rwlock_*` (musl) | — | **0**（纯 atomic） |
| `__lockfile` (stdio `printf`/`fread`/`fwrite`) | **每次** | 1 |
| `__errno_location` | 每次读/写 errno | 1 |
| `pthread_setcancelstate` | 每次 | 1 |

关键结论：
- NORMAL mutex（`PTHREAD_MUTEX_DEFAULT`，默认类型）的 fast path 完全不走 `__pthread_self()`，当前方案对此类 mutex **零开销**
- `__lockfile`（stdio）是真正热路径：每次 `printf`/`fprintf`/`fread`/`fwrite` 都会调 `__pthread_self()` → `gettid()` + 查表。但 stdio 操作自身有 write/read syscall 成本远大于此
- `pthread_cond_timedwait` 每调用 3 次以上 `__pthread_self()`，在条件变量频繁等待/唤醒场景有累积影响

| 操作 | 原来（纯 musl） | 本方案 | 影响 |
|------|----------------|--------|------|
| `__pthread_self()` | 1 条 `mrs tpidr_el0` | `gettid()` syscall + 线性扫描 | syscall ~200 cycles，扫描 < 10 条目 |
| `pthread_mutex_lock` 整体 (NORMAL) | atomics ± futex | 同上（不变，不调 `__pthread_self()`） | **零影响** |
| `pthread_mutex_lock` 整体 (非 NORMAL) | atomics + `__pthread_self()` | 额外多一次 `gettid()` | mutex 主体是 atomic/futex，占比小 |
| `printf` 整体 | write syscall + `__lockfile` | `__lockfile` 中 `gettid()` 替代 `mrs` | write syscall 本身 > 10,000 cycles，占比 < 2% |

典型场景（< 10 个线程调用 HAP .so）：`gettid()` + 线性扫描 < 500 cycles，而 futex 在无竞争时 ~200 cycles、有竞争时 > 10,000 cycles。`__pthread_self()` 的开销在绝大多数操作中占比 < 20%。

**优化空间**（后续）：引入每线程缓存 slot（`__thread` 不可用，但可以用表内的 per-thread 指针位），或 lock-free read path（表只增不减，用 RCU 或 seqlock，当前方案已采用"无锁查表+写锁"，热路径上 `__pthread_self()` 的查表无需加锁）。

## 风险与局限

| 项 | 分析 |
|------|------|
| **robust mutex** | 依赖 `struct pthread.robust_list` 与内核通信。bionic 线程上 musl 的 robust_list 已初始化（自指），但 bionic 线程退出不经过 musl 的 `__pthread_exit`，robust 列表不会被内核处理。**暂不支持** |
| **HAP 自建线程** | musl `pthread_create` 创建的新线程 `tpidr_el0` 指向 musl TCB。`start()` 注册后 `__pthread_self()` 工作正常。但 ART GC 扫描该线程时读 `tpidr_el0` 期望 bionic TCB；scudo 分配器会向 `tpidr_el0[6]` 写入 TLS 状态（musl TLS 区域的内存损坏）。**初期禁止 HAP 自建线程**，后续可在 `pthread_create` 中同时分配 bionic-compatible TCB 并预留 scudo slot |
| **bionic 线程退出后 tid 回收** | bionic 线程退出不经过 musl `__pthread_exit()`，bridge struct 的 `tid` 保持原值。内核可能将 tid 分配给新 bionic 线程 → 新线程 `gettid()` 命中旧条目 → 返回旧 `struct pthread`。主线程和线程池线程不退出，临时线程数量极少（< 5），TSG 数组为全 NULL 与新线程状态相同——功能正确。旧线程 TSD 值泄露概率极低。后续可用 `pthread_key_create` 析构函数检测退出 |
| **SIGCANCEL 信号** | musl 用内核信号 33 做线程取消。bionic 保留信号 32-41 但信号 33 保持不阻塞且无默认 handler——musl 可以安装 `cancel_handler`。如 HAP 代码从不调 `pthread_cancel()`（常见），handler 不会被安装，完全无冲突 |
| **表溢出** | 128 槽位，超出时新线程分配 `struct pthread` 但不入表 → 每次 `__pthread_self()` 都 miss → 每次重新分配。仅在极端多线程场景触发。实际 HAP 调用线程 < 5，128 远大于需求 |
