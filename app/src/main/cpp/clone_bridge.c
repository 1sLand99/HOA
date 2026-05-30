// Replaces musl src/thread/aarch64/clone.s.
//
// Android seccomp blocks raw SYS_clone from non-bionic callers, so we
// call bionic's clone() wrapper which the kernel trusts.  We strip
// CLONE_DETACHED (obsolete, kernel may reject) and CLONE_SETTLS
// (bionic's __start_thread must see bionic's TPIDR_EL0, not musl's TP).

#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <sys/syscall.h>

typedef void *(*musl_start_t)(void *);

// Forward: bionic's clone (declared in <sched.h>; we declare to match
// musl's expected signature exactly).
extern int clone(int (*fn)(void *), void *child_stack, int flags, void *arg,
                 int *parent_tid, void *tls, int *child_tid);

int __clone(musl_start_t func, void *stack, int flags, void *arg,
            int *ptid, void *tls, int *ctid)
{
    int clean_flags = flags & ~(0x00400000 | 0x00080000);

    // tls is the musl TP — don't pass it as bionic TLS.  The child will
    // inherit the parent's (bionic) TPIDR_EL0, keeping __start_thread happy.
    int ret = clone((int (*)(void *))func, stack, clean_flags, arg,
                    ptid, NULL, ctid);

    if (ret == 0) {
        // bionic's clone() never returns 0 to the caller; if it does,
        // something is wrong.  Exit to avoid running parent code in child.
        syscall(SYS_exit, 0);
        __builtin_unreachable();
    }

    return ret;
}
