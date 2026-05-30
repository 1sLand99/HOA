#define _GNU_SOURCE
#include <elf.h>
#include <link.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* a symbol→address pair to redirect */
struct got_target {
    const char *name;
    void *addr;
};

struct patch_ctx {
    const char *so_basename;
    const struct got_target *targets;
    int ntargets;
    int patched;
    int errors;
};

static void *resolve_target(const struct patch_ctx *ctx, const char *sym)
{
    for (int i = 0; i < ctx->ntargets; i++) {
        if (strcmp(sym, ctx->targets[i].name) == 0)
            return ctx->targets[i].addr;
    }
    return NULL;
}

static int patch_one_library(struct dl_phdr_info *info, size_t size, void *data)
{
    (void)size;
    struct patch_ctx *ctx = (struct patch_ctx *)data;

    const char *name = info->dlpi_name;
    if (!name || !name[0]) return 0;
    const char *slash = strrchr(name, '/');
    const char *basename = slash ? slash + 1 : name;

    if (strcmp(basename, ctx->so_basename) != 0) return 0;

    if (!info->dlpi_phdr || info->dlpi_phnum == 0) return 0;

    uintptr_t base = info->dlpi_addr;
    const Elf64_Phdr *phdr = info->dlpi_phdr;
    const Elf64_Dyn *dyn = NULL;

    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = (const Elf64_Dyn *)(base + phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const Elf64_Sym *dynsym = NULL;
    const char *dynstr = NULL;
    const Elf64_Rela *jmprel = NULL;
    size_t jmprel_sz = 0;
    const Elf64_Rela *rela = NULL;
    size_t rela_sz = 0;

    for (const Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
        case DT_SYMTAB:   dynsym    = (const Elf64_Sym  *)(base + d->d_un.d_ptr); break;
        case DT_STRTAB:   dynstr    = (const char       *)(base + d->d_un.d_ptr); break;
        case DT_JMPREL:   jmprel    = (const Elf64_Rela *)(base + d->d_un.d_ptr); break;
        case DT_PLTRELSZ: jmprel_sz = d->d_un.d_val;                              break;
        case DT_RELA:     rela      = (const Elf64_Rela *)(base + d->d_un.d_ptr); break;
        case DT_RELASZ:   rela_sz   = d->d_un.d_val;                                break;
        }
    }
    if (!dynsym || !dynstr) return 0;

    long pg_sz = sysconf(_SC_PAGESIZE);
    uintptr_t pg_mask = ~(uintptr_t)(pg_sz - 1);

    /* walk JUMP_SLOT relocations */
    if (jmprel && jmprel_sz) {
        size_t n = jmprel_sz / sizeof(Elf64_Rela);
        for (size_t i = 0; i < n; i++) {
            const char *sym = dynstr + dynsym[ELF64_R_SYM(jmprel[i].r_info)].st_name;
            void *target = resolve_target(ctx, sym);
            if (!target) continue;

            uintptr_t got = base + jmprel[i].r_offset;
            uintptr_t pg  = got & pg_mask;
            if (mprotect((void *)pg, pg_sz, PROT_READ | PROT_WRITE) == 0) {
                *(void **)got = target;
                ctx->patched++;
            } else {
                ctx->errors++;
            }
        }
    }

    /* walk GLOB_DAT relocations */
    if (rela && rela_sz) {
        size_t n = rela_sz / sizeof(Elf64_Rela);
        for (size_t i = 0; i < n; i++) {
            if (ELF64_R_TYPE(rela[i].r_info) != R_AARCH64_GLOB_DAT) continue;
            const char *sym = dynstr + dynsym[ELF64_R_SYM(rela[i].r_info)].st_name;
            void *target = resolve_target(ctx, sym);
            if (!target) continue;

            uintptr_t got = base + rela[i].r_offset;
            uintptr_t pg  = got & pg_mask;
            if (mprotect((void *)pg, pg_sz, PROT_READ | PROT_WRITE) == 0) {
                *(void **)got = target;
                ctx->patched++;
            } else {
                ctx->errors++;
            }
        }
    }

    return 0;
}

int elf_patch_got_signal(const char *so_basename,
                          const struct got_target *targets, int ntargets)
{
    struct patch_ctx ctx = {
        .so_basename = so_basename,
        .targets     = targets,
        .ntargets    = ntargets,
        .patched     = 0,
        .errors      = 0,
    };
    dl_iterate_phdr(patch_one_library, &ctx);
    return ctx.patched;
}
