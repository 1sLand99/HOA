#include <stdlib.h>

// Bridge musl's internal allocator entry points to bionic jemalloc.
// All malloc/free calls in HAP .so code share the same heap as the
// Android host process.
//
// WARNING: When called from a musl-created thread (tpidr_el0 points
// to musl TCB), bionic's scudo writes allocator state to
// TLS_SLOT_SANITIZER (tpidr_el0[6]), which overlaps musl TLS data.
// HAP-initiated thread creation is not yet supported (risk item:
// "musl 自建线程与 scudo TLS slot 冲突").  When musl thread creation
// is re-enabled, this bridge must be made thread-type-aware.

void *__libc_malloc_impl(size_t n)       { return malloc(n); }
void  __libc_free(void *p)               { free(p); }
void *__libc_realloc(void *p, size_t n)  { return realloc(p, n); }
void *__libc_calloc(size_t m, size_t n)  { return calloc(m, n); }

// REMOVED malloc/free aliases — caused infinite recursion:
//   alias → __libc_malloc_impl → malloc (PLT back to alias).
// Musl code calling malloc/free directly (e.g. ofl.c) resolves
// through NEEDED chain: libb.so → libc.so (bionic).
