#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
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

static int self_count = 0;

// ── Forward declaration of musl struct pthread ──────────────────────
//
// These 8 fields match the start of the real OHOS musl struct pthread
// on aarch64 LP64 (TLS_ABOVE_TP).  Verified against the source at
// third_party/musl/src/internal/pthread_impl.h.
//
// Fields beyond offset 48 are accessed via (char *) + hardcoded offset;
// see the initialization block below for the offset map.
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
#define OFF_CANCEL        208   // volatile int (was 216 in analysis; OHOS fork shifts)
#define OFF_CANCELDISABLE 212   // volatile unsigned char
#define OFF_CANCELASYNC   213   // volatile unsigned char

static void *global_locale_ptr;  // set by constructor from musl's __libc

// musl thread entry calls this after tpidr_el0 is set
void __musl_bridge_register(struct pthread *self)
{
    lock_table();
    if (self_count < MAX_SELF_ENTRIES) {
        self_table[self_count].tid  = self->tid;
        self_table[self_count].self = self;
        self_count++;
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
    for (int i = 0; i < self_count; i++) {
        if (self_table[i].tid == tid)
            return self_table[i].self;
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
        self_count++;
    }
    unlock_table();

    return new_self;
}

// ── errno delegation to bionic ──────────────────────────────────────
//
// musl's opendir (etc.) calls open() which resolves to bionic's open().
// Bionic sets errno via its own __errno_location() TLS slot, while musl
// code reads errno via the bridge pthread errno_val field.  These are
// different memory locations → errno mismatch → musl code sees errno=0
// after a bionic failure.
//
// Fix: resolve bionic's __errno_location at init time and delegate.

static int *(*bionic_errno_loc)(void);

int *__errno_location(void)
{
    if (!bionic_errno_loc) {
        bionic_errno_loc = (int *(*)(void))dlsym(RTLD_NEXT, "__errno_location");
        if (!bionic_errno_loc) {
            return &__musl_bridge_self()->errno_val;
        }
    }
    return bionic_errno_loc();
}

int *___errno_location(void) __attribute__((alias("__errno_location")));

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

    // Set page_size to 4096. Without this, ROUND() returns 0 and mmap
    // fails with EINVAL inside pthread_create.
    *(size_t *)(&__libc + 48) = 4096;

    // Set tls_align to 1. With tls_align=0 (BSS), __copy_tls pointer
    // arithmetic overflows and dereferences garbage → SIGSEGV.
    *(size_t *)(&__libc + 32) = 1;

    // Capture musl's global_locale address (offset 56).
    global_locale_ptr = (void *)(&__libc + 56);
}
