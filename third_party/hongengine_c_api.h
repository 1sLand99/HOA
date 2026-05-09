#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Error codes for HongEngine operations
typedef enum {
    HONGENGINE_OK = 0,
    HONGENGINE_ERROR_NULL_STATE,
    HONGENGINE_ERROR_VM_ALREADY_INIT,
    HONGENGINE_ERROR_VM_CREATE,
    HONGENGINE_ERROR_FILE
} HongEngineError;

// ETS module execution result
typedef struct {
    int success;                // 1 if execution succeeded
    int exit_code;              // exit code from the module
    const char* error;          // error message (or NULL)
} HongEtsResult;

// Opaque handle to the engine state
typedef struct HongEngineState HongEngineState;

// ABC validation result
typedef struct {
    int valid;                  // 1 if valid
    const char* error;          // error message (or NULL)
    const char* magic;          // magic bytes string (e.g. "PANDA")
    const char* version;        // version string (e.g. "11.0.0.0")
    uint32_t file_size;         // total file size
    uint32_t num_classes;       // number of classes
    uint32_t header_size;       // size of file header
} HongAbcInfo;

// Create engine state
HongEngineState* hongengine_state_create(const char* files_dir);

// Destroy engine state
void hongengine_state_destroy(HongEngineState* state);

// Open and validate an ABC file at the given path.
// Returns ABC info on success; info.valid == 0 on failure.
HongAbcInfo hongengine_validate_abc(const char* abc_path);

// Get stored files_dir from state
const char* hongengine_state_get_files_dir(const HongEngineState* state);

// ---- ETS VM lifecycle ----

// Create an ETS runtime within the engine state.
// app_abc_path: path to the application .abc file (required).
// stdlib_abc_path: path to etsstdlib.abc (optional, NULL for snapshot mode).
// Returns HONGENGINE_OK on success.
HongEngineError hongengine_ets_create_runtime(HongEngineState* state,
                                              const char* app_abc_path,
                                              const char* stdlib_abc_path);

// Destroy the ETS runtime within the engine state.
// Returns HONGENGINE_OK on success.
HongEngineError hongengine_ets_destroy_runtime(HongEngineState* state);

// Execute an ETS module entry point.
// module_name: the ability/page name (default entry "_GLOBAL::main" if empty).
// entry_point: if non-NULL, used as the full entry point string
//              (e.g. "entry.ETSGLOBAL::func_main_0"), bypassing the default.
// Returns result with success flag and exit code.
HongEtsResult hongengine_ets_execute_module(HongEngineState* state,
                                            const char* module_name,
                                            const char* entry_point);

// Get the last error message stored in the engine state.
// Returns a pointer to a null-terminated string, or NULL if no error.
const char* hongengine_ets_get_last_error(const HongEngineState* state);

#ifdef __cplusplus
}
#endif
