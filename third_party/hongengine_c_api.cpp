#include "hongengine_c_api.h"

#include <string>
#include <memory>
#include <cstring>

#include <file.h>
#include <file_format_version.h>
#include <file_items.h>

#include "plugins/ets/runtime/ets_vm_api.h"
#include "runtime/include/runtime.h"
#include "runtime/include/runtime_options.h"
#include "generated/base_options.h"

using namespace ark::panda_file;

struct HongEngineState {
    std::string filesDir;
    bool etsVmInitialized = false;
    std::string lastError;
};

HongEngineState* hongengine_state_create(const char* files_dir) {
    auto* state = new HongEngineState();
    state->filesDir = files_dir ? files_dir : "";
    return state;
}

void hongengine_state_destroy(HongEngineState* state) {
    if (state) {
        hongengine_ets_destroy_runtime(state);
        delete state;
    }
}

const char* hongengine_state_get_files_dir(const HongEngineState* state) {
    return state->filesDir.c_str();
}

HongAbcInfo hongengine_validate_abc(const char* abc_path) {
    HongAbcInfo info = {};
    info.valid = 0;

    if (!abc_path) {
        info.error = "NULL path";
        return info;
    }

    // Open via libpandafile
    auto pf = File::Open(abc_path);
    if (!pf) {
        info.error = "File::Open failed";
        return info;
    }

    const auto* header = pf->GetHeader();

    // Validate magic
    constexpr uint8_t expected[] = {'P', 'A', 'N', 'D', 'A', 0, 0, 0};
    if (std::memcmp(header->magic.data(), expected, File::MAGIC_SIZE) != 0) {
        info.error = "Invalid magic bytes";
        return info;
    }

    // Success - fill in info
    info.valid = 1;
    info.error = nullptr;

    // Use thread_local for thread safety
    thread_local std::string s_magic;
    thread_local std::string s_version;

    char buf[16];
    snprintf(buf, sizeof(buf), "%c%c%c%c%c",
             header->magic[0], header->magic[1],
             header->magic[2], header->magic[3],
             header->magic[4]);
    s_magic = buf;
    info.magic = s_magic.c_str();

    s_version = GetVersion(header->version);
    info.version = s_version.c_str();

    info.file_size = header->fileSize;
    info.num_classes = header->numClasses;
    info.header_size = static_cast<uint32_t>(sizeof(File::Header));

    return info;
}

// ---- ETS VM lifecycle ----

HongEngineError hongengine_ets_create_runtime(HongEngineState* state,
                                              const char* app_abc_path,
                                              const char* stdlib_abc_path) {
    if (!state) {
        return HONGENGINE_ERROR_NULL_STATE;
    }
    if (state->etsVmInitialized) {
        return HONGENGINE_ERROR_VM_ALREADY_INIT;
    }
    if (!app_abc_path) {
        state->lastError = "NULL app_abc_path";
        return HONGENGINE_ERROR_FILE;
    }

    std::string appAbc = app_abc_path;
    std::string icuDataDir = state->filesDir + "/icu";

    auto addOpts = [&appAbc, stdlib_abc_path, &icuDataDir](ark::base_options::Options *baseOptions,
                                              ark::RuntimeOptions *runtimeOptions) {
        baseOptions->SetLogLevel("info");

        if (stdlib_abc_path != nullptr) {
            // Full mode: load stdlib as boot panda file
            std::string stdlibAbc = stdlib_abc_path;
            runtimeOptions->SetBootPandaFiles({stdlibAbc, appAbc});
        } else {
            // Snapshot mode: skip boot panda file loading and intrinsics init
            runtimeOptions->SetForSnapShotStart();
        }

        runtimeOptions->SetPandaFiles({appAbc});
        runtimeOptions->SetGcType("g1-gc");
        runtimeOptions->SetCoroutineImpl("stackful");
        runtimeOptions->SetIcuDataPath(icuDataDir);

        return true;
    };

    if (!ark::ets::CreateRuntime(addOpts)) {
        state->lastError = "ark::ets::CreateRuntime failed";
        return HONGENGINE_ERROR_VM_CREATE;
    }

    state->etsVmInitialized = true;
    state->lastError.clear();
    return HONGENGINE_OK;
}

HongEngineError hongengine_ets_destroy_runtime(HongEngineState* state) {
    if (!state) {
        return HONGENGINE_ERROR_NULL_STATE;
    }
    if (!state->etsVmInitialized) {
        return HONGENGINE_OK;
    }

    ark::ets::DestroyRuntime();
    state->etsVmInitialized = false;
    return HONGENGINE_OK;
}

HongEtsResult hongengine_ets_execute_module(HongEngineState* state,
                                            const char* module_name,
                                            const char* entry_point) {
    HongEtsResult result = {};
    result.success = 0;
    result.exit_code = 1;

    if (!state) {
        result.error = "NULL state";
        return result;
    }
    if (!state->etsVmInitialized) {
        result.error = "ETS VM not initialized";
        return result;
    }

    // When entry_point is provided, call Runtime::ExecutePandaFile directly.
    // This bypasses ExecuteModule's hardcoded "_GLOBAL::main" / ".ETSGLOBAL::main",
    // allowing ES module entry points like "entry.ETSGLOBAL::func_main_0".
    if (entry_point != nullptr && entry_point[0] != '\0') {
        auto *runtime = ark::Runtime::GetCurrent();
        auto pfPath = runtime->GetPandaFiles()[0];
        LOG(INFO, RUNTIME) << "ExecuteModule custom: '" << pfPath
                           << "' entryPoint='" << entry_point << "'";
        auto res = runtime->ExecutePandaFile(pfPath, std::string_view(entry_point), {});
        result.success = res ? 1 : 0;
        result.exit_code = res ? res.Value() : 1;
        if (!res) {
            state->lastError = "ExecutePandaFile failed for custom entry point";
            result.error = state->lastError.c_str();
        }
        return result;
    }

    // Legacy path: use ExecuteModule with default entry point logic
    std::string_view name = (module_name != nullptr) ? std::string_view(module_name)
                                                     : std::string_view();
    auto [ok, exitCode] = ark::ets::ExecuteModule(name);

    result.success = ok ? 1 : 0;
    result.exit_code = exitCode;
    if (!ok) {
        state->lastError = "ark::ets::ExecuteModule failed";
        result.error = state->lastError.c_str();
    } else {
        state->lastError.clear();
        result.error = nullptr;
    }
    return result;
}

const char* hongengine_ets_get_last_error(const HongEngineState* state) {
    if (!state || state->lastError.empty()) {
        return nullptr;
    }
    return state->lastError.c_str();
}
