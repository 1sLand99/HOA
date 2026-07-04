#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <iconv.h>
#include <unistd.h>
#include <dlfcn.h>
#include <execinfo.h>
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

// Forward declare real_malloc_usable_size (resolved in __malloc_bridge_init)
static size_t (*real_malloc_usable_size)(const void *) = NULL;

#ifdef MALLOC_CANARY_MINIMAL
// ── Footer-only canary design ──────────────────────────────────────────
//
// WHY footer-only (no prefix header)?
//   The old prefix-header design put {size, canary} BEFORE the user data and
//   returned p+16 to the caller.  This offset the pointer from what scudo
//   originally returned.  Problem: not all free() calls go through libb.so.
//   Code inside libc++_shared.so (e.g. operator delete, __cxa_free_exception)
//   may call bionic's free() directly.  Bionic receives our offset pointer,
//   can't find scudo metadata at the expected location → corruption.
//
//   Footer-only solves this: we return the RAW scudo pointer (zero offset).
//   Both libb.so's free and bionic's free see the identical pointer value.
//   On free, we locate the footer via malloc_usable_size(p).
//
// HOW the footer is located:
//   Allocation:  real_malloc(n + 16) → scudo returns p, usable bytes ≥ n+16.
//                Footer placed at p + usable - 16, invisible to the caller.
//   Free:         usable = malloc_usable_size(p) — same value as at alloc time.
//                Footer at p + usable - 16.
//
// Layout:
//   [user data  ...  n bytes  ...] [slack/padding] [size:8] [tail_canary:8]
//   ^                                                ^          ^
//   p (returned to caller)                            usable-16  usable-8

// CANARY_SIZE_MARK is OR'd into the footer's size field.  On free, we check
// (size & MASK) == MARK to confirm this is a canary-tracked allocation.
// Pointers from non-canary paths (bionic malloc, pre-libb allocations) will
// have random data at the footer location and fail this check — they are
// passed through to real_free without inspection.
//
// The 0xB0B0 prefix was chosen arbitrarily; any non-zero 16-bit value with
// low collision probability against real size values works.
#define CANARY_SIZE_MARK  0xB0B0000000000000ULL
#define CANARY_SIZE_MASK  0xFFFF000000000000ULL

typedef struct {
    size_t  size;          // user size | CANARY_SIZE_MARK
    uint64_t tail_canary;
} canary_footer;

static void *malloc_canary(size_t n)
{
    void *p = real_malloc(n + sizeof(canary_footer));
    if (!p) return NULL;
    size_t usable = real_malloc_usable_size(p);
    canary_footer *foot = (canary_footer *)((char *)p + usable - sizeof(canary_footer));
    foot->size = n | CANARY_SIZE_MARK;
    foot->tail_canary = CANARY_TAIL_MAGIC;
    return p;  // RAW scudo pointer — no offset!
}

// ── canary_get_raw: resolve raw pointer + footer from a user pointer ────
//
// Regular malloc/calloc/realloc return the raw scudo pointer directly, so
// raw == p and the footer is at p + usable(p) - 16.
//
// posix_memalign returns an ALIGNED pointer within a larger allocation.
// The raw scudo pointer is stored at ((void**)aligned)[-1], and the footer
// is at the end of the raw allocation.
//
// We try both locations; the CANARY_SIZE_MARK in the footer's size field
// confirms which (if any) is the correct interpretation.
//
// Returns the raw scudo pointer on success, or NULL if this is not a
// canary-tracked allocation.

static void *canary_get_raw(void *p, canary_footer **out_foot)
{
    // Try 1: p is the raw pointer (regular malloc/calloc/realloc)
    size_t usable = real_malloc_usable_size(p);
    if (usable >= sizeof(canary_footer)) {
        canary_footer *foot = (canary_footer *)((char *)p + usable - sizeof(canary_footer));
        if ((foot->size & CANARY_SIZE_MASK) == CANARY_SIZE_MARK) {
            *out_foot = foot;
            return p;
        }
    }

    // Try 2: p is an aligned pointer (posix_memalign); raw pointer at p[-1]
    void *raw = ((void **)p)[-1];
    usable = real_malloc_usable_size(raw);
    if (usable >= sizeof(canary_footer)) {
        canary_footer *foot = (canary_footer *)((char *)raw + usable - sizeof(canary_footer));
        if ((foot->size & CANARY_SIZE_MASK) == CANARY_SIZE_MARK) {
            *out_foot = foot;
            return raw;
        }
    }

    return NULL;
}

static void free_canary(void *p)
{
    if (!p) return;

    canary_footer *foot = NULL;
    void *raw = canary_get_raw(p, &foot);
    if (!raw) {
        // Not a canary-tracked allocation.
        // This happens for pointers from non-libb paths:
        //  - bionic internal allocations (atexit, TLS init, etc.)
        //  - allocations made before libb.so's constructor ran
        //  - __cxa_allocate_exception emergency pool (non-malloc path)
        // Pass through to real_free unchanged.
        real_free(p);
        return;
    }

    size_t n = foot->size & ~CANARY_SIZE_MASK;
    if (foot->tail_canary != CANARY_TAIL_MAGIC) {
        void *bt_buf[16];
        int bt_n = backtrace(bt_buf, 16);
        BRIDGE_LOG("*** HEAP-CORRUPT *** free(%p) size=%zu: TAIL canary=0x%016llx expected=0x%016llx",
                   p, n, (unsigned long long)foot->tail_canary, (unsigned long long)CANARY_TAIL_MAGIC);
        for (int k = 2; k < bt_n && k < 14; k++) {
            Dl_info info;
            if (dladdr(bt_buf[k], &info) && info.dli_sname) {
                BRIDGE_LOG("  #%02d %p in %s (%s+0x%lx)", k, bt_buf[k], info.dli_sname,
                    info.dli_fname ? info.dli_fname : "",
                    (unsigned long)bt_buf[k] - (unsigned long)info.dli_saddr);
            } else {
                BRIDGE_LOG("  #%02d %p", k, bt_buf[k]);
            }
        }
        __builtin_trap();
    }

    foot->tail_canary = 0;
    real_free(raw);
}

static void *realloc_canary(void *p, size_t n)
{
    if (!p) return malloc_canary(n);

    canary_footer *foot = NULL;
    void *raw = canary_get_raw(p, &foot);
    if (!raw) {
        // Not ours — just call real_realloc
        return real_realloc(p, n + sizeof(canary_footer));
    }

    if (foot->tail_canary != CANARY_TAIL_MAGIC) {
        BRIDGE_LOG("*** HEAP-CORRUPT *** realloc(%p): TAIL canary=0x%016llx", p, (unsigned long long)foot->tail_canary);
        __builtin_trap();
    }

    foot->tail_canary = 0;
    void *new_p = real_realloc(raw, n + sizeof(canary_footer));
    if (!new_p) return NULL;

    size_t new_usable = real_malloc_usable_size(new_p);
    canary_footer *new_foot = (canary_footer *)((char *)new_p + new_usable - sizeof(canary_footer));
    new_foot->size = n | CANARY_SIZE_MARK;
    new_foot->tail_canary = CANARY_TAIL_MAGIC;
    return new_p;
}

static void *calloc_canary(size_t m, size_t n)
{
    size_t sz = m * n;
    void *p = malloc_canary(sz);
    if (p) memset(p, 0, sz);
    return p;
}

#else
// Full mode: 56-byte header (size+canary+backtrace) + 8-byte tail footer.

#define CANARY_BT_DEPTH 4

typedef struct {
    size_t  size;                     // user-requested size
    uint64_t front_canary;            // detects underflow
    void   *alloc_bt[CANARY_BT_DEPTH]; // allocation backtrace
} canary_header;

typedef struct {
    uint64_t tail_canary;    // detects overflow
} canary_footer;

static void capture_bt(void **bt, int depth, int skip)
{
    void *raw[32];
    int n = backtrace(raw, 32);
    int j = 0;
    for (int i = skip; i < n && j < depth; i++, j++)
        bt[j] = raw[i];
    for (; j < depth; j++)
        bt[j] = NULL;
}

static void *malloc_canary(size_t n)
{
    size_t total = sizeof(canary_header) + n + sizeof(canary_footer);
    canary_header *hdr = (canary_header *)real_malloc(total);
    if (!hdr) return NULL;
    hdr->size = n;
    hdr->front_canary = CANARY_FRONT_MAGIC;
    capture_bt(hdr->alloc_bt, CANARY_BT_DEPTH, 3);
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
        BRIDGE_LOG("*** HEAP-CORRUPT *** free(%p) size=%zu: FRONT canary=0x%016llx expected=0x%016llx (underflow or double-free)",
                   p, hdr->size, (unsigned long long)hdr->front_canary, (unsigned long long)CANARY_FRONT_MAGIC);
        BRIDGE_LOG("  [ALLOC]");
        for (int k = 0; k < CANARY_BT_DEPTH && hdr->alloc_bt[k]; k++) {
            Dl_info info;
            if (dladdr(hdr->alloc_bt[k], &info) && info.dli_sname) {
                BRIDGE_LOG("    #%02d %p in %s (%s+0x%lx)", k, hdr->alloc_bt[k], info.dli_sname,
                    info.dli_fname ? info.dli_fname : "",
                    (unsigned long)hdr->alloc_bt[k] - (unsigned long)info.dli_saddr);
            } else {
                BRIDGE_LOG("    #%02d %p", k, hdr->alloc_bt[k]);
            }
        }
        BRIDGE_LOG("  [FREE]");
        void *bt[32]; int n = backtrace(bt, 32);
        for (int k = 2; k < n && k < 18; k++) {
            Dl_info info;
            if (dladdr(bt[k], &info) && info.dli_sname) {
                BRIDGE_LOG("    #%02d %p in %s (%s+0x%lx)", k, bt[k], info.dli_sname,
                    info.dli_fname ? info.dli_fname : "",
                    (unsigned long)bt[k] - (unsigned long)info.dli_saddr);
            } else {
                BRIDGE_LOG("    #%02d %p", k, bt[k]);
            }
        }
        __builtin_trap();
    }

    canary_footer *ftr = (canary_footer *)((char *)p + hdr->size);
    if (ftr->tail_canary != CANARY_TAIL_MAGIC) {
        BRIDGE_LOG("*** HEAP-CORRUPT *** free(%p) size=%zu: TAIL canary=0x%016llx expected=0x%016llx (overflow!)",
                   p, hdr->size, (unsigned long long)ftr->tail_canary, (unsigned long long)CANARY_TAIL_MAGIC);
        BRIDGE_LOG("  [ALLOC]");
        for (int k = 0; k < CANARY_BT_DEPTH && hdr->alloc_bt[k]; k++) {
            Dl_info info;
            if (dladdr(hdr->alloc_bt[k], &info) && info.dli_sname) {
                BRIDGE_LOG("    #%02d %p in %s (%s+0x%lx)", k, hdr->alloc_bt[k], info.dli_sname,
                    info.dli_fname ? info.dli_fname : "",
                    (unsigned long)hdr->alloc_bt[k] - (unsigned long)info.dli_saddr);
            } else {
                BRIDGE_LOG("    #%02d %p", k, hdr->alloc_bt[k]);
            }
        }
        BRIDGE_LOG("  tail bytes: %02x %02x %02x %02x %02x %02x %02x %02x"
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

#endif // MALLOC_CANARY_MINIMAL

#endif // MALLOC_CANARY_DEBUG

// ── MALLOC_PADDING: add N bytes per allocation to simulate musl chunk spacing ──
#ifdef MALLOC_PADDING
static inline size_t malloc_pad(size_t n) { return n > SIZE_MAX - MALLOC_PADDING ? n : n + MALLOC_PADDING; }
#else
static inline size_t malloc_pad(size_t n) { return n; }
#endif

#define BIONIC_TLS_SIZE 64
#define MAX_TLS_ENTRIES 128

static uintptr_t main_bionic_tp;

static struct {
    pid_t tid;
    uintptr_t tp;
} tls_table[MAX_TLS_ENTRIES];
static volatile int tls_count = 0;

// Forward declarations for functions defined later in this file.
void enter_bionic_alloc(uintptr_t *saved);
void leave_bionic_alloc(uintptr_t saved);

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

// ── strdup ──────────────────────────────────────────────────────────────
// Must be exported so musl-compiled .so files resolve it against libb.so
// instead of bionic's strdup (which would call bionic malloc without TLS
// switching, corrupting scudo per-thread cache).

char *strdup(const char *s)
{
    if (!s) return NULL;
    size_t len = strlen(s) + 1;
    char *p = (char *)malloc(len);
    if (p) memcpy(p, s, len);
    return p;
}

// ── realpath ────────────────────────────────────────────────────────────
// Same issue as strdup: bionic realpath internally calls bionic malloc.
// We wrap it with TLS switching.  The real bionic realpath is resolved
// eagerly in __malloc_bridge_init to avoid dlsym() in musl threads.

static char *(*bionic_realpath)(const char *, char *) = NULL;

char *realpath(const char *path, char *resolved)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    char *result = bionic_realpath(path, resolved);
    leave_bionic_alloc(saved);
    return result;
}

// ── putenv / setenv ─────────────────────────────────────────────────────
// musl's putenv/setenv internally call realloc/malloc/free.  If these
// resolve to bionic directly, they bypass TLS switching and corrupt the
// scudo allocator state.
//
// We wrap them with TLS switching, resolving the real bionic functions
// eagerly in __malloc_bridge_init.

static int (*bionic_putenv)(char *) = NULL;
static int (*bionic_setenv)(const char *, const char *, int) = NULL;

int putenv(char *s)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    int r = bionic_putenv(s);
    leave_bionic_alloc(saved);
    return r;
}

int setenv(const char *name, const char *value, int overwrite)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    int r = bionic_setenv(name, value, overwrite);
    leave_bionic_alloc(saved);
    return r;
}

// ── fdopen ──────────────────────────────────────────────────────────────
// Must return a MUSL FILE*, not a bionic FILE*.  Bionic FILE* has a
// different struct layout — calling musl's fclose/fputs/fgets on it
// dereferences wrong offsets (e.g. f->close at offset 24 → NULL → SIGSEGV).
//
// Use musl's internal __fmodeflags + __fdopenx, which are compiled into
// libb.so.  -Wl,-Bsymbolic ensures internal resolution to libb.so's copies.

// Flags from musl stdio_impl.h (F_NORD=4, F_NOWR=8)
extern int __fmodeflags(const char *mode, int *file_flags);
extern FILE *__fdopenx(int fd, int flags);

FILE *fdopen(int fd, const char *mode)
{
    int file_flags = 0;
    if (__fmodeflags(mode, &file_flags) < 0) return NULL;

    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    FILE *r = __fdopenx(fd, file_flags);
    leave_bionic_alloc(saved);
    return r;
}

// ── unsetenv ────────────────────────────────────────────────────────────
// musl's unsetenv internally calls free() via __env_rm_add.  Wrap with TLS
// switching so the free goes through bionic's allocator with correct TLS.

static int (*bionic_unsetenv)(const char *) = NULL;

int unsetenv(const char *name)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    int r = bionic_unsetenv(name);
    leave_bionic_alloc(saved);
    return r;
}

// ── iconv ───────────────────────────────────────────────────────────────
// iconv_open allocates a conversion descriptor via malloc.  iconv_close
// frees it.  Wrap all three with TLS switching.

static iconv_t (*bionic_iconv_open)(const char *, const char *) = NULL;
static int    (*bionic_iconv_close)(iconv_t) = NULL;
static size_t (*bionic_iconv)(iconv_t, char **, size_t *, char **, size_t *) = NULL;

iconv_t iconv_open(const char *tocode, const char *fromcode)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    iconv_t r = bionic_iconv_open(tocode, fromcode);
    leave_bionic_alloc(saved);
    return r;
}

int iconv_close(iconv_t cd)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    int r = bionic_iconv_close(cd);
    leave_bionic_alloc(saved);
    return r;
}

size_t iconv(iconv_t cd, char **inbuf, size_t *inbytesleft,
             char **outbuf, size_t *outbytesleft)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    size_t r = bionic_iconv(cd, inbuf, inbytesleft, outbuf, outbytesleft);
    leave_bionic_alloc(saved);
    return r;
}

// ── posix_memalign ──────────────────────────────────────────────────────
//
// WHY this exists:
//   HOA's ELF patcher rewrites HAP .so files' DT_NEEDED libc.so → libb.so.
//   This redirects malloc/free to libb.so, but does NOT cover posix_memalign
//   unless libb.so explicitly exports it.  libc++abi's __cxa_allocate_exception
//   calls posix_memalign internally.  Without this export, it resolves to
//   bionic, bypassing TLS switching entirely.  When called from a musl thread
//   (e.g. ArkUI-X Runtime worker), TPIDR_EL0 still points to musl TLS while
//   scudo expects bionic TLS → per-thread cache corruption → random crashes.
//
//   We must export posix_memalign so it routes through libb.so with correct
//   TLS switching.  In canary mode, we additionally allocate through our own
//   real_malloc so the allocation gets a canary footer.  In non-canary mode,
//   a simple TLS-switching passthrough to bionic suffices.
//
// HOW canary mode works:
//   The caller expects an aligned pointer.  We allocate extra space for
//   alignment padding, a pointer-sized slot before the aligned address,
//   and the canary footer.  Raw scudo pointer is stored at aligned[-1]
//   so free_canary/realloc_canary can recover it via canary_get_raw().
//
//   Layout:
//     [pad] [raw_ptr:8] [aligned user data: size] [slack] [size:8] [canary:8]
//           ^            ^                                  ^         ^
//           |            returned to caller                 usable-16 usable-8
//           aligned[-1] = raw

#ifdef MALLOC_CANARY_DEBUG
int posix_memalign(void **memptr, size_t alignment, size_t size)
{
    if (alignment < sizeof(void *)) alignment = sizeof(void *);
    if (size == 0) { *memptr = NULL; return 0; }

    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);

    size_t overhead = sizeof(void *) + alignment - 1 + sizeof(canary_footer);
    if (size > (size_t)-1 - overhead) {
        leave_bionic_alloc(saved);
        *memptr = NULL;
        return 12;
    }

    void *raw = real_malloc(size + overhead);
    if (!raw) {
        leave_bionic_alloc(saved);
        *memptr = NULL;
        return 12;
    }

    uintptr_t addr = (uintptr_t)raw + sizeof(void *);
    uintptr_t aligned = (addr + alignment - 1) & ~(alignment - 1);
    ((void **)aligned)[-1] = raw;

    size_t usable = real_malloc_usable_size(raw);
    canary_footer *foot = (canary_footer *)((char *)raw + usable - sizeof(canary_footer));
    foot->size = size | CANARY_SIZE_MARK;
    foot->tail_canary = CANARY_TAIL_MAGIC;

    *memptr = (void *)aligned;

    leave_bionic_alloc(saved);
    return 0;
}
#else
// Non-canary mode: TLS-switching passthrough.  No canary footer needed
// since free() won't be checking for one.  We still need TLS switching
// to protect scudo's per-thread cache when called from musl threads.
static int (*bionic_posix_memalign)(void **, size_t, size_t) = NULL;

int posix_memalign(void **memptr, size_t alignment, size_t size)
{
    uintptr_t saved = 0;
    enter_bionic_alloc(&saved);
    int r = bionic_posix_memalign(memptr, alignment, size);
    leave_bionic_alloc(saved);
    return r;
}
#endif

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

    // android_mallopt or malloc_usable_size — needed for footer-only canary
#ifdef MALLOC_CANARY_DEBUG
    real_malloc_usable_size = (size_t (*)(const void *))
        dlsym(RTLD_NEXT, "malloc_usable_size");
#endif

    // Resolve bionic's realpath for the TLS-switching wrapper.
    bionic_realpath = (char *(*)(const char *, char *))
        dlsym(RTLD_NEXT, "realpath");

    // Resolve bionic's putenv/setenv/fdopen for TLS-switching wrappers.
    bionic_putenv = (int (*)(char *))
        dlsym(RTLD_NEXT, "putenv");
    bionic_setenv = (int (*)(const char *, const char *, int))
        dlsym(RTLD_NEXT, "setenv");

    // Resolve bionic's unsetenv/iconv for TLS-switching wrappers.
    bionic_unsetenv = (int (*)(const char *))
        dlsym(RTLD_NEXT, "unsetenv");
    bionic_iconv_open = (iconv_t (*)(const char *, const char *))
        dlsym(RTLD_NEXT, "iconv_open");
#ifndef MALLOC_CANARY_DEBUG
    bionic_posix_memalign = (int (*)(void **, size_t, size_t))
        dlsym(RTLD_NEXT, "posix_memalign");
#endif
    bionic_iconv_close = (int (*)(iconv_t))
        dlsym(RTLD_NEXT, "iconv_close");
    bionic_iconv = (size_t (*)(iconv_t, char **, size_t *, char **, size_t *))
        dlsym(RTLD_NEXT, "iconv");
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

void enter_bionic_alloc(uintptr_t *saved)
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

void leave_bionic_alloc(uintptr_t saved)
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
    void *p = real_malloc(malloc_pad(n));
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
    void *r = real_realloc(p, malloc_pad(n));
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
    void *r = real_calloc(m, malloc_pad((m)*(n)));
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
    void *p = real_malloc(malloc_pad(n));
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
    void *r = real_realloc(p, malloc_pad(n));
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
    void *r = real_calloc(m, malloc_pad((m)*(n)));
#endif
    leave_bionic_alloc(s);
    return r;
}
