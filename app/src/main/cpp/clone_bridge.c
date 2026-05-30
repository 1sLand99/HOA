// Replaces musl src/thread/aarch64/clone.s.
//
// Android seccomp may block raw SYS_clone (220) from non-bionic callers.
// Instead of our own syscall, we call bionic's clone() wrapper which the
// kernel trusts.  We then replicate the child-vs-parent dispatch that
// musl's clone.s did in assembly.

#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>

typedef void *(*musl_start_t)(void *);

// Logging via dlsym at runtime (no link-time dep on __android_log_print)
typedef int (*log_fn_t)(int prio, const char *tag, const char *fmt, ...);
static log_fn_t p_log = NULL;

static void log_init(void) {
    if (!p_log) {
        p_log = (log_fn_t)dlsym(RTLD_DEFAULT, "__android_log_print");
        // Fallback: write to stderr via syscall if dlsym fails
        if (!p_log) {
            const char msg[] = "HOA-CLONE: dlsym(__android_log_print) FAILED\n";
            syscall(SYS_write, 2, msg, sizeof(msg) - 1);
        }
    }
}

#define LOG_TAG "HOA-CLONE"
#define LOGI(fmt, ...) do { if (p_log) p_log(4, LOG_TAG, fmt, ##__VA_ARGS__); \
                            else { char _b[256]; int _n = snprintf(_b, sizeof(_b), LOG_TAG ": " fmt "\n", ##__VA_ARGS__); syscall(SYS_write, 2, _b, _n > 0 ? _n : 0); } } while(0)
#define LOGE(fmt, ...) do { if (p_log) p_log(6, LOG_TAG, fmt, ##__VA_ARGS__); \
                            else { char _b[256]; int _n = snprintf(_b, sizeof(_b), LOG_TAG "(E): " fmt "\n", ##__VA_ARGS__); syscall(SYS_write, 2, _b, _n > 0 ? _n : 0); } } while(0)

// Constructor to verify this module is loaded
__attribute__((constructor))
static void clone_bridge_init(void) {
    // Write a marker file to prove this code ran.
    int fd = open("/data/data/app.hackeris.hoa/files/_clone_bridge_loaded",
                  O_CREAT | O_WRONLY | O_TRUNC, 0666);
    if (fd >= 0) {
        const char *msg = "clone_bridge.c LOADED\n";
        (void)write(fd, msg, 22);
        close(fd);
    }
    log_init();
    LOGI("clone_bridge.c LOADED — constructor fired (pid=%d)", getpid());
}

// Forward: bionic's clone (declared in <sched.h> but we redeclare to
// match musl's expected signature exactly).
extern int clone(int (*fn)(void *), void *child_stack, int flags, void *arg,
                 int *parent_tid, void *tls, int *child_tid);

// Diagnostic globals — readable via dlsym from test code.
volatile int __clone_dbg_called = 0;
volatile int __clone_dbg_ret = 0;
volatile int __clone_dbg_errno = 0;
volatile int __clone_dbg_flags = 0;
volatile int __clone_dbg_clean = 0;

// pthread_create stage tracker: set by musl __pthread_create at each step.
// 0=entry, 1=threads_check_ok, 2=first_thread_init, 3=attr_setup,
// 4=mmap_done, 5=copy_tls_done, 6=before_clone
volatile int __pthread_create_stage = 0;
volatile int __pthread_create_fail_errno = 0;
volatile size_t __pthread_create_size = 0;
volatile size_t __pthread_create_guard = 0;
volatile size_t __pthread_create_tls_size = 0;
volatile size_t __pthread_create_tsd_size = 0;

// Write-to-file trace that's guaranteed to work in any process context.
static void raw_trace(const char *msg) {
    int len = 0; while (msg[len]) len++;
    int fd = open("/data/data/app.hackeris.hoa/files/_clone_trace",
                  O_CREAT|O_WRONLY|O_APPEND, 0666);
    if (fd >= 0) {
        syscall(SYS_write, fd, msg, len);
        close(fd);
    }
}

int __clone(musl_start_t func, void *stack, int flags, void *arg,
            int *ptid, void *tls, int *ctid)
{
    raw_trace("__clone ENTER\n");

    // Strip CLONE_DETACHED (0x00400000) and CLONE_SETTLS (0x00080000).
    // CLONE_DETACHED is obsolete and Android kernel may reject it.
    // CLONE_SETTLS must be stripped because bionic's __start_thread reads
    // TPIDR_EL0 expecting bionic's TLS layout.  If we set TPIDR_EL0 to
    // musl's TP via CLONE_SETTLS, __start_thread dereferences garbage and
    // SIGSEGV before reaching our start() function.
    int clean_flags = flags & ~(0x00400000 | 0x00080000);

    LOGI("__clone: func=%p stack=%p flags=0x%x(clean=0x%x) arg=%p ptid=%p tls=%p ctid=%p",
         func, stack, flags, clean_flags, arg, ptid, tls, ctid);

    // tls is the musl TP — don't pass it as bionic TLS.  The child will
    // inherit the parent's (bionic) TPIDR_EL0, keeping __start_thread happy.
    int ret = clone((int (*)(void *))func, stack, clean_flags, arg,
                    ptid, NULL, ctid);
    raw_trace("__clone AFTER clone syscall\n");

    __clone_dbg_called = 1;
    __clone_dbg_ret = ret;
    __clone_dbg_errno = (ret < 0) ? errno : 0;
    __clone_dbg_flags = flags;
    __clone_dbg_clean = clean_flags;

    LOGI("__clone: ret=%d errno=%d", ret, (ret < 0) ? errno : 0);

    if (ret == 0) {
        syscall(SYS_exit, 0);
        __builtin_unreachable();
    }

    return ret;
}
