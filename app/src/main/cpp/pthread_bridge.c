#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <sys/syscall.h>
#include <linux/futex.h>

// ── Futex-based spinlock (pure kernel call, no libc TLS dependency) ──
static int table_lock = 0;

static void lock_table(void)
{
    while (__atomic_exchange_n(&table_lock, 1, __ATOMIC_ACQUIRE))
        syscall(SYS_futex, &table_lock, FUTEX_WAIT_PRIVATE, 1, 0, 0, 0);
}

static void unlock_table(void)
{
    __atomic_store_n(&table_lock, 0, __ATOMIC_RELEASE);
    syscall(SYS_futex, &table_lock, FUTEX_WAKE_PRIVATE, 1, 0, 0, 0);
}

// ── Global table: gettid() → struct pthread * ───────────────────────
#define MAX_SELF_ENTRIES 128

static struct {
    pid_t tid;
    struct pthread *self;
} self_table[MAX_SELF_ENTRIES];

static volatile int self_count = 0;

// ── struct pthread and offset constants ───────────────────────────────
//
// These 8 fields match the start of the real OHOS musl struct pthread
// on aarch64 LP64 (TLS_ABOVE_TP).  Verified against the source at
// third_party/musl/src/internal/pthread_impl.h.
//
// Fields beyond offset 48 are accessed via (char *) + hardcoded offset;
// see the offset map below.
struct pthread {
    struct pthread *self;     // offset  0
    struct pthread *prev;     // offset  8
    struct pthread *next;     // offset 16
    uintptr_t sysinfo;        // offset 24
    int tid;                  // offset 32
    int pid;                  // offset 36
    int proc_tid;             // offset 40
    int errno_val;            // offset 44
};

// Real struct is ~300+ bytes.  Allocate 8192 to cover any platform.
#define MUSL_PTHREAD_MAX_SIZE 8192

// Offset constants for fields beyond the forward declaration.
// These are verified against the OHOS musl fork struct layout
// (aarch64 LP64, TLS_ABOVE_TP, CXA_THREAD_USE_TLS not defined).
//
// If struct layout changes, update these.  All accesses are within
// MUSL_PTHREAD_MAX_SIZE so miscounts won't corrupt adjacent memory.
#define OFF_DETACH_STATE   52   // volatile int
#define OFF_TSD           120   // void **
#define OFF_ROBUST_HEAD   128   // volatile void *volatile (head of robust_list)
#define OFF_LOCALE        160   // locale_t (struct __locale_struct *)
#define OFF_KILLLOCK      168   // volatile int[1]
#define OFF_DLERROR_BUF   176   // char *
#define OFF_CANCEL        208   // volatile int
#define OFF_CANCELDISABLE 212   // volatile unsigned char
#define OFF_CANCELASYNC   213   // volatile unsigned char

// ── Validate a struct pthread before returning it ────────────────────
static struct pthread *validate_self(struct pthread *self, pid_t tid, int idx)
{
    if (!self) return self;

    // Verify self->self == self (first field should point to itself)
    if (self->self != self) {
        return NULL;
    }

    // Verify tsd is non-NULL
    void *tsd = *(void **)((char *)self + OFF_TSD);
    if (!tsd) {
        return NULL;
    }
    return self;
}
static void *global_locale_ptr;  // set by constructor from musl's __libc

// ── libb.so TLS module registration for __thread support ──────────────
//
// aarch64 OHOS NDK 默认使用 TLSDESC 模型，不调用 __tls_get_addr。
// bionic 线程的 __thread 由 linker 的 TLSDESC resolver 开箱支持；
// musl 线程的 __thread 需要干预 TLSDESC entry（非 GOT 符号 patch）。
//
// 以下 TLS 模块注册基础设施预留给后续 musl 线程 TLS 支持：
// musl 的 __copy_tls() 迭代 libc.tls_head 为新线程拷贝 TLS 镜像，
// 需要将 HAP .so 的 PT_TLS 注册到此链中。
//
// Layout mirrors musl's src/internal/libc.h:struct tls_module.
struct tls_module {
    struct tls_module *next;
    void *image;
    size_t len, size, align, offset;
};

static struct tls_module libb_tls_mod;

// musl thread entry calls this after tpidr_el0 is set
void __musl_bridge_register(struct pthread *self)
{
    // Validate the tcb looks right
    if (self->self != self) {
        return;
    }

    lock_table();
    if (self_count < MAX_SELF_ENTRIES) {
        self_table[self_count].tid  = self->tid;
        self_table[self_count].self = self;
        __atomic_fetch_add(&self_count, 1, __ATOMIC_RELEASE);
    }
    unlock_table();
}

// Unified __pthread_self(): raw gettid syscall + table lookup.
// On first call from a bionic thread, allocates a musl struct pthread.
//
// Uses syscall(SYS_gettid) instead of bionic's gettid() because bionic's
// gettid() reads the cached tid from bionic's pthread_internal_t via
// TPIDR_EL0.  Since we strip CLONE_SETTLS to keep bionic's __start_thread
// working, child threads inherit the parent's TPIDR_EL0 and would get the
// parent's tid — causing the table lookup to return the wrong pthread.
struct pthread *__musl_bridge_self(void)
{
    pid_t tid = (pid_t)syscall(SYS_gettid);

    // Lock-free read: table is append-only, self_count is monotonic.
    // Scan backwards so the most recent entry for a recycled tid wins.
    int n = __atomic_load_n(&self_count, __ATOMIC_ACQUIRE);
    for (int i = n - 1; i >= 0; i--) {
        if (self_table[i].tid == tid) {
            return validate_self(self_table[i].self, tid, i);
        }
    }

    // First call from this bionic thread — allocate a musl struct pthread.
    struct pthread *new_self = calloc(1, MUSL_PTHREAD_MAX_SIZE);

    // ── offset 0–44: forward-declared fields ──
    new_self->self = new_self;
    new_self->prev = new_self;      // self-loop (unlink in __pthread_exit)
    new_self->next = new_self;
    new_self->tid  = tid;
    new_self->pid  = getpid();
    new_self->errno_val = 0;

    // ── offset 52: detach_state = DT_JOINABLE (2) ──
    *(volatile int *)((char *)new_self + OFF_DETACH_STATE) = 2;

    // ── offset 120: tsd → void*[128] ──
    void **tsd = calloc(128, sizeof(void *));
    *(void ***)((char *)new_self + OFF_TSD) = tsd;

    // ── offset 128: robust_list.head → self ──
    *(void **)((char *)new_self + OFF_ROBUST_HEAD) =
        (char *)new_self + OFF_ROBUST_HEAD;

    // ── offset 160: locale → musl global_locale ──
    // global_locale_ptr is set by constructor from &__libc.global_locale.
    *(void **)((char *)new_self + OFF_LOCALE) = global_locale_ptr;

    // ── offset 168: killlock[1] = 0 ──
    ((volatile int *)((char *)new_self + OFF_KILLLOCK))[0] = 0;

    // ── offset 176: dlerror_buf = NULL ──
    *(char **)((char *)new_self + OFF_DLERROR_BUF) = NULL;

    // ── offset 208-213: cancel, canceldisable, cancelasync ──
    *(volatile int *)((char *)new_self + OFF_CANCEL) = 0;
    ((volatile unsigned char *)(new_self))[OFF_CANCELDISABLE] = 1;  // PTHREAD_CANCEL_DISABLE
    ((volatile unsigned char *)(new_self))[OFF_CANCELASYNC] = 0;

    lock_table();
    if (self_count < MAX_SELF_ENTRIES) {
        self_table[self_count].tid  = tid;
        self_table[self_count].self = new_self;
        __atomic_fetch_add(&self_count, 1, __ATOMIC_RELEASE);
    }
    unlock_table();

    return validate_self(new_self, tid, -1);
}

// ── errno delegation to bionic ──────────────────────────────────────
//
// musl's opendir (etc.) calls open() which resolves to bionic's open().
// Bionic sets errno via its own __errno_location() TLS slot, while musl
// code reads errno via the bridge pthread errno_val field.  These are
// different memory locations → errno mismatch → musl code sees errno=0
// after a bionic failure.
//
// Eagerly resolved in __musl_bridge_init (main thread, before any musl
// threads exist).  dlsym(RTLD_NEXT) inside a musl thread segfaults because
// the dynamic linker tries to access bionic per-thread state that doesn't
// exist for musl-created threads.
static int *(*bionic_errno_loc)(void);

// Recursion guard: dlsym() on some custom ROMs may internally call
// __errno_location, which would re-enter this function before the
// function pointer is stored → infinite recursion → stack overflow.
static volatile int errno_loc_resolving = 0;

int *__errno_location(void)
{
    if (!bionic_errno_loc) {
        // Fallback: lazy init (only reached if constructor was skipped)
        if (__atomic_load_n(&errno_loc_resolving, __ATOMIC_ACQUIRE)) {
            return &__musl_bridge_self()->errno_val;
        }
        __atomic_store_n(&errno_loc_resolving, 1, __ATOMIC_RELEASE);
        bionic_errno_loc = (int *(*)(void))dlsym(RTLD_NEXT, "__errno_location");
        __atomic_store_n(&errno_loc_resolving, 0, __ATOMIC_RELEASE);
        if (!bionic_errno_loc) {
            return &__musl_bridge_self()->errno_val;
        }
    }
    return bionic_errno_loc();
}

int *___errno_location(void) __attribute__((alias("__errno_location")));

extern char __libc;  // struct __libc, defined in musl src/internal/libc.c

// ── Helper: find libb.so's PT_TLS via dl_iterate_phdr ─────────────────
#define MUSL_FULL_PTHREAD_SIZE 392   // sizeof(struct pthread) on aarch64 LP64
#define TLS_GAP_ABOVE_TP 16          // aarch64 TLS ABI reserved gap above TP

static int find_libb_tls(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size;
    (void)data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, "libb.so")) return 0;

    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type != PT_TLS) continue;

        const Elf64_Phdr *phdr = &info->dlpi_phdr[i];
        libb_tls_mod.image = (void *)(info->dlpi_addr + phdr->p_vaddr);
        libb_tls_mod.len   = phdr->p_filesz;
        libb_tls_mod.size  = phdr->p_memsz;
        libb_tls_mod.align = phdr->p_align;

        // Compute offset within the TLS block (same formula as __init_tls.c)
        libb_tls_mod.offset = TLS_GAP_ABOVE_TP;
        libb_tls_mod.offset +=
            (-TLS_GAP_ABOVE_TP + (uintptr_t)libb_tls_mod.image)
            & (libb_tls_mod.align - 1);

        // Register with musl's TLS module chain so __copy_tls copies
        // libb.so's TLS image for every new musl-created thread.
        libb_tls_mod.next = NULL;
        *(struct tls_module **)(&__libc + 16) = &libb_tls_mod;   // tls_head
        *(size_t *)(&__libc + 40) = 1;                            // tls_cnt

        // tls_align was set to 1 (conservative).  Use the actual
        // alignment from libb.so's TLS segment if larger.
        if (phdr->p_align > *(size_t *)(&__libc + 32))
            *(size_t *)(&__libc + 32) = phdr->p_align;

        return 1;
    }
    return 0;
}

// Constructor: enable musl internal locking and capture global_locale.
//
// Without need_locks=1, musl's __lock() returns immediately
// (if (!libc.need_locks) return), making all musl internal locks
// no-ops — data races in multi-threaded use.
//
// struct __libc layout from musl src/internal/libc.h (aarch64 LP64):
//   offset  0: can_do_threads (char)
//   offset  1: threaded (char)
//   offset  2: secure (char)
//   offset  3: need_locks (volatile signed char)
//   offset  4: threads_minus_1 (int)
//   offset  8: auxv (size_t *)
//   offset 16: tls_head (struct tls_module *)
//   offset 24: tls_size (size_t)
//   offset 32: tls_align (size_t)
//   offset 40: tls_cnt (size_t)
//   offset 48: page_size (size_t)  ← used by ROUND() in pthread_create
//   offset 56: global_locale (struct __locale_struct)
//
// __libc is a BSS symbol defined in musl's src/internal/libc.c.
extern char __libc;  // struct __libc (in BSS, zero-init by linker)

__attribute__((constructor))
static void __musl_bridge_init(void)
{
    // Enable thread creation.
    ((volatile signed char *)&__libc)[0] = 1;  // can_do_threads
    ((volatile signed char *)&__libc)[3] = 1;  // need_locks

    // Set tls_size to 4096. With tls_size=0 (BSS), __copy_tls places
    // struct pthread at the same address as the TSD array, causing
    // self->self (offset 0) to overlap with tsd[0] → self corruption.
    *(size_t *)(&__libc + 24) = 4096;

    // Set page_size to 4096. Without this, ROUND() returns 0 and mmap
    // fails with EINVAL inside pthread_create.
    *(size_t *)(&__libc + 48) = 4096;

    // Set tls_align to 1. With tls_align=0 (BSS), __copy_tls pointer
    // arithmetic overflows and dereferences garbage → SIGSEGV.
    *(size_t *)(&__libc + 32) = 1;

    // Register libb.so's own TLS module so __copy_tls copies it for
    // new musl-created threads.  Without this, __tls_get_addr has no
    // DTV entries → crash on first __thread access.
    dl_iterate_phdr(find_libb_tls, NULL);

    // Capture musl's global_locale address (offset 56).
    global_locale_ptr = (void *)(&__libc + 56);

    // Resolve bionic's __errno_location eagerly in the main thread.
    // Lazy resolution (first call to __errno_location) can happen in a
    // musl-created thread where dlsym(RTLD_NEXT) chokes on TLS setup →
    // SIGSEGV.  Doing it here avoids the race entirely.
    bionic_errno_loc = (int *(*)(void))dlsym(RTLD_NEXT, "__errno_location");
}
