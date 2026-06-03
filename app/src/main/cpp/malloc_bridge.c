#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>

// Bridge musl's internal allocator entry points to bionic jemalloc/scudo.
//
// On musl-created threads, tpidr_el0 points to the musl TLS area.  Bionic's
// scudo writes allocator state to tpidr_el0[6] (TLS_SLOT_SANITIZER), which
// would overwrite musl's tls_slots[] region.  We save/restore tpidr_el0
// around bionic allocator calls so scudo always sees a bionic thread pointer.

static uintptr_t bionic_tp;

__attribute__((constructor))
static void __malloc_bridge_init(void)
{
	__asm__ ("mrs %0, tpidr_el0" : "=r"(bionic_tp));
}

static inline void save_restore_tp(uintptr_t *saved)
{
	uintptr_t tp;
	__asm__ ("mrs %0, tpidr_el0" : "=r"(tp));
	if (tp == bionic_tp) {
		*saved = 0;
		return;
	}
	*saved = tp;
	__asm__ volatile ("msr tpidr_el0, %0" :: "r"(bionic_tp));
}

static inline void restore_tp(uintptr_t saved)
{
	if (saved)
		__asm__ volatile ("msr tpidr_el0, %0" :: "r"(saved));
}

void *__libc_malloc_impl(size_t n)
{
	uintptr_t s;
	save_restore_tp(&s);
	void *p = malloc(n);
	restore_tp(s);
	return p;
}

void __libc_free(void *p)
{
	uintptr_t s;
	save_restore_tp(&s);
	free(p);
	restore_tp(s);
}

void *__libc_realloc(void *p, size_t n)
{
	uintptr_t s;
	save_restore_tp(&s);
	void *r = realloc(p, n);
	restore_tp(s);
	return r;
}

void *__libc_calloc(size_t m, size_t n)
{
	uintptr_t s;
	save_restore_tp(&s);
	void *r = calloc(m, n);
	restore_tp(s);
	return r;
}
