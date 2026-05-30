#include <errno.h>
#include <stddef.h>

/*
 * HAP .so is compiled with OHOS SDK (musl headers).  struct sigaction layout:
 *   offset 0: union { void (*sa_handler)(int);
 *                     void (*sa_sigaction)(int, siginfo_t*, void*); }
 *   offset 8: unsigned long sa_mask[2]   (musl sigset_t, _NSIG=65)
 *   offset 24: int sa_flags + 4 pad
 *   offset 32: void (*sa_restorer)(void)
 * total: 40 bytes, sa_handler FIRST (unlike bionic where sa_flags is first).
 *
 * The kernel struct on aarch64 is:
 *   offset 0: sa_handler (8 bytes)
 *   offset 8: sa_flags   (8 bytes)
 *   offset 16: sa_restorer (8 bytes)
 *   offset 24: sa_mask[1]  (8 bytes, _KERNEL__NSIG=64 -> 1 word)
 * total: 32 bytes, sa_handler also first (matches kernel convention).
 *
 * We convert from HAP (musl) layout to kernel layout.
 */
struct kernel_sigaction {
    void (*sa_handler)(int);
    unsigned long sa_flags;
    void (*sa_restorer)(void);
    unsigned long sa_mask[1];
};

/* HAP's struct sigaction — must match OHOS SDK (musl) layout exactly */
struct hap_sigaction {
    void (*sa_handler)(int);    /* offset 0,  also .sa_sigaction via union */
    unsigned long sa_mask[2];   /* offset 8,  musl sigset_t */
    int sa_flags;               /* offset 24 */
    int __pad;                  /* offset 28 */
    void (*sa_restorer)(void);  /* offset 32 */
};

static long _sig_syscall(long n, long a, long b, long c, long d)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0"
                     : "+r"(x0)
                     : "r"(x8), "r"(x1), "r"(x2), "r"(x3)
                     : "memory");
    return x0;
}

static inline long _syscall_ret(long r)
{
    if (r > -4096UL) { errno = -r; return -1; }
    return r;
}

int sigaction(int sig, const struct sigaction *restrict act, struct sigaction *restrict old)
{
    struct kernel_sigaction ksa, ksa_old;
    const struct hap_sigaction *hap_act = (const struct hap_sigaction *)act;
    struct hap_sigaction *hap_old = (struct hap_sigaction *)old;

    /* musl reserves signals 32-34 (SIGCANCEL/SIGSYNCCALL/SIGTIMER);
       signals ≥ 64 exceed _KERNEL__NSIG */
    if (sig - 32U < 3 || sig - 1U >= (unsigned)(8*sizeof(unsigned long)) - 1) {
        errno = EINVAL;
        return -1;
    }

    if (act) {
        ksa.sa_handler  = hap_act->sa_handler;
        ksa.sa_flags    = (unsigned long)(unsigned int)hap_act->sa_flags;
        ksa.sa_mask[0]  = hap_act->sa_mask[0];
        ksa.sa_restorer = NULL;
    }

    long r = _sig_syscall(134 /* SYS_rt_sigaction */,
                          sig,
                          (long)(act ? &ksa : 0),
                          (long)(old ? &ksa_old : 0),
                          sizeof(unsigned long));

    if (old && r >= 0) {
        hap_old->sa_handler  = ksa_old.sa_handler;
        hap_old->sa_mask[0]  = ksa_old.sa_mask[0];
        hap_old->sa_mask[1]  = 0;
        hap_old->sa_flags    = (int)ksa_old.sa_flags;
        hap_old->sa_restorer = ksa_old.sa_restorer;
    }
    return _syscall_ret(r);
}

/* musl internal aliases — signal.c and siginterrupt.c call these */
int __sigaction(int sig, const struct sigaction *restrict act, struct sigaction *restrict old)
    __attribute__((alias("sigaction")));
int __libc_sigaction(int sig, const struct sigaction *restrict act, struct sigaction *restrict old)
    __attribute__((alias("sigaction")));
