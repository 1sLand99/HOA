/*
 * libsettings_napi.z.so stub for HOA.
 *
 * Registers the "@ohos.settings" NAPI module so HAPs that import
 * settings from '@ohos.settings' get a functional key-value store
 * backed by an in-memory hash table.
 *
 * The module is registered via __attribute__((constructor)) when
 * libsettings_napi.z.so is loaded (explicitly via System.loadLibrary
 * in HoaApplication.onCreate, after libarkui_android.so is available).
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define NAPI_AUTO_LENGTH ((size_t)-1)

/* ── NAPI types (from arkcompiler/ets_runtime/ecmascript/napi/include) ── */
typedef struct napi_env__        *napi_env;
typedef struct napi_value__      *napi_value;
typedef struct napi_ref__        *napi_ref;
typedef struct napi_handle_scope__ *napi_handle_scope;
typedef struct napi_escapable_handle_scope__ *napi_escapable_handle_scope;
typedef struct napi_callback_info__ *napi_callback_info;
typedef napi_value (*napi_callback)(napi_env, napi_callback_info);
typedef napi_value (*napi_addon_register_func)(napi_env, napi_value);

typedef enum {
    napi_ok,
    napi_invalid_arg,
    napi_object_expected,
    napi_string_expected,
    napi_number_expected,
    napi_function_expected,
    napi_boolean_expected,
    napi_generic_failure,
    napi_pending_exception,
    napi_cancelled,
    napi_escape_called_twice,
    napi_handle_scope_mismatch,
    napi_callback_scope_mismatch,
    napi_queue_full,
    napi_closing,
    napi_bigint_expected,
    napi_date_expected,
    napi_arraybuffer_expected,
    napi_detachable_arraybuffer_expected,
    napi_would_deadlock,
} napi_status;

/* ── Module descriptor ── */
typedef struct napi_module {
    int nm_version;
    unsigned int nm_flags;
    const char *nm_filename;
    napi_addon_register_func nm_register_func;
    const char *nm_modname;
    void *nm_priv;
    void *reserved[4];
} napi_module;

/* ── Forward-declare NAPI functions we use ── */
extern void napi_module_register(napi_module *mod);

/* The full NAPI is linked from libarkui_android.so */
napi_status napi_create_string_utf8(napi_env, const char *, size_t, napi_value *);
napi_status napi_create_function(napi_env, const char *, size_t, napi_callback, void *, napi_value *);
napi_status napi_set_named_property(napi_env, napi_value, const char *, napi_value);
napi_status napi_get_cb_info(napi_env, napi_callback_info, size_t *, napi_value *, napi_value *, void **);
napi_status napi_get_value_string_utf8(napi_env, napi_value, char *, size_t, size_t *);
napi_status napi_create_object(napi_env, napi_value *);
napi_status napi_create_string_latin1(napi_env, const char *, size_t, napi_value *);
napi_status napi_get_boolean(napi_env, int, napi_value *);

/* ── Simple in-memory key-value store ── */
#define MAX_SETTINGS 64
#define MAX_KEY_LEN  128
#define MAX_VAL_LEN  512

static struct {
    char key[MAX_KEY_LEN];
    char val[MAX_VAL_LEN];
} settings_store[MAX_SETTINGS];
static int settings_count = 0;

static char *find_setting(const char *key) {
    for (int i = 0; i < settings_count; i++) {
        if (strcmp(settings_store[i].key, key) == 0)
            return settings_store[i].val;
    }
    return NULL;
}

static void set_setting(const char *key, const char *val) {
    for (int i = 0; i < settings_count; i++) {
        if (strcmp(settings_store[i].key, key) == 0) {
            strncpy(settings_store[i].val, val, MAX_VAL_LEN - 1);
            settings_store[i].val[MAX_VAL_LEN - 1] = '\0';
            return;
        }
    }
    if (settings_count < MAX_SETTINGS) {
        strncpy(settings_store[settings_count].key, key, MAX_KEY_LEN - 1);
        strncpy(settings_store[settings_count].val, val, MAX_VAL_LEN - 1);
        settings_store[settings_count].key[MAX_KEY_LEN - 1] = '\0';
        settings_store[settings_count].val[MAX_VAL_LEN - 1] = '\0';
        settings_count++;
    }
}

/* ── getValueSync(context, name, defValue) -> string ── */
static napi_value GetValueSync(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3] = {0};
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);

    char name[MAX_KEY_LEN] = {0};
    char defv[MAX_VAL_LEN] = {0};

    if (argc >= 2)
        napi_get_value_string_utf8(env, args[1], name, MAX_KEY_LEN, NULL);
    if (argc >= 3)
        napi_get_value_string_utf8(env, args[2], defv, MAX_VAL_LEN, NULL);

    char *val = find_setting(name);
    napi_value result;
    napi_create_string_utf8(env, val ? val : defv, NAPI_AUTO_LENGTH, &result);
    return result;
}

/* ── setValueSync(context, name, value) -> boolean ── */
static napi_value SetValueSync(napi_env env, napi_callback_info info) {
    (void)env;
    size_t argc = 3;
    napi_value args[3] = {0};
    napi_get_cb_info(env, info, &argc, args, NULL, NULL);

    char name[MAX_KEY_LEN] = {0};
    char value[MAX_VAL_LEN] = {0};

    if (argc >= 2)
        napi_get_value_string_utf8(env, args[1], name, MAX_KEY_LEN, NULL);
    if (argc >= 3)
        napi_get_value_string_utf8(env, args[2], value, MAX_VAL_LEN, NULL);

    if (name[0] == '\0') {
        napi_value result;
        napi_get_boolean(env, 0, &result);
        return result;
    }
    set_setting(name, value);

    napi_value result;
    napi_get_boolean(env, 1, &result);
    return result;
}

/* ── Module init: register exported functions ── */
static napi_value SettingsModuleInit(napi_env env, napi_value exports) {
    napi_value fn;

    napi_create_function(env, "getValueSync", NAPI_AUTO_LENGTH, GetValueSync, NULL, &fn);
    napi_set_named_property(env, exports, "getValueSync", fn);

    napi_create_function(env, "setValueSync", NAPI_AUTO_LENGTH, SetValueSync, NULL, &fn);
    napi_set_named_property(env, exports, "setValueSync", fn);

    /* Export namespace constants — string-key constants that HAPs
     * reference as settings.display.SCREEN_BRIGHTNESS_STATUS etc. */
    napi_value display_ns, date_ns, domain_ns;

    napi_create_object(env, &display_ns);
    napi_create_string_utf8(env, "FONT_SCALE", NAPI_AUTO_LENGTH, &fn);
    napi_set_named_property(env, display_ns, "FONT_SCALE", fn);
    napi_create_string_utf8(env, "SCREEN_BRIGHTNESS_STATUS", NAPI_AUTO_LENGTH, &fn);
    napi_set_named_property(env, display_ns, "SCREEN_BRIGHTNESS_STATUS", fn);
    napi_set_named_property(env, exports, "display", display_ns);

    napi_create_object(env, &date_ns);
    napi_create_string_utf8(env, "DATE_FORMAT", NAPI_AUTO_LENGTH, &fn);
    napi_set_named_property(env, date_ns, "DATE_FORMAT", fn);
    napi_create_string_utf8(env, "TIME_FORMAT", NAPI_AUTO_LENGTH, &fn);
    napi_set_named_property(env, date_ns, "TIME_FORMAT", fn);
    napi_set_named_property(env, exports, "date", date_ns);

    napi_create_object(env, &domain_ns);
    napi_create_string_utf8(env, "DEVICE_SHARED", NAPI_AUTO_LENGTH, &fn);
    napi_set_named_property(env, domain_ns, "DEVICE_SHARED", fn);
    napi_create_string_utf8(env, "USER_PROPERTY", NAPI_AUTO_LENGTH, &fn);
    napi_set_named_property(env, domain_ns, "USER_PROPERTY", fn);
    napi_set_named_property(env, exports, "domainName", domain_ns);

    return exports;
}

/* ── Auto-register ── */
static napi_module _settings_module = {
    .nm_version      = 1,
    .nm_flags        = 0,
    .nm_filename     = "module/libsettings_napi.z.so/settings.js",
    .nm_register_func = SettingsModuleInit,
    .nm_modname      = "settings",
    .nm_priv         = NULL,
    .reserved        = {0},
};

__attribute__((constructor)) static void NAPI_settings_AutoRegister(void) {
    napi_module_register(&_settings_module);
}
