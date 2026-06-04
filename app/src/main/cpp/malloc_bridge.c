#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <linux/futex.h>

// Bridge musl's internal allocator entry points to bionic jemalloc/scudo.
//
// On musl-created threads, tpidr_el0 points to the musl TLS area.  Bionic's
// scudo writes allocator state to tpidr_el0[6] (TLS_SLOT_SANITIZER), which
// would overwrite musl's tls_slots[] region.  We swap to a per-thread bionic
// TLS block so each musl thread gets its own scudo per-thread cache.
//
// The main thread's bionic_tp is captured at init and used only as a one-shot
// bootstrap when allocating a new TLS block for a musl thread.

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
}

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
            tls_unlock_table();
            return tp;
        }
    }

    // Allocate using main_bionic_tp as bootstrap.  We hold the lock so
    // no other thread can concurrently use main_bionic_tp with scudo.
    uintptr_t saved, block;
    __asm__ volatile ("mrs %0, tpidr_el0" : "=r"(saved));
    __asm__ volatile ("msr tpidr_el0, %0" :: "r"(main_bionic_tp));
    block = (uintptr_t)calloc(1, BIONIC_TLS_SIZE);
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
    __asm__ volatile ("msr tpidr_el0, %0" :: "r"(get_bionic_tp()));
}

static inline void leave_bionic_alloc(uintptr_t saved)
{
    if (saved)
        __asm__ volatile ("msr tpidr_el0, %0" :: "r"(saved));
}

void *__libc_malloc_impl(size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *p = malloc(n);
    leave_bionic_alloc(s);
    return p;
}

void __libc_free(void *p)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    free(p);
    leave_bionic_alloc(s);
}

void *__libc_realloc(void *p, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *r = realloc(p, n);
    leave_bionic_alloc(s);
    return r;
}

void *__libc_calloc(size_t m, size_t n)
{
    uintptr_t s;
    enter_bionic_alloc(&s);
    void *r = calloc(m, n);
    leave_bionic_alloc(s);
    return r;
}
