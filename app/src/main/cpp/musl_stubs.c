#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <stdint.h>

// ── __libc_malloc / __libc_calloc / __libc_realloc ──────────────────
//
// Some musl source files (#define malloc __libc_malloc) redirect their
// malloc calls.  Route all three to bionic's allocator.

void *__libc_malloc(size_t n)       { return malloc(n); }

// ── __environ ────────────────────────────────────────────────────────
//
// Used by popen.c to pass the environment to posix_spawn.
// In musl, __environ is weak_alias(environ). We can't alias to an
// external bionic symbol, so we provide a separate definition and
// point it to environ at load time.

extern char **environ;
char **__environ = NULL;

__attribute__((constructor)) static void __musl_stubs_init(void) {
    __environ = environ;
}

// ── __thread_list_lock ──────────────────────────────────────────────
//
// Used by pthread_create to serialize updates to the thread list.
// Defined in src/env/__init_tls.c which we can't compile (it handles
// full TLS bootstrap which bionic owns on Android).

volatile int __thread_list_lock = 0;

// ── __copy_tls ──────────────────────────────────────────────────────
//
// Copies the TLS initialization image and returns a pointer past the
// TCB.  Only called from pthread_create when spawning a new thread.
// Since our initial phase disallows HAP-created threads, this stub
// aborts if ever reached.

void *__copy_tls(unsigned char *mem)
{
    fprintf(stderr, "FATAL: __copy_tls called — HAP thread creation not supported\n");
    abort();
}

// ── MUSL_LOGW ───────────────────────────────────────────────────────
//
// OHOS HiLog wrapper.  The guard in pthread_impl.h prevents inclusion
// of the real header, but some code still references the function.
// Provide a weak no-op stub.
//
// The real signature is MUSL_LOGW(const char *fmt, ...) but since we
// define it as a no-op, a variadic macro in the OHOS header would be
// ideal.  Here we provide a function stub.

void MUSL_LOGW(const char *fmt, ...) { (void)fmt; }

// ── fdsan (OHOS file descriptor sanitizer) ───────────────────────────
//
// Several musl source files (stdio/__fdopen.c, __fopen_rb_ca.c,
// __stdio_close.c, dirent/opendir.c, fdopendir.c, closedir.c) call
// OHOS fdsan functions that don't exist on Android.  Provide no-op stubs.

uint64_t fdsan_create_owner_tag(int type, uint64_t value)
    { (void)type; return value; }
int fdsan_exchange_owner_tag(int fd, uint64_t old_tag, uint64_t new_tag)
    { (void)fd; (void)old_tag; (void)new_tag; return 0; }
int fdsan_close_with_tag(int fd, uint64_t tag)
    { (void)tag; return close(fd); }
void __init_fdsan(void) {}
int fdsan_set_error_level(int level) { (void)level; return 0; }

// ── __get_vdso_addr ──────────────────────────────────────────────────
//
// Resolves VDSO function pointers for fast clock_gettime etc. on Linux.
// On Android bionic manages VDSO internally.  Return NULL to force the
// slower syscall fallback path.

void *__get_vdso_addr(const char *vername, const char *name)
{
    (void)vername; (void)name;
    return NULL;
}

void __get_vdso_info(void) {}
