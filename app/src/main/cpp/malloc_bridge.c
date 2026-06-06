#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <unistd.h>
#include <dlfcn.h>
#include <string.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <android/log.h>

#define BRIDGE_LOG(fmt, ...) \
    __android_log_print(ANDROID_LOG_ERROR, "malloc_bridge", fmt, ##__VA_ARGS__)

// ── Heap canary debugging ─────────────────────────────────────────────────
// Each allocation gets a header (size + front canary) and a tail canary.
// On free(), both canaries are checked. If corrupted, the overflow is caught
// at the moment of free — not later when corrupted metadata causes a crash.
//
// Forward declarations of real_* pointers (defined below, resolved at init).
static void *(*real_malloc)(size_t) = NULL;
static void (*real_free)(void *) = NULL;
static void *(*real_realloc)(void *, size_t) = NULL;
static void *(*real_calloc)(size_t, size_t) = NULL;

#ifdef MALLOC_CANARY_DEBUG

#define CANARY_FRONT_MAGIC 0xCAFEF00DDEADBEEFULL
#define CANARY_TAIL_MAGIC  0x8BADF00D5CAFFEEDULL

typedef struct {
    size_t  size;            // user-requested size
    uint64_t front_canary;   // detects underflow
} canary_header;

typedef struct {
    uint64_t tail_canary;    // detects overflow
} canary_footer;

static void *malloc_canary(size_t n)
{
    size_t total = sizeof(canary_header) + n + sizeof(canary_footer);
    canary_header *hdr = (canary_header *)real_malloc(total);
    if (!hdr) return NULL;
    hdr->size = n;
    hdr->front_canary = CANARY_FRONT_MAGIC;
    char *user_ptr = (char *)(hdr + 1);
    canary_footer *ftr = (canary_footer *)(user_ptr + n);
    ftr->tail_canary = CANARY_TAIL_MAGIC;
    return user_ptr;
}

static void free_canary(void *p)
{
    if (!p) return;
    canary_header *hdr = ((canary_header *)p) - 1;

    if (hdr->front_canary != CANARY_FRONT_MAGIC) {
        BRIDGE_LOG("*** HEAP-CORRUPT *** free(%p): FRONT canary=0x%016llx expected=0x%016llx (underflow or double-free)",
                   p, (unsigned long long)hdr->front_canary, (unsigned long long)CANARY_FRONT_MAGIC);
        __builtin_trap();
    }

    canary_footer *ftr = (canary_footer *)((char *)p + hdr->size);
    if (ftr->tail_canary != CANARY_TAIL_MAGIC) {
        BRIDGE_LOG("*** HEAP-CORRUPT *** free(%p) size=%zu: TAIL canary=0x%016llx expected=0x%016llx (overflow!)",
                   p, hdr->size, (unsigned long long)ftr->tail_canary, (unsigned long long)CANARY_TAIL_MAGIC);
        // dump tail bytes
        BRIDGE_LOG("*** HEAP-CORRUPT *** tail bytes: %02x %02x %02x %02x %02x %02x %02x %02x "
                   " %02x %02x %02x %02x %02x %02x %02x %02x",
                   ((unsigned char *)ftr)[0], ((unsigned char *)ftr)[1], ((unsigned char *)ftr)[2],
                   ((unsigned char *)ftr)[3], ((unsigned char *)ftr)[4], ((unsigned char *)ftr)[5],
                   ((unsigned char *)ftr)[6], ((unsigned char *)ftr)[7], ((unsigned char *)ftr)[8],
                   ((unsigned char *)ftr)[9], ((unsigned char *)ftr)[10], ((unsigned char *)ftr)[11],
                   ((unsigned char *)ftr)[12], ((unsigned char *)ftr)[13], ((unsigned char *)ftr)[14],
                   ((unsigned char *)ftr)[15]);
        __builtin_trap();
    }

    // wipe canaries to catch double-free
    hdr->front_canary = 0;
    ftr->tail_canary = 0;
    real_free(hdr);
}

static void *realloc_canary(void *p, size_t n)
{
    if (!p) return malloc_canary(n);

    canary_header *hdr = ((canary_header *)p) - 1;
    if (hdr->front_canary != CANARY_FRONT_MAGIC) {
        BRIDGE_LOG("*** HEAP-CORRUPT *** realloc(%p): FRONT canary=0x%016llx", p, (unsigned long long)hdr->front_canary);
        __builtin_trap();
    }

    canary_footer *ftr = (canary_footer *)((char *)p + hdr->size);
    if (ftr->tail_canary != CANARY_TAIL_MAGIC) {
        BRIDGE_LOG("*** HEAP-CORRUPT *** realloc(%p) size=%zu: TAIL canary corrupted", p, hdr->size);
        __builtin_trap();
    }

    // wipe old
    hdr->front_canary = 0;
    ftr->tail_canary = 0;

    size_t total = sizeof(canary_header) + n + sizeof(canary_footer);
    canary_header *new_hdr = (canary_header *)real_realloc(hdr, total);
    if (!new_hdr) return NULL;
    new_hdr->size = n;
    new_hdr->front_canary = CANARY_FRONT_MAGIC;
    char *user_ptr = (char *)(new_hdr + 1);
    canary_footer *new_ftr = (canary_footer *)(user_ptr + n);
    new_ftr->tail_canary = CANARY_TAIL_MAGIC;
    return user_ptr;
}

static void *calloc_canary(size_t m, size_t n)
{
    size_t total = m * n;
    void *p = malloc_canary(total);
    if (p) memset(p, 0, total);
    return p;
}

#endif // MALLOC_CANARY_DEBUG

#define BIONIC_TLS_SIZE 64
#define MAX_TLS_ENTRIES 128

static uintptr_t main_bionic_tp;

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
#ifdef MALLOC_CANARY_DEBUG
    void *p = malloc_canary(n);
#else
    void *p = real_malloc(n);
#endif
    leave_bionic_alloc(s);
    return p;
}

void __libc_free(void *p)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
#ifdef MALLOC_CANARY_DEBUG
    free_canary(p);
#else
    real_free(p);
#endif
    leave_bionic_alloc(s);
}

void *__libc_realloc(void *p, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
#ifdef MALLOC_CANARY_DEBUG
    void *r = realloc_canary(p, n);
#else
    void *r = real_realloc(p, n);
#endif
    leave_bionic_alloc(s);
    return r;
}

void *__libc_calloc(size_t m, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
#ifdef MALLOC_CANARY_DEBUG
    void *r = calloc_canary(m, n);
#else
    void *r = real_calloc(m, n);
#endif
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
#ifdef MALLOC_CANARY_DEBUG
    void *p = malloc_canary(n);
#else
    void *p = real_malloc(n);
#endif
    leave_bionic_alloc(s);
    return p;
}

void free(void *p)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
#ifdef MALLOC_CANARY_DEBUG
    free_canary(p);
#else
    real_free(p);
#endif
    leave_bionic_alloc(s);
}

void *realloc(void *p, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
#ifdef MALLOC_CANARY_DEBUG
    void *r = realloc_canary(p, n);
#else
    void *r = real_realloc(p, n);
#endif
    leave_bionic_alloc(s);
    return r;
}

void *calloc(size_t m, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
#ifdef MALLOC_CANARY_DEBUG
    void *r = calloc_canary(m, n);
#else
    void *r = real_calloc(m, n);
#endif
    leave_bionic_alloc(s);
    return r;
}
