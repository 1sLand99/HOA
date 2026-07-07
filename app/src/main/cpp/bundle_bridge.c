/*
 * bundle_bridge.cpp -- OHOS libbundle_ndk.z.so bridge for HOA
 *
 * Implements OHOS native bundle APIs by reading the HAP's module.json
 * from the extraction directory.  The HAP module directory is discovered
 * by enumerating files/hap/ (same approach as rawfile_bridge).
 *
 * APIs implemented:
 *   GetCurrentApplicationInfo   -- bundleName + fingerprint from module.json
 *   GetAppId / GetAppIdentifier -- empty (HOA has no OHOS signing)
 *   GetMainElementName          -- bundleName + moduleName + mainElement
 *   GetCompatibleDeviceType     -- deviceTypes from module.json
 *   IsDebugMode                 -- app.debug from module.json
 *   GetModuleMetadata           -- empty (not supported)
 *   GetAbilityResourceInfo + helpers -- stubs returning error codes
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

#include "bundle/native_interface_bundle.h"
/* Forward declarations for types from ability_resource_info.h */
struct OH_NativeBundle_AbilityResourceInfo;
typedef struct OH_NativeBundle_AbilityResourceInfo OH_NativeBundle_AbilityResourceInfo;
struct ArkUI_DrawableDescriptor;
typedef struct ArkUI_DrawableDescriptor ArkUI_DrawableDescriptor;

/* ── Path discovery ──────────────────────────────────────────────────── */

/* Find the most recently installed HAP's module.json. Returns a malloc'd path. */
static char *find_hap_module_dir(void)
{
    const char *base = "/data/user/0/app.hackeris.hoa/files/hap";
    DIR *d = opendir(base);
    if (!d) return NULL;

    char *result = NULL;
    time_t newest = 0;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (ent->d_name[0] == '.') continue;
        size_t len = strlen(base) + 1 + strlen(ent->d_name) + strlen("/module.json") + 1;
        char *path = (char*)malloc(len);
        if (!path) continue;
        snprintf(path, len, "%s/%s/module.json", base, ent->d_name);
        struct stat st;
        if (stat(path, &st) == 0 && S_ISREG(st.st_mode) && st.st_mtime > newest) {
            newest = st.st_mtime;
            free(result);
            result = path;
        } else {
            free(path);
        }
    }
    closedir(d);
    return result;
}

/* ── Minimal JSON string extractor ───────────────────────────────────── */

/*
 * Extract a string value for a top-level key in a flat JSON scan.
 * Looks for "key": "value" and writes value to buf.
 * Does NOT distinguish between nested objects -- use with care.
 * Returns true on success.
 */
static bool json_get_string(const char *json, const char *key,
                             char *buf, size_t bufsz)
{
    char search[256];
    snprintf(search, sizeof(search), "\"%s\"", key);

    const char *p = strstr(json, search);
    if (!p) return false;
    p += strlen(search);

    /* Skip whitespace and colon+whitespace */
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    if (*p != ':') return false;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    if (*p != '"') return false;
    p++;

    /* Copy until closing quote */
    size_t i = 0;
    while (*p && *p != '"' && i < bufsz - 1) {
        if (*p == '\\' && *(p+1)) p++; // skip escape
        buf[i++] = *p++;
    }
    buf[i] = '\0';
    return i > 0;
}

/*
 * Extract a boolean value. Returns true and sets *val on success.
 */
static bool json_get_bool(const char *json, const char *key, bool *val)
{
    char search[256];
    snprintf(search, sizeof(search), "\"%s\"", key);

    const char *p = strstr(json, search);
    if (!p) return false;
    p += strlen(search);

    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    if (*p != ':') return false;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;

    if (strncmp(p, "true", 4) == 0) { *val = true; return true; }
    if (strncmp(p, "false", 5) == 0) { *val = false; return true; }
    return false;
}

/*
 * Extract a string array and concatenate entries with commas.
 * Looks for "key": ["v1", "v2"] and writes "v1,v2" to buf.
 */
static bool json_get_string_array(const char *json, const char *key,
                                   char *buf, size_t bufsz)
{
    char search[256];
    snprintf(search, sizeof(search), "\"%s\"", key);

    const char *p = strstr(json, search);
    if (!p) return false;
    p += strlen(search);

    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    if (*p != ':') return false;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    if (*p != '[') return false;

    /* Read items */
    size_t total = 0;
    buf[0] = '\0';
    while (*p && *p != ']') {
        p++; /* skip [ or , */
        while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
        if (*p == ']') break;
        if (*p == '"') {
            p++;
            if (total > 0) {
                if (total + 2 < bufsz) { strcat(buf, ","); total++; }
            }
            while (*p && *p != '"' && total < bufsz - 1) {
                buf[total++] = *p++;
            }
            buf[total] = '\0';
            if (*p == '"') p++;
        }
    }
    return total > 0;
}

/* ── Cached module.json content ──────────────────────────────────────── */

static char  *g_json_buf = NULL;
static size_t g_json_len = 0;

/* Load module.json into memory (once, cached). */
static const char *load_module_json(void)
{
    if (g_json_buf) return g_json_buf;

    char *mod_dir = find_hap_module_dir();
    if (!mod_dir) return NULL;

    FILE *fp = fopen(mod_dir, "rb");
    free(mod_dir);
    if (!fp) return NULL;

    fseek(fp, 0, SEEK_END);
    long sz = ftell(fp);
    if (sz <= 0 || sz > 65536) { fclose(fp); return NULL; }
    rewind(fp);

    g_json_buf = (char*)malloc((size_t)sz + 1);
    if (!g_json_buf) { fclose(fp); return NULL; }
    g_json_len = (size_t)fread(g_json_buf, 1, (size_t)sz, fp);
    fclose(fp);
    g_json_buf[g_json_len] = '\0';
    return g_json_buf;
}

/* ── OH_NativeBundle_ApplicationInfo ─────────────────────────────────── */

OH_NativeBundle_ApplicationInfo OH_NativeBundle_GetCurrentApplicationInfo()
{
    OH_NativeBundle_ApplicationInfo info = {NULL, NULL};
    const char *json = load_module_json();
    if (!json) return info;

    /* bundleName -- look under "app" scope at depth 1 */
    const char *app = strstr(json, "\"app\"");
    if (app) {
        int depth = 0;
        const char *p = app;
        while (*p) {
            if (*p == '{' || *p == '[') depth++;
            else if (*p == '}' || *p == ']') { depth--; if (depth <= 0) break; }
            else if (depth == 1 && strncmp(p, "\"bundleName\"", 12) == 0) {
                p += 12;
                while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
                if (*p == ':') {
                    p++;
                    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
                    if (*p == '"') {
                        p++;
                        char name[256]; size_t i = 0;
                        while (*p && *p != '"' && i < sizeof(name) - 1) {
                            if (*p == '\\' && *(p+1)) p++;
                            name[i++] = *p++;
                        }
                        name[i] = '\0';
                        if (i > 0) info.bundleName = strdup(name);
                    }
                }
                break;
            }
            p++;
        }
    }

    /* fingerprint -- not available in HOA, leave NULL */
    info.fingerprint = NULL;
    return info;
}

/* ── AppId / AppIdentifier ───────────────────────────────────────────── */

char *OH_NativeBundle_GetAppId()
{
    /* HOA has no OHOS signature, return empty string */
    return strdup("");
}

char *OH_NativeBundle_GetAppIdentifier()
{
    return strdup("");
}

/* ── MainElementName ─────────────────────────────────────────────────── */

OH_NativeBundle_ElementName OH_NativeBundle_GetMainElementName()
{
    OH_NativeBundle_ElementName ename = {NULL, NULL, NULL};
    const char *json = load_module_json();
    if (!json) return ename;

    /* bundleName from app scope */
    const char *app = strstr(json, "\"app\"");
    if (app) {
        char name[256];
        if (json_get_string(app, "bundleName", name, sizeof(name)))
            ename.bundleName = strdup(name);
    }

    /* moduleName and mainElement from module scope.
     * "moduleName" is "name" directly under "module": { ... }.
     * Use a depth-aware scan to skip nested objects/arrays. */
    const char *mod = strstr(json, "\"module\"");
    if (mod) {
        char val[256];

        /* Find "name" within the top-level module object (depth 1) */
        int depth = 0;
        const char *p = mod;
        while (*p) {
            if (*p == '{' || *p == '[') depth++;
            else if (*p == '}' || *p == ']') { depth--; if (depth <= 0) break; }
            else if (depth == 1 && strncmp(p, "\"name\"", 6) == 0) {
                p += 6;
                while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
                if (*p == ':') {
                    p++;
                    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
                    if (*p == '"') {
                        p++;
                        size_t i = 0;
                        while (*p && *p != '"' && i < sizeof(val) - 1) {
                            if (*p == '\\' && *(p+1)) p++;
                            val[i++] = *p++;
                        }
                        val[i] = '\0';
                        if (i > 0) ename.moduleName = strdup(val);
                    }
                }
                break;
            }
            p++;
        }

        /* Find "mainElement" at depth 1 */
        if (json_get_string(mod, "mainElement", val, sizeof(val)))
            ename.abilityName = strdup(val);
    }

    return ename;
}

/* ── CompatibleDeviceType ────────────────────────────────────────────── */

char *OH_NativeBundle_GetCompatibleDeviceType()
{
    const char *json = load_module_json();
    if (!json) return strdup("");

    /* deviceTypes from module scope */
    const char *mod = strstr(json, "\"module\"");
    if (mod) {
        char types[512];
        if (json_get_string_array(mod, "deviceTypes", types, sizeof(types)))
            return strdup(types);
    }

    /* Default */
    return strdup("phone");
}

/* ── IsDebugMode ─────────────────────────────────────────────────────── */

bool OH_NativeBundle_IsDebugMode(bool *isDebugMode)
{
    if (!isDebugMode) return false;

    const char *json = load_module_json();
    if (!json) { *isDebugMode = false; return true; }

    const char *app = strstr(json, "\"app\"");
    if (app) {
        /* Try "debug" first, then "buildMode" */
        if (json_get_bool(app, "debug", isDebugMode))
            return true;
        char mode[32];
        if (json_get_string(app, "buildMode", mode, sizeof(mode))) {
            *isDebugMode = (strcmp(mode, "debug") == 0 || strcmp(mode, "release") != 0);
            return true;
        }
    }

    *isDebugMode = false;
    return true;
}

/* ── ModuleMetadata ──────────────────────────────────────────────────── */

OH_NativeBundle_ModuleMetadata *OH_NativeBundle_GetModuleMetadata(size_t *size)
{
    if (size) *size = 0;
    return NULL;
}

/* ── AbilityResourceInfo ─────────────────────────────────────────────── */

BundleManager_ErrorCode OH_NativeBundle_GetAbilityResourceInfo(
    char *fileType,
    OH_NativeBundle_AbilityResourceInfo **abilityResourceInfo,
    size_t *size)
{
    (void)fileType;
    (void)abilityResourceInfo;
    (void)size;
    /* HOA doesn't support inter-app ability queries */
    return BUNDLE_MANAGER_ERROR_CODE_PERMISSION_DENIED;
}

BundleManager_ErrorCode OH_NativeBundle_GetBundleName(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, char **bundleName)
{
    (void)abilityResourceInfo;
    (void)bundleName;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

BundleManager_ErrorCode OH_NativeBundle_GetModuleName(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, char **moduleName)
{
    (void)abilityResourceInfo;
    (void)moduleName;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

BundleManager_ErrorCode OH_NativeBundle_GetAbilityName(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, char **abilityName)
{
    (void)abilityResourceInfo;
    (void)abilityName;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

BundleManager_ErrorCode OH_NativeBundle_GetLabel(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, char **label)
{
    (void)abilityResourceInfo;
    (void)label;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

BundleManager_ErrorCode OH_NativeBundle_GetAppIndex(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, int *appIndex)
{
    (void)abilityResourceInfo;
    (void)appIndex;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

BundleManager_ErrorCode OH_NativeBundle_CheckDefaultApp(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, bool *isDefault)
{
    (void)abilityResourceInfo;
    (void)isDefault;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

BundleManager_ErrorCode OH_AbilityResourceInfo_Destroy(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo, size_t count)
{
    (void)abilityResourceInfo;
    (void)count;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}

int OH_NativeBundle_GetSize()
{
    return (int)sizeof(void*); /* approximate */
}

BundleManager_ErrorCode OH_NativeBundle_GetDrawableDescriptor(
    OH_NativeBundle_AbilityResourceInfo *abilityResourceInfo,
    ArkUI_DrawableDescriptor **drawableIcon)
{
    (void)abilityResourceInfo;
    (void)drawableIcon;
    return BUNDLE_MANAGER_ERROR_CODE_PARAM_INVALID;
}
