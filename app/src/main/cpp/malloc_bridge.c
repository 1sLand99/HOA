#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <android/log.h>

#define BRIDGE_LOG(fmt, ...) \
    __android_log_print(ANDROID_LOG_ERROR, "malloc_bridge", fmt, ##__VA_ARGS__)

// Bridge musl's internal allocator entry points to bionic jemalloc/scudo.
//
// On musl-created threads, tpidr_el0 points to the musl TLS area.  Bionic's
// scudo writes allocator state to tpidr_el0[6] (TLS_SLOT_SANITIZER), which
// would overwrite musl's tls_slots[] region.  We swap to a per-thread bionic
// TLS block so each musl thread gets its own scudo per-thread cache.
//
// The main thread's bionic_tp is captured at init and used only as a one-shot
// bootstrap when allocating a new TLS block for a musl thread.
//
// malloc / free / realloc / calloc are also exported so that musl-compiled
// .so files (e.g. libentry.so inside a HAP) resolve those symbols to libb.so
// instead of falling through to bionic's libc.so.  Without this, worker
// threads would call bionic's scudo directly without the TLS swap and crash
// on tagged-pointer mismatches.

#define BIONIC_TLS_SIZE 64
#define MAX_TLS_ENTRIES 128

static uintptr_t main_bionic_tp;

// Pointers to bionic's real allocator functions, resolved via dlsym(RTLD_NEXT)
// at init to avoid PLT recursion (our own malloc/free wrappers are exported
// from this library, so a plain PLT call would bind to ourselves).
static void *(*real_malloc)(size_t) = NULL;
static void (*real_free)(void *) = NULL;
static void *(*real_realloc)(void *, size_t) = NULL;
static void *(*real_calloc)(size_t, size_t) = NULL;

static struct {
    pid_t tid;
    uintptr_t tp;
} tls_table[MAX_TLS_ENTRIES];
static volatile int tls_count = 0;

// Futex-based spinlock (same pattern as pthread_bridge.c)
static int tls_lock = 0;

static void tls_lock_table(void)
{
    while (__atomic_exchange_n(&tls_lock, 1, __ATOMIC_ACQUIRE))
        syscall(SYS_futex, &tls_lock, FUTEX_WAIT_PRIVATE, 1, 0, 0, 0);
}

static void tls_unlock_table(void)
{
    __atomic_store_n(&tls_lock, 0, __ATOMIC_RELEASE);
    syscall(SYS_futex, &tls_lock, FUTEX_WAKE_PRIVATE, 1, 0, 0, 0);
}

__attribute__((constructor))
static void __malloc_bridge_init(void)
{
    __asm__ ("mrs %0, tpidr_el0" : "=r"(main_bionic_tp));
    __android_log_print(ANDROID_LOG_ERROR, "malloc_bridge", "INIT: main_bionic_tp=0x%lx", (unsigned long)main_bionic_tp);

    // Resolve bionic's real allocator functions once.
    // RTLD_NEXT skips libb.so itself, so these always go to bionic.
    real_malloc  = (void *(*)(size_t))dlsym(RTLD_NEXT, "malloc");
    real_free    = (void (*)(void *))dlsym(RTLD_NEXT, "free");
    real_realloc = (void *(*)(void *, size_t))dlsym(RTLD_NEXT, "realloc");
    real_calloc  = (void *(*)(size_t, size_t))dlsym(RTLD_NEXT, "calloc");
}

// Tracks per-tid malloc call count for logging (first N calls only).
// Written under bionic TP; read under lock_file_key.
static struct {
    pid_t tid;
    int call_count;
} tid_call_log[32];
static volatile int tid_call_log_count = 0;

// Look up or allocate the per-thread bionic TLS block for the calling thread.
static uintptr_t get_bionic_tp(void)
{
    pid_t tid = (pid_t)syscall(SYS_gettid);

    // Fast path: lock-free scan (table is append-only, count is monotonic)
    int n = __atomic_load_n(&tls_count, __ATOMIC_ACQUIRE);
    for (int i = n - 1; i >= 0; i--) {
        if (tls_table[i].tid == tid)
            return tls_table[i].tp;
    }

    // Slow path: allocate and register under lock
    tls_lock_table();

    // Double-check: another thread may have registered while we waited
    n = __atomic_load_n(&tls_count, __ATOMIC_ACQUIRE);
    for (int i = n - 1; i >= 0; i--) {
        if (tls_table[i].tid == tid) {
            uintptr_t tp = tls_table[i].tp;
            BRIDGE_LOG("SLOW-HIT: tid=%d reusing TLS[%d] (other thread registered it)", tid, i);
            tls_unlock_table();
            return tp;
        }
    }

    // Allocate using main_bionic_tp as bootstrap.  We hold the lock so
    // no other thread can concurrently use main_bionic_tp with scudo.
    uintptr_t saved, block;
    __asm__ volatile ("mrs %0, tpidr_el0" : "=r"(saved));
    __asm__ volatile ("msr tpidr_el0, %0" :: "r"(main_bionic_tp));

    int total = tls_count;
    BRIDGE_LOG("NEW-TLS: tid=%d allocating TLS block (total=%d -> %d, saved_tp=0x%lx main_tp=0x%lx)",
               tid, total, total + 1, (unsigned long)saved, (unsigned long)main_bionic_tp);

    block = (uintptr_t)real_calloc(1, BIONIC_TLS_SIZE);
    __asm__ volatile ("msr tpidr_el0, %0" :: "r"(saved));

    if (block && tls_count < MAX_TLS_ENTRIES) {
        tls_table[tls_count].tid = tid;
        tls_table[tls_count].tp = block;
        __atomic_fetch_add(&tls_count, 1, __ATOMIC_RELEASE);
    }

    tls_unlock_table();
    return block ? block : main_bionic_tp;
}

static inline void enter_bionic_alloc(uintptr_t *saved)
{
    uintptr_t tp;
    __asm__ volatile ("mrs %0, tpidr_el0" : "=r"(tp));
    if (tp == main_bionic_tp) {
        *saved = 0;
        return;
    }
    *saved = tp;
    uintptr_t bionic_tp = get_bionic_tp();
    __asm__ volatile ("msr tpidr_el0, %0" :: "r"(bionic_tp));

    // Log first few malloc calls from each musl tid (safe: on bionic TP)
    pid_t tid = (pid_t)syscall(SYS_gettid);
    int cnt = __atomic_load_n(&tid_call_log_count, __ATOMIC_ACQUIRE);
    int found = 0;
    for (int i = 0; i < cnt && i < 32; i++) {
        if (tid_call_log[i].tid == tid) {
            found = 1;
            if (tid_call_log[i].call_count < 5) {
                tid_call_log[i].call_count++;
                BRIDGE_LOG("MALLOC: tid=%d call#%d (musl thread, tp=0x%lx bionic_tp=0x%lx)",
                           tid, tid_call_log[i].call_count, (unsigned long)tp, (unsigned long)bionic_tp);
            }
            return;
        }
    }
    if (!found && cnt < 32) {
        int slot = __atomic_fetch_add(&tid_call_log_count, 1, __ATOMIC_RELAXED);
        if (slot < 32) {
            tid_call_log[slot].tid = tid;
            tid_call_log[slot].call_count = 1;
            BRIDGE_LOG("MALLOC: tid=%d FIRST-CALL (musl thread, tp=0x%lx bionic_tp=0x%lx)",
                       tid, (unsigned long)tp, (unsigned long)bionic_tp);
        }
    }
}

static inline void leave_bionic_alloc(uintptr_t saved)
{
    if (saved)
        __asm__ volatile ("msr tpidr_el0, %0" :: "r"(saved));
}

// ── Bridge entry points called by musl's internal allocator ──────────────
// musl's lite_malloc.c / free.c call these for the actual allocation.
// They swap to bionic TLS, call bionic's allocator (via real_* pointers
// resolved at init), then restore musl TLS.

void *__libc_malloc_impl(size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *p = real_malloc(n);
    leave_bionic_alloc(s);
    return p;
}

void __libc_free(void *p)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    real_free(p);
    leave_bionic_alloc(s);
}

void *__libc_realloc(void *p, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *r = real_realloc(p, n);
    leave_bionic_alloc(s);
    return r;
}

void *__libc_calloc(size_t m, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *r = real_calloc(m, n);
    leave_bionic_alloc(s);
    return r;
}

// ── Exported malloc / free / realloc / calloc ────────────────────────────
// musl-compiled .so files (e.g. HAP native libs) resolve these against
// libb.so (which hot-patches libc.so).  Without these exports, calls would
// fall through to bionic's scudo directly, bypassing the TLS swap and
// causing tagged-pointer mismatches on worker threads.

void *malloc(size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *p = real_malloc(n);
    leave_bionic_alloc(s);
    return p;
}

void free(void *p)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    real_free(p);
    leave_bionic_alloc(s);
}

void *realloc(void *p, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *r = real_realloc(p, n);
    leave_bionic_alloc(s);
    return r;
}

void *calloc(size_t m, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *r = real_calloc(m, n);
    leave_bionic_alloc(s);
    return r;
}
