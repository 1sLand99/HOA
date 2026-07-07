/*
 * rawfile_bridge.cpp — OHOS librawfile.z.so bridge for HOA
 *
 * Implements the OHOS RawFile native API on top of the HAP extraction
 * directory.  HAP rawfiles live under:
 *   /data/user/0/app.hackeris.hoa/files/hap/<bundle>.<module>/resources/rawfile/
 *
 * NativeResourceManager stores all rawfile directories found on disk
 * and OpenRawFile/OpenRawDir search across them.  In HOA each process
 * runs exactly one HAP, so this is semantically equivalent to per-HAP
 * isolation and avoids depending on NAPI properties of the JS
 * resourceManager (which are not exposed as plain JS properties).
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <dirent.h>
#include <errno.h>
#include <sys/stat.h>
#include <unistd.h>

#include "napi/native_api.h"
#include "rawfile/raw_file_manager.h"
#include "rawfile/raw_file.h"
#include "rawfile/raw_dir.h"

#define MAX_HAP_MODULES 32

/* ── NativeResourceManager ───────────────────────────────────────────── */

struct NativeResourceManager {
    char **bases;
    int    count;
};

/* ── RawFile ─────────────────────────────────────────────────────────── */

struct RawFile {
    FILE  *fp;
    long   size;
    char  *path;
};

struct RawFile64 {
    FILE   *fp;
    int64_t size;
    char   *path;
};

/* ── RawDir ──────────────────────────────────────────────────────────── */

struct RawDir {
    DIR    *dir;
    char   *full_path;
    char  **names;
    int     count;
};

/* ── Helpers ─────────────────────────────────────────────────────────── */

static long file_size(FILE *fp)
{
    long cur = ftell(fp);
    fseek(fp, 0, SEEK_END);
    long sz = ftell(fp);
    fseek(fp, cur, SEEK_SET);
    return sz;
}

/* ── NativeResourceManager API ───────────────────────────────────────── */

NativeResourceManager *OH_ResourceManager_InitNativeResourceManager(
    napi_env env, napi_value jsResMgr)
{
    (void)env;
    (void)jsResMgr;

    NativeResourceManager *mgr = (NativeResourceManager*)calloc(1, sizeof(*mgr));
    if (!mgr) return NULL;

    const char *data_dir = "/data/user/0/app.hackeris.hoa";
    char hap_root[PATH_MAX];
    snprintf(hap_root, sizeof(hap_root), "%s/files/hap", data_dir);

    DIR *d = opendir(hap_root);
    if (!d) { free(mgr); return NULL; }

    struct dirent *ent;
    while ((ent = readdir(d)) != NULL && mgr->count < MAX_HAP_MODULES) {
        if (ent->d_name[0] == '.') continue;
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/%s/resources/rawfile", hap_root, ent->d_name);
        struct stat st;
        if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
            mgr->bases = (char**)realloc(mgr->bases, (size_t)(mgr->count + 1) * sizeof(char*));
            mgr->bases[mgr->count] = strdup(path);
            mgr->count++;
        }
    }
    closedir(d);

    if (mgr->count == 0) { free(mgr); return NULL; }
    return mgr;
}

void OH_ResourceManager_ReleaseNativeResourceManager(NativeResourceManager *mgr)
{
    if (!mgr) return;
    for (int i = 0; i < mgr->count; i++) free(mgr->bases[i]);
    free(mgr->bases);
    free(mgr);
}

/* ── RawFile API ─────────────────────────────────────────────────────── */

RawFile *OH_ResourceManager_OpenRawFile(const NativeResourceManager *mgr,
                                         const char *fileName)
{
    if (!mgr || !fileName) return NULL;

    for (int i = 0; i < mgr->count; i++) {
        size_t len = strlen(mgr->bases[i]) + 1 + strlen(fileName) + 1;
        char *full = (char*)malloc(len);
        if (!full) continue;
        snprintf(full, len, "%s/%s", mgr->bases[i], fileName);

        FILE *fp = fopen(full, "rb");
        if (fp) {
            RawFile *rf = (RawFile*)calloc(1, sizeof(RawFile));
            if (!rf) { fclose(fp); free(full); return NULL; }
            rf->fp = fp;
            rf->path = full;
            rf->size = file_size(fp);
            return rf;
        }
        free(full);
    }
    return NULL;
}

int OH_ResourceManager_ReadRawFile(const RawFile *rawFile, void *buf,
                                    size_t length)
{
    if (!rawFile || !buf || length == 0) return 0;
    return (int)fread(buf, 1, length, rawFile->fp);
}

int OH_ResourceManager_SeekRawFile(const RawFile *rawFile, long offset,
                                    int whence)
{
    if (!rawFile) return -1;
    return fseek(rawFile->fp, offset, whence);
}

long OH_ResourceManager_GetRawFileSize(RawFile *rawFile)
{
    if (!rawFile) return 0;
    return rawFile->size;
}

long OH_ResourceManager_GetRawFileRemainingLength(const RawFile *rawFile)
{
    if (!rawFile) return 0;
    long cur = ftell(rawFile->fp);
    if (cur < 0) return 0;
    return rawFile->size - cur;
}

void OH_ResourceManager_CloseRawFile(RawFile *rawFile)
{
    if (!rawFile) return;
    if (rawFile->fp) fclose(rawFile->fp);
    free(rawFile->path);
    free(rawFile);
}

long OH_ResourceManager_GetRawFileOffset(const RawFile *rawFile)
{
    if (!rawFile) return 0;
    return ftell(rawFile->fp);
}

bool OH_ResourceManager_GetRawFileDescriptor(const RawFile *rawFile,
                                              RawFileDescriptor &descriptor)
{
    RawFileDescriptor d;
    bool ok = OH_ResourceManager_GetRawFileDescriptorData(rawFile, &d);
    if (ok) descriptor = d;
    return ok;
}

bool OH_ResourceManager_GetRawFileDescriptorData(const RawFile *rawFile,
                                                   RawFileDescriptor *descriptor)
{
    if (!rawFile || !descriptor) return false;
    int fd = fileno(rawFile->fp);
    if (fd < 0) return false;
    descriptor->fd = dup(fd);
    descriptor->start = 0;
    descriptor->length = rawFile->size;
    return (descriptor->fd >= 0);
}

bool OH_ResourceManager_ReleaseRawFileDescriptor(
    const RawFileDescriptor &descriptor)
{
    return OH_ResourceManager_ReleaseRawFileDescriptorData(&descriptor);
}

bool OH_ResourceManager_ReleaseRawFileDescriptorData(
    const RawFileDescriptor *descriptor)
{
    if (!descriptor || descriptor->fd < 0) return false;
    close(descriptor->fd);
    return true;
}

/* ── RawDir API ──────────────────────────────────────────────────────── */

RawDir *OH_ResourceManager_OpenRawDir(const NativeResourceManager *mgr,
                                       const char *dirName)
{
    if (!mgr || !dirName) return NULL;

    for (int i = 0; i < mgr->count; i++) {
        size_t len = strlen(mgr->bases[i]) + 1 + strlen(dirName) + 1;
        char *full = (char*)malloc(len);
        if (!full) continue;
        snprintf(full, len, "%s/%s", mgr->bases[i], dirName);

        DIR *d = opendir(full);
        if (d) {
            RawDir *rd = (RawDir*)calloc(1, sizeof(RawDir));
            if (!rd) { closedir(d); free(full); return NULL; }
            rd->dir = d;
            rd->full_path = full;

            struct dirent *de;
            while ((de = readdir(d)) != NULL) {
                if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0)
                    continue;
                rd->count++;
                rd->names = (char**)realloc(rd->names, (size_t)rd->count * sizeof(char*));
                rd->names[rd->count - 1] = strdup(de->d_name);
            }
            rewinddir(d);
            return rd;
        }
        free(full);
    }
    return NULL;
}

const char *OH_ResourceManager_GetRawFileName(RawDir *rawDir, int index)
{
    if (!rawDir || index < 0 || index >= rawDir->count) return NULL;
    return rawDir->names[index];
}

int OH_ResourceManager_GetRawFileCount(RawDir *rawDir)
{
    if (!rawDir) return 0;
    return rawDir->count;
}

void OH_ResourceManager_CloseRawDir(RawDir *rawDir)
{
    if (!rawDir) return;
    if (rawDir->dir) closedir(rawDir->dir);
    for (int i = 0; i < rawDir->count; i++) free(rawDir->names[i]);
    free(rawDir->names);
    free(rawDir->full_path);
    free(rawDir);
}

/* ── RawFile64 API ───────────────────────────────────────────────────── */

RawFile64 *OH_ResourceManager_OpenRawFile64(const NativeResourceManager *mgr,
                                              const char *fileName)
{
    if (!mgr || !fileName) return NULL;

    for (int i = 0; i < mgr->count; i++) {
        size_t len = strlen(mgr->bases[i]) + 1 + strlen(fileName) + 1;
        char *full = (char*)malloc(len);
        if (!full) continue;
        snprintf(full, len, "%s/%s", mgr->bases[i], fileName);

        FILE *fp = fopen(full, "rb");
        if (fp) {
            RawFile64 *rf = (RawFile64*)calloc(1, sizeof(RawFile64));
            if (!rf) { fclose(fp); free(full); return NULL; }
            rf->fp = fp;
            rf->path = full;
            fseeko(fp, 0, SEEK_END);
            rf->size = ftello(fp);
            rewind(fp);
            return rf;
        }
        free(full);
    }
    return NULL;
}

int64_t OH_ResourceManager_ReadRawFile64(const RawFile64 *rawFile,
                                           void *buf, int64_t length)
{
    if (!rawFile || !buf || length <= 0) return 0;
    return (int64_t)fread(buf, 1, (size_t)length, rawFile->fp);
}

int OH_ResourceManager_SeekRawFile64(const RawFile64 *rawFile,
                                      int64_t offset, int whence)
{
    if (!rawFile) return -1;
    return fseeko(rawFile->fp, offset, whence);
}

int64_t OH_ResourceManager_GetRawFileSize64(RawFile64 *rawFile)
{
    if (!rawFile) return 0;
    return rawFile->size;
}

int64_t OH_ResourceManager_GetRawFileRemainingLength64(const RawFile64 *rawFile)
{
    if (!rawFile) return 0;
    off_t cur = ftello(rawFile->fp);
    if (cur < 0) return 0;
    return rawFile->size - cur;
}

void OH_ResourceManager_CloseRawFile64(RawFile64 *rawFile)
{
    if (!rawFile) return;
    if (rawFile->fp) fclose(rawFile->fp);
    free(rawFile->path);
    free(rawFile);
}

int64_t OH_ResourceManager_GetRawFileOffset64(const RawFile64 *rawFile)
{
    if (!rawFile) return 0;
    return ftello(rawFile->fp);
}

bool OH_ResourceManager_GetRawFileDescriptor64(const RawFile64 *rawFile,
                                                 RawFileDescriptor64 *descriptor)
{
    if (!rawFile || !descriptor) return false;
    int fd = fileno(rawFile->fp);
    if (fd < 0) return false;
    descriptor->fd = dup(fd);
    descriptor->start = 0;
    descriptor->length = rawFile->size;
    return (descriptor->fd >= 0);
}

bool OH_ResourceManager_ReleaseRawFileDescriptor64(
    const RawFileDescriptor64 *descriptor)
{
    if (!descriptor || descriptor->fd < 0) return false;
    close(descriptor->fd);
    return true;
}

/* ── OH_ResourceManager_IsRawDir (API 12) ────────────────────────────── */

bool OH_ResourceManager_IsRawDir(const NativeResourceManager *mgr,
                                  const char *path)
{
    if (!mgr || !path) return false;
    for (int i = 0; i < mgr->count; i++) {
        size_t len = strlen(mgr->bases[i]) + 1 + strlen(path) + 1;
        char *full = (char*)malloc(len);
        if (!full) continue;
        snprintf(full, len, "%s/%s", mgr->bases[i], path);
        struct stat st;
        bool is_dir = (stat(full, &st) == 0 && S_ISDIR(st.st_mode));
        free(full);
        if (is_dir) return true;
    }
    return false;
}
