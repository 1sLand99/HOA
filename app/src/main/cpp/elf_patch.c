#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

// elf_patch_libc_to_libb(path)
//
// In-place replacement: "libc.so\0" → "libb.so\0" in the ELF .dynstr
// section.  Both strings are exactly 8 bytes, so this is a single
// memcpy — no section growth, no .dynamic shifting.
//
// Returns 0 on success, -1 if the file does not have a DT_NEEDED for
// "libc.so", or on ELF parse errors.

int elf_patch_libc_to_libb(const char *path)
{
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;

    struct stat st;
    if (fstat(fd, &st) < 0) { close(fd); return -1; }

    void *map = mmap(NULL, st.st_size, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -1;

    // Validate ELF header
    if (st.st_size < (off_t)sizeof(Elf64_Ehdr)) goto fail;
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)map;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) goto fail;
    if (ehdr->e_ident[EI_CLASS] != ELFCLASS64) goto fail;

    // Find PT_DYNAMIC
    Elf64_Phdr *phdr = (Elf64_Phdr *)((char *)map + ehdr->e_phoff);
    Elf64_Addr dyn_addr = 0;
    Elf64_Off dyn_offset = 0;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn_addr = phdr[i].p_vaddr;
            dyn_offset = phdr[i].p_offset;
            break;
        }
    }
    if (dyn_addr == 0) goto fail;

    // Walk .dynamic to find DT_STRTAB
    Elf64_Dyn *dyn = (Elf64_Dyn *)((char *)map + dyn_offset);
    const char *dynstr = NULL;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_STRTAB) {
            // d_un.d_ptr is a virtual address; convert to file offset.
            // For .dynstr this is typically in the first PT_LOAD segment,
            // so vaddr → file offset is: file = vaddr - p_vaddr + p_offset.
            int found = 0;
            for (int i = 0; i < ehdr->e_phnum; i++) {
                if (phdr[i].p_type == PT_LOAD &&
                    d->d_un.d_val >= phdr[i].p_vaddr &&
                    d->d_un.d_val < phdr[i].p_vaddr + phdr[i].p_filesz) {
                    dynstr = (const char *)map + phdr[i].p_offset
                             + (d->d_un.d_val - phdr[i].p_vaddr);
                    found = 1;
                    break;
                }
            }
            if (!found) goto fail;
            break;
        }
    }
    if (dynstr == NULL) goto fail;

    // Check if "libb.so" already present (already patched)
    int already_patched = 0;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_NEEDED &&
            strcmp(dynstr + d->d_un.d_val, "libb.so") == 0) {
            already_patched = 1;
            break;
        }
    }
    if (already_patched) { munmap(map, st.st_size); return 0; }

    // Find DT_NEEDED for "libc.so" and replace in-place
    int patched = 0;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_NEEDED &&
            strcmp(dynstr + d->d_un.d_val, "libc.so") == 0) {
            memcpy((char *)dynstr + d->d_un.d_val, "libb.so", 8);
            patched = 1;
            break;
        }
    }

    munmap(map, st.st_size);
    return patched ? 0 : -1;

fail:
    munmap(map, st.st_size);
    return -1;
}

// elf_patch_runpath_to_origin(path)
//
// Overwrite DT_RUNPATH (and DT_RPATH if present) with "$ORIGIN" so that
// the linker resolves DT_NEEDED dependencies from the same directory as
// the loading .so, rather than the OHOS build-machine path baked in by
// the HarmonyOS toolchain.
//
// "$ORIGIN" is 8 bytes (including NUL).  The existing RUNPATH string is
// always longer (typically a build-machine absolute path), so we can
// overwrite in place without growing any section.
//
// Returns 0 on success (or if no RUNPATH/RPATH present), -1 on error.

int elf_patch_runpath_to_origin(const char *path)
{
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;

    struct stat st;
    if (fstat(fd, &st) < 0) { close(fd); return -1; }

    void *map = mmap(NULL, st.st_size, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -1;

    if (st.st_size < (off_t)sizeof(Elf64_Ehdr)) goto fail2;
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)map;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) goto fail2;
    if (ehdr->e_ident[EI_CLASS] != ELFCLASS64) goto fail2;

    Elf64_Phdr *phdr = (Elf64_Phdr *)((char *)map + ehdr->e_phoff);
    Elf64_Off dyn_offset = 0;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn_offset = phdr[i].p_offset;
            break;
        }
    }
    if (dyn_offset == 0) goto fail2;

    Elf64_Dyn *dyn = (Elf64_Dyn *)((char *)map + dyn_offset);

    // Find .dynstr
    const char *dynstr = NULL;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_STRTAB) {
            int found = 0;
            for (int i = 0; i < ehdr->e_phnum; i++) {
                if (phdr[i].p_type == PT_LOAD &&
                    d->d_un.d_val >= phdr[i].p_vaddr &&
                    d->d_un.d_val < phdr[i].p_vaddr + phdr[i].p_filesz) {
                    dynstr = (const char *)map + phdr[i].p_offset
                             + (d->d_un.d_val - phdr[i].p_vaddr);
                    found = 1;
                    break;
                }
            }
            if (!found) goto fail2;
            break;
        }
    }
    if (dynstr == NULL) goto fail2;

    int patched = 0;

    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_RUNPATH) {
            memcpy((char *)dynstr + d->d_un.d_val, "$ORIGIN", 8);
            patched = 1;
        }
        // Also clear DT_RPATH if present, so RUNPATH takes effect
        // even when the executable has an RPATH.
        if (d->d_tag == DT_RPATH) {
            memcpy((char *)dynstr + d->d_un.d_val, "$ORIGIN", 8);
            patched = 1;
        }
    }

    munmap(map, st.st_size);
    return patched ? 0 : -1;

fail2:
    munmap(map, st.st_size);
    return -1;
}

#define MAX_NEEDED 32
#define MAX_SONAME 256

// Read DT_NEEDED sonames from an ELF shared library.
// needed: array of at least max elements, each char[MAX_SONAME].
// Returns number of DT_NEEDED entries, or -1 on error.
int elf_read_needed(const char *path, char (*needed)[MAX_SONAME], int max)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;

    struct stat st;
    if (fstat(fd, &st) < 0) { close(fd); return -1; }

    void *map = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -1;

    if (st.st_size < (off_t)sizeof(Elf64_Ehdr)) { munmap(map, st.st_size); return -1; }
    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)map;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) { munmap(map, st.st_size); return -1; }
    if (ehdr->e_ident[EI_CLASS] != ELFCLASS64) { munmap(map, st.st_size); return -1; }

    Elf64_Phdr *phdr = (Elf64_Phdr *)((char *)map + ehdr->e_phoff);
    Elf64_Off dyn_offset = 0;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn_offset = phdr[i].p_offset;
            break;
        }
    }
    if (dyn_offset == 0) { munmap(map, st.st_size); return -1; }

    Elf64_Dyn *dyn = (Elf64_Dyn *)((char *)map + dyn_offset);

    const char *dynstr = NULL;
    for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_STRTAB) {
            for (int i = 0; i < ehdr->e_phnum; i++) {
                if (phdr[i].p_type == PT_LOAD &&
                    d->d_un.d_val >= phdr[i].p_vaddr &&
                    d->d_un.d_val < phdr[i].p_vaddr + phdr[i].p_filesz) {
                    dynstr = (const char *)map + phdr[i].p_offset
                             + (d->d_un.d_val - phdr[i].p_vaddr);
                    break;
                }
            }
            break;
        }
    }

    int count = 0;
    if (dynstr != NULL) {
        for (Elf64_Dyn *d = dyn; d->d_tag != DT_NULL && count < max; d++) {
            if (d->d_tag == DT_NEEDED) {
                strncpy(needed[count], dynstr + d->d_un.d_val, MAX_SONAME - 1);
                needed[count][MAX_SONAME - 1] = '\0';
                count++;
            }
        }
    }

    munmap(map, st.st_size);
    return count;
}

// Load a shared library, recursively loading its DT_NEEDED dependencies
// found in the same directory first.  Uses RTLD_NOLOAD to skip already-
// loaded libraries and avoid infinite recursion on circular deps.
// Returns 0 on success, -1 on failure.
int elf_load_with_deps(const char *path)
{
    void *check = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    if (check != NULL) {
        dlclose(check);
        return 0;
    }

    char needed[MAX_NEEDED][MAX_SONAME];
    int n = elf_read_needed(path, needed, MAX_NEEDED);

    char dir[4096];
    const char *slash = strrchr(path, '/');
    if (slash) {
        size_t len = slash - path;
        memcpy(dir, path, len);
        dir[len] = '\0';
    } else {
        dir[0] = '.';
        dir[1] = '\0';
    }

    if (n > 0) {
        for (int i = 0; i < n; i++) {
            char dep_path[4096];
            snprintf(dep_path, sizeof(dep_path), "%s/%s", dir, needed[i]);
            if (access(dep_path, F_OK) == 0) {
                elf_load_with_deps(dep_path);
            }
        }
    }

    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "BRIDGE-LOAD",
                            "dlopen(%s): %s", path, dlerror());
        return -1;
    }
    __android_log_print(ANDROID_LOG_INFO, "BRIDGE-LOAD",
                        "dlopen(%s) OK", path);
    return 0;
}
