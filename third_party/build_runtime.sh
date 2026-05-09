#!/usr/bin/env bash
# ==============================================================================
# HOA Runtime: Cross-compile OpenHarmony ArkCompiler for Android
#
# Usage:
#   ./third_party/build_runtime.sh             # Full build (codegen + compile + install)
#   ./third_party/build_runtime.sh codegen     # Only generate code-gen files
#   ./third_party/build_runtime.sh compile     # Only cross-compile (needs codegen first)
#   ./third_party/build_runtime.sh install     # Only copy .so files to jniLibs
#   ./third_party/build_runtime.sh clean       # Remove build artifacts
#
# Requirements:
#   - git submodule update --init
#   - Ruby >= 3.0 (codegen only, pre-generated output also available)
#   - Android NDK 28.2+ at /apps/android/ndk/28.2.13676358
#   - CMake >= 3.10
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
SRC_ROOT="${SCRIPT_DIR}/arkcompiler_runtime_core/static_core"
THIRD_PARTY="${SRC_ROOT}/third_party"
BUILD_GEN="${SCRIPT_DIR}/build_gen"
BUILD_ANDROID="${SCRIPT_DIR}/build_android"

# NDK path (adjust for your environment)
NDK="/apps/android/ndk/28.2.13676358"
TOOLCHAIN="${NDK}/build/cmake/android.toolchain.cmake"

JNILIBS_DIR="${SCRIPT_DIR}/../app/src/main/jniLibs/arm64-v8a"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# -----------------------------------------------------------------------------
# Preflight checks
# -----------------------------------------------------------------------------
do_preflight() {
    local ok=true

    if [ ! -d "${SRC_ROOT}" ]; then
        log_error "Source not found: ${SRC_ROOT}"
        log_error "Did you run 'git submodule update --init'?"
        ok=false
    fi

    if [ ! -d "${SRC_ROOT}/third_party/utils_native" ]; then
        log_error "Third-party deps not found under ${SRC_ROOT}/third_party/"
        log_error "The arkcompiler_runtime_core submodule may be incomplete."
        ok=false
    fi

    if ! command -v ruby &>/dev/null; then
        log_error "Ruby not found. Install it: sudo apt install ruby"
        ok=false
    fi

    if [ ! -f "${TOOLCHAIN}" ]; then
        log_error "NDK toolchain not found: ${TOOLCHAIN}"
        log_error "Install Android NDK or update NDK path in this script."
        ok=false
    fi

    if [ "$ok" = false ]; then
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# Step 0: Apply Android portability patches to submodule source
# -----------------------------------------------------------------------------
do_patch() {
    local patch_file="${SCRIPT_DIR}/android_port.patch"
    if [ ! -f "${patch_file}" ]; then
        log_error "Patch file not found: ${patch_file}"
        exit 1
    fi
    log_info "Applying Android portability patches..."
    # Check if already applied (look for a marker in a patched file)
    if grep -q "android_port" "${SRC_ROOT}/compiler/optimizer/ir/runtime_interface.h" 2>/dev/null; then
        log_info "  Patches already applied, skipping"
        return 0
    fi
    patch -p0 -d "${SCRIPT_DIR}" < "${patch_file}" || {
        log_error "Patch application failed"
        exit 1
    }
    # Fix inst_templates.yaml: replace ets-only file with symlink to full template
    # (Patch can't represent symlinks, so handle it explicitly)
    local its="${SRC_ROOT}/compiler/optimizer/templates/inst_templates.yaml"
    if [ -f "${its}" ] && [ ! -L "${its}" ]; then
        mv "${its}" "${its}.bak"
        ln -sf ../ir_builder/inst_templates.yaml "${its}"
        log_info "  inst_templates.yaml -> symlink to ir_builder/inst_templates.yaml"
    fi

    # Add marker to patched files so we can detect re-application
    for f in \
        compiler/optimizer/code_generator/encode_visitor.cpp \
        compiler/optimizer/ir/runtime_interface.h \
        compiler/optimizer/ir_builder/inst_builder.h \
        plugins/ets/compiler/optimizer/ets_intrinsics_peephole.cpp \
        plugins/ets/runtime/ets_class_linker_extension.cpp \
        plugins/ets/runtime/ets_vm_api.cpp \
        runtime/arch/asm_support.cpp \
        verification/verifier_messages_data.cpp; do
        echo "// android_port: applied" >> "${SRC_ROOT}/${f}" 2>/dev/null || true
    done
    log_info "  Patches applied OK"
}

# -----------------------------------------------------------------------------
# Step 1: Generate code-gen files (native Ruby/ERB)
# NOTE: Stage 5e (inst_builder_gen.cpp) fails with Ruby >= 3.3.
# If build_gen/ already has pre-generated output, codegen is skipped.
# To regenerate from scratch, use Ruby < 3.3 or fix inst_builder_gen.cpp.erb.
# -----------------------------------------------------------------------------
do_codegen() {
    # Skip if build_gen already populated (check file size, not just existence —
    # a failed previous codegen run may leave empty stub files)
    if [ -s "${BUILD_GEN}/isa/isa.yaml" ] && [ -s "${BUILD_GEN}/compiler/inst_builder_gen.cpp" ]; then
        log_info "=== Code Generation: pre-generated output found, skipping ==="
        log_info "    (use './build_runtime.sh codegen' to force regeneration)"
        return 0
    fi
    log_info "=== Code Generation ==="

    mkdir -p "${BUILD_GEN}/isa"
    mkdir -p "${BUILD_GEN}/libpandafile/include/tests"
    mkdir -p "${BUILD_GEN}/libpandabase/generated"
    mkdir -p "${BUILD_GEN}/panda_gen_options/generated"
    mkdir -p "${BUILD_GEN}/generated"

    GEN_OUT="${BUILD_GEN}/libpandafile/include"
    GEN_PBASE="${BUILD_GEN}/libpandabase/generated"
    GEN_OPTS="${BUILD_GEN}/panda_gen_options/generated"
    GEN_BASE="${BUILD_GEN}/generated"

    # Stage 0: Combine ISA YAML
    log_info "Stage 0: Combining ISA YAML..."
    ruby "${SRC_ROOT}/isa/combine.rb" \
        -d "${SRC_ROOT}/isa/isa.yaml","${SRC_ROOT}/plugins/ets/isa/isa.yaml" \
        -o "${BUILD_GEN}/isa/isa.yaml"

    # Stage 1: ISA-based templates for libpandafile (6 files)
    log_info "Stage 1: Generating ISA templates..."
    for tpl in bytecode_instruction_enum_gen.h.erb \
               bytecode_instruction-inl_gen.h.erb \
               bytecode_emitter_def_gen.h.erb \
               bytecode_emitter_gen.h.erb \
               file_format_version.h.erb \
               tests/bytecode_emitter_tests_gen.h.erb; do
        outfile="${tpl%.erb}"
        log_info "  -> ${outfile}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/isa/isa.yaml" \
            --api "${SRC_ROOT}/isa/isapi.rb" \
            --require "${SRC_ROOT}/libpandafile/pandafile_isapi.rb" \
            --template "${SRC_ROOT}/libpandafile/templates/${tpl}" \
            --output "${GEN_OUT}/${outfile}"
    done

    # Stage 2: type.h
    log_info "Stage 2: Generating type.h..."
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/libpandafile/types.yaml" \
        --api "${SRC_ROOT}/libpandafile/types.rb" \
        --template "${SRC_ROOT}/libpandafile/templates/type.h.erb" \
        --output "${GEN_OUT}/type.h"

    # Stage 3: Plugin options merge
    log_info "Stage 3: Merging plugin options..."
    bash "${SRC_ROOT}/templates/concat_yamls.sh" \
        "${BUILD_GEN}/plugin_options.yaml" \
        "${SRC_ROOT}/templates/plugin_options.yaml" \
        "${SRC_ROOT}/plugins/ets/ets_plugin_options.yaml"

    # Stage 3a: file_items_gen.inc + source_lang_enum.h
    log_info "Stage 3a: Plugin option templates..."
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/libpandafile/templates/file_items_gen.inc.erb" \
        --output "${GEN_OUT}/file_items_gen.inc"

    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/libpandafile/templates/source_lang_enum.h.erb" \
        --output "${GEN_OUT}/source_lang_enum.h"

    # Stage 4: libpandabase generated files
    log_info "Stage 4: Generating libpandabase files..."

    # 4a: logger.yaml (merged)
    ruby "${SRC_ROOT}/libpandabase/templates/logger_gen.rb" \
        -d "${SRC_ROOT}/libpandabase/templates/logger.yaml" \
        -p "${BUILD_GEN}/plugin_options.yaml" \
        -o "${BUILD_GEN}/libpandabase/logger.yaml"

    # 4b: logger_enum_gen.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --template "${SRC_ROOT}/libpandabase/templates/logger_enum_gen.h.erb" \
        --data "${BUILD_GEN}/libpandabase/logger.yaml" \
        --api "${SRC_ROOT}/libpandabase/templates/logger.rb" \
        --output "${GEN_PBASE}/logger_enum_gen.h"

    # 4c: logger_impl_gen.inc
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --template "${SRC_ROOT}/libpandabase/templates/logger_impl_gen.inc.erb" \
        --data "${BUILD_GEN}/libpandabase/logger.yaml" \
        --api "${SRC_ROOT}/libpandabase/templates/logger.rb" \
        --output "${GEN_PBASE}/logger_impl_gen.inc"

    # 4d: source_language.h (libpandabase version)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --template "${SRC_ROOT}/libpandabase/templates/source_language.h.erb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --output "${GEN_PBASE}/source_language.h"

    # 4e: plugins_regmasks.inl
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --template "${SRC_ROOT}/libpandabase/templates/plugins_regmasks.inl.erb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --output "${GEN_PBASE}/plugins_regmasks.inl"

    # 4f: events_gen.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --template "${SRC_ROOT}/libpandabase/events/events_gen.h.erb" \
        --data "${SRC_ROOT}/libpandabase/events/events.yaml" \
        --api "${SRC_ROOT}/libpandabase/events/events.rb" \
        --output "${GEN_BASE}/events_gen.h"

    # 4g: base_options.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --template "${SRC_ROOT}/templates/options/options.h.erb" \
        --data "${SRC_ROOT}/libpandabase/options.yaml" \
        --api "${SRC_ROOT}/templates/common.rb" \
        --output "${GEN_OPTS}/base_options.h"

    # 4h: coherency_line_size.h (copy from common)
    cp "${SRC_ROOT}/platforms/common/libpandabase/coherency_line_size.h" \
       "${GEN_BASE}/coherency_line_size.h"

    # 4i: ark_version.h
    GIT_HASH=$(git -C "${SRC_ROOT}" rev-parse --short HEAD 2>/dev/null || echo "unknown")
    GIT_DATE=$(git -C "${SRC_ROOT}" log -1 --format=%cd --date=short 2>/dev/null || date +%Y-%m-%d)
    sed "s/@ARK_VERSION_GIT_HASH@/${GIT_HASH}/g; s/@ARK_VERSION_DATE@/${GIT_DATE}/g" \
        "${SRC_ROOT}/libpandabase/templates/ark_version.h.in" > "${GEN_BASE}/ark_version.h"

    # Stage 5: Compiler generated headers
    log_info "Stage 5: Generating compiler headers..."
    mkdir -p "${BUILD_GEN}/compiler"

    # 5e: arch_info_gen.h + opcodes.h + inst_flags.inl (from instructions.yaml)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/optimizer/ir/instructions.yaml" \
        --api "${SRC_ROOT}/compiler/optimizer/templates/instructions.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/arch_info_gen.h.erb" \
        --output "${BUILD_GEN}/compiler/arch_info_gen.h"

    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/optimizer/ir/instructions.yaml" \
        --api "${SRC_ROOT}/compiler/optimizer/templates/instructions.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/opcodes.h.erb" \
        --output "${BUILD_GEN}/compiler/opcodes.h"

    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/optimizer/ir/instructions.yaml" \
        --api "${SRC_ROOT}/compiler/optimizer/templates/instructions.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/inst_flags.inl.erb" \
        --output "${BUILD_GEN}/compiler/inst_flags.inl"

    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/optimizer/ir/instructions.yaml" \
        --api "${SRC_ROOT}/compiler/optimizer/templates/instructions.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/savestate_optimization_call_visitors.inl.erb" \
        --output "${BUILD_GEN}/compiler/savestate_optimization_call_visitors.inl"

    # inst_checker_gen.h + codegen_arm64_gen.inc (also from instructions.yaml)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/optimizer/ir/instructions.yaml" \
        --api "${SRC_ROOT}/compiler/optimizer/templates/instructions.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/inst_checker_gen.h.erb" \
        --output "${BUILD_GEN}/compiler/inst_checker_gen.h"

    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/optimizer/ir/instructions.yaml" \
        --api "${SRC_ROOT}/compiler/optimizer/templates/instructions.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/codegen_arm64_gen.inc.erb" \
        --output "${BUILD_GEN}/compiler/codegen_arm64_gen.inc"

    # 5a: compiler_options_gen.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/compiler.yaml" \
        --api "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/templates/options/options.h.erb" \
        --output "${BUILD_GEN}/compiler/compiler_options_gen.h"

    # 5b: cpu_features.inc
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/compiler.yaml" \
        --api "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/templates/cpu_features.inc.erb" \
        --output "${BUILD_GEN}/compiler/cpu_features.inc"

    # 5c: compiler_events_gen.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/compiler.yaml" \
        --api "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/templates/events/events.h.erb" \
        --output "${BUILD_GEN}/compiler/compiler_events_gen.h"

    # 5d: compiler_logger_components.inc
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/compiler/compiler.yaml" \
        --api "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/templates/logger_components/logger_components.inc.erb" \
        --output "${BUILD_GEN}/compiler/compiler_logger_components.inc"

    # 5e: inst_builder_gen.cpp (required for compile; merges ETS inst templates)
    log_info "  5e: inst_builder_gen.cpp..."
    # The ERB template loads inst_templates.yaml from disk.
    # Ensure ETS inst templates are merged (idempotent check).
    if ! grep -q "^  ets:" "${SRC_ROOT}/compiler/optimizer/templates/inst_templates.yaml" 2>/dev/null; then
        cat "${SRC_ROOT}/plugins/ets/compiler/optimizer/ir_builder/ets_inst_templates.yaml" \
            >> "${SRC_ROOT}/compiler/optimizer/templates/inst_templates.yaml"
    fi
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/isa/isa.yaml" \
        --api "${SRC_ROOT}/isa/isapi.rb" \
        --require "${SRC_ROOT}/assembler/asm_isapi.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/inst_builder_gen.cpp.erb" \
        --output "${BUILD_GEN}/compiler/inst_builder_gen.cpp"

    # Stage 6: Runtime options + ISA templates + bridge + profiling + ICU
    log_info "Stage 6: Generating runtime options..."
    mkdir -p "${BUILD_GEN}/runtime"
    GEN_INC="${BUILD_GEN}/runtime/include"
    mkdir -p "${GEN_INC}"

    # 6a: Merge runtime options
    ruby "${SRC_ROOT}/templates/merge.rb" \
        -d "${SRC_ROOT}/runtime/options.yaml","${SRC_ROOT}/plugins/ets/runtime_options.yaml" \
        -o "${BUILD_GEN}/runtime/merged_runtime_options.yaml"

    # 6b: Generate runtime_options_gen.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/runtime/merged_runtime_options.yaml" \
        --api "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/templates/options/options.h.erb" \
        --output "${GEN_BASE}/runtime_options_gen.h"

    # 6c: Runtime ISA templates (interpreter needs these)
    log_info "  6c: isa_constants_gen.h + interpreter-inl_gen.h"
    for tpl in isa_constants_gen.h.erb interpreter-inl_gen.h.erb; do
        outfile="${tpl%.erb}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/isa/isa.yaml" \
            --api "${SRC_ROOT}/isa/isapi.rb" \
            --template "${SRC_ROOT}/runtime/interpreter/templates/${tpl}" \
            --output "${GEN_INC}/${outfile}"
    done

    # 6c2: Merge entrypoints YAMLs (needed by 6d before 8e runs it again)
    log_info "  6c2: Merging entrypoints YAMLs..."
    bash "${SRC_ROOT}/templates/concat_yamls.sh" \
        "${BUILD_GEN}/runtime/entrypoints_merged.yaml" \
        "${SRC_ROOT}/runtime/entrypoints/entrypoints.yaml" \
        "${SRC_ROOT}/plugins/ets/runtime/ets_entrypoints.yaml"

    # 6d: entrypoints_gen.S (needed by bridge assembly)
    log_info "  6d: entrypoints_gen.S"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/runtime/entrypoints_merged.yaml" \
        --api "${SRC_ROOT}/runtime/entrypoints/entrypoints.rb" \
        --template "${SRC_ROOT}/runtime/entrypoints/entrypoints_gen.S.erb" \
        --output "${GEN_INC}/entrypoints_gen.S"

    # 6e: bridge_dispatch .S for aarch64
    log_info "  6e: bridge_dispatch(_dyn)_aarch64.S"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/isa/isa.yaml" \
        --api "${SRC_ROOT}/isa/isapi.rb" \
        --require "${SRC_ROOT}/runtime/templates/bridge_helpers_aarch64.rb","${SRC_ROOT}/runtime/templates/bridge_helpers_common.rb","${SRC_ROOT}/runtime/templates/bridge_helpers_static.rb" \
        --template "${SRC_ROOT}/runtime/templates/bridge_dispatch.S.erb" \
        --output "${GEN_INC}/bridge_dispatch_aarch64.S"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/isa/isa.yaml" \
        --api "${SRC_ROOT}/isa/isapi.rb" \
        --require "${SRC_ROOT}/runtime/templates/bridge_helpers_aarch64.rb","${SRC_ROOT}/runtime/templates/bridge_helpers_common.rb","${SRC_ROOT}/runtime/templates/bridge_helpers_dynamic.rb" \
        --template "${SRC_ROOT}/runtime/templates/bridge_dispatch_dyn.S.erb" \
        --output "${GEN_INC}/bridge_dispatch_dyn_aarch64.S"

    # 6f: intrinsics.inl.h (included from generated intrinsics.h)
    log_info "  6f: intrinsics.inl.h"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/runtime/templates/intrinsics.inl.h.erb" \
        --output "${GEN_INC}/intrinsics.inl.h"

    # 6g: Profiling plugin stubs (empty files, no ETS plugin profiling)
    log_info "  6g: profiling stubs..."
    mkdir -p "${BUILD_GEN}/runtime/profiling/generated"
    for f in profiling_includes.h profiling_includes_disasm.h read_profile.h \
             destroy_profile.h find_method_in_profile.h dump_profile.h \
             clear_profile.h get_profiling_any_type.h; do
        echo "// Autogenerated stub -- no ETS plugin profiling" \
            > "${BUILD_GEN}/runtime/profiling/generated/${f}"
    done

    # 6h: init_icu_gen.cpp — ICU data directory init (Phase 2e: real ICU cross-compiled)
    log_info "  6h: init_icu_gen.cpp"
    cat > "${GEN_INC}/init_icu_gen.cpp" << 'ICUEOF'
/*
 * Copyright (c) 2022-2024 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "init_icu.h"

#include <mutex>
#include <string>

#include "common/unicode/putil.h"

/**
 * Set ICU data directory to the bundled OHOS ICU data path.
 * The host app is responsible for extracting icudt72l.dat from
 * assets to a writable directory before calling this function.
 */
void SetIcuDirectory()
{
    static int status = 0;
    static std::mutex dataMutex;

    std::lock_guard<std::mutex> lock(dataMutex);
    if (status != 0) {
        return;
    }
    u_setDataDirectory("/data/storage/el1/base/haps/ark/icu");
    status = 1;
}
ICUEOF

    # Stage 7: Shorty values
    log_info "Stage 7: Generating shorty_values.h..."
    ruby "${SRC_ROOT}/isa/gen.rb" \
        -d "${SRC_ROOT}/libpandafile/types.yaml" \
        -t "${SRC_ROOT}/runtime/templates/shorty_values.h.erb" \
        -o "${GEN_BASE}/shorty_values.h" \
        -q "${SRC_ROOT}/libpandafile/types.rb"

    # Stage 8: Plugin defines + IR dyn base types + entrypoints + profiling + assembler codegen
    log_info "Stage 8: Generating plugin/compiler/runtime headers..."
    mkdir -p "${BUILD_GEN}/cross_values"

    # 8a: ir-dyn-base-types.h
    log_info "  8a: ir-dyn-base-types.h"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/compiler/optimizer/templates/ir-dyn-base-types.h.erb" \
        --output "${BUILD_GEN}/compiler/ir-dyn-base-types.h"

    # 8a2-8aN: Batch compiler generated files from plugin_options
    log_info "  8a2: Compiler plugin-generated files..."
    COMPILER_ERB_DIR="${SRC_ROOT}/compiler/optimizer/templates"
    for tpl in \
        source_languages.h.erb \
        codegen_language_extensions.h.erb \
        compiler_interface_extensions.inl.h.erb \
        irtoc_interface_extensions.inl.h.erb \
        irtoc_interface_extensions_includes.inl.h.erb \
        inst_builder_extensions.inl.h.erb \
        intrinsics_extensions.inl.h.erb \
        pipeline_includes.h.erb \
        intrinsics/intrinsics_codegen_ext.inl.h.erb \
        intrinsics/intrinsics_ir_build_static_call.inl.erb \
        intrinsics/intrinsics_ir_build_virtual_call.inl.erb \
        intrinsics/intrinsics_graph_checker.inl.erb \
        intrinsics/intrinsics_ir_build.inl.h.erb \
        intrinsics/intrinsics_can_encode.inl.erb \
        intrinsics/intrinsics_lse_heap_inv_args.inl.erb \
        intrinsics/intrinsics_inline.inl.h.erb \
        intrinsics/intrinsics_inline_native_method.inl.erb \
        intrinsics/intrinsics_inlining_expansion.inl.h.erb \
        intrinsics/intrinsics_inlining_expansion_switch_case.inl.erb \
        intrinsics/intrinsics_peephole.inl.h.erb; do
        out="${tpl%.erb}"
        out="${out//intrinsics\//}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/plugin_options.yaml" \
            --api "${SRC_ROOT}/templates/plugin_options.rb" \
            --template "${COMPILER_ERB_DIR}/${tpl}" \
            --output "${BUILD_GEN}/compiler/${out}" 2>/dev/null && true
    done
    # Some sources use "compiler/generated/" include path; create symlink
    rm -rf "${BUILD_GEN}/compiler/generated"
    ln -s . "${BUILD_GEN}/compiler/generated"
    # create_pipeline.inl is a plugin-merged file (concatenation of per-plugin files)
    # For irtoc-disabled build, it's effectively empty
    touch "${BUILD_GEN}/compiler/create_pipeline.inl"
    log_info "    Compiler plugin files done"

    # 8b: plugins_defines.h
    log_info "  8b: plugins_defines.h"
    mkdir -p "${BUILD_GEN}/runtime/asm_defines"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/runtime/templates/plugins_defines.h.erb" \
        --output "${BUILD_GEN}/runtime/asm_defines/plugins_defines.h"

    # 8c: plugins_asm_defines.def
    log_info "  8c: plugins_asm_defines.def"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/runtime/templates/plugins_asm_defines.def.erb" \
        --output "${BUILD_GEN}/runtime/asm_defines/plugins_asm_defines.def"

    # 8d: profiling_gen.h (from ISA YAML)
    log_info "  8d: profiling_gen.h"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/isa/isa.yaml" \
        --api "${SRC_ROOT}/isa/isapi.rb" \
        --require "${SRC_ROOT}/libpandafile/pandafile_isapi.rb" \
        --template "${SRC_ROOT}/runtime/profiling/profiling_gen.h.erb" \
        --output "${GEN_INC}/profiling_gen.h"

    # 8e: Merge entrypoints YAMLs
    log_info "  8e: Merging entrypoints YAMLs..."
    bash "${SRC_ROOT}/templates/concat_yamls.sh" \
        "${BUILD_GEN}/runtime/entrypoints_merged.yaml" \
        "${SRC_ROOT}/runtime/entrypoints/entrypoints.yaml" \
        "${SRC_ROOT}/plugins/ets/runtime/ets_entrypoints.yaml"

    # 8e2: entrypoints_gen.h (from merged entrypoints YAML)
    log_info "  8e2: entrypoints_gen.h"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/runtime/entrypoints_merged.yaml" \
        --api "${SRC_ROOT}/runtime/entrypoints/entrypoints.rb" \
        --require "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/runtime/entrypoints/entrypoints_gen.h.erb" \
        --output "${GEN_INC}/entrypoints_gen.h"

    # 8e3: entrypoints_compiler.inl (needed by compiler)
    log_info "  8e3: entrypoints_compiler.inl"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/runtime/entrypoints_merged.yaml" \
        --api "${SRC_ROOT}/runtime/entrypoints/entrypoints.rb" \
        --require "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/runtime/entrypoints/entrypoints_compiler.inl.erb" \
        --output "${BUILD_GEN}/compiler/entrypoints_compiler.inl"

    # 8e3b: Ensure cross_values directory exists (8f generates real values later)
    mkdir -p "${BUILD_GEN}/cross_values/generated_values"
    touch "${BUILD_GEN}/cross_values/generated_values/AARCH64_values_gen.h"
    touch "${BUILD_GEN}/cross_values/cross_values.h"
    # 8e4: entrypoints_compiler_checksum.inl (needed by compiler, uses cross_values path)
    log_info "  8e4: entrypoints_compiler_checksum.inl"
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/runtime/entrypoints_merged.yaml" \
        --api "${SRC_ROOT}/runtime/entrypoints/entrypoints.rb" \
        --require "${SRC_ROOT}/templates/common.rb" \
        --template "${SRC_ROOT}/runtime/entrypoints/entrypoints_compiler_checksum.inl.erb" \
        --output "${BUILD_GEN}/compiler/entrypoints_compiler_checksum.inl" \
        "${BUILD_GEN}/cross_values"

    # Stage 8f: Assembler codegen (ISA templates + meta_gen headers)
    log_info "  8f: Generating assembler codegen files..."
    mkdir -p "${BUILD_GEN}/assembler"

    # ISA-based templates (isa.h, ins_emit.h, ins_to_string.cpp, etc.)
    for tpl in isa.h.erb ins_emit.h.erb ins_to_string.cpp.erb ins_create_api.h.erb opcode_parsing.h.erb operand_types_print.h.erb; do
        out="${tpl%.erb}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/isa/isa.yaml" \
            --api "${SRC_ROOT}/isa/isapi.rb" \
            --require "${SRC_ROOT}/assembler/asm_isapi.rb","${SRC_ROOT}/libpandafile/pandafile_isapi.rb" \
            --template "${SRC_ROOT}/assembler/templates/${tpl}" \
            --output "${BUILD_GEN}/assembler/${out}"
    done

    # Plugin extensions registration
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/assembler/extensions/register_extensions.h.erb" \
        --output "${BUILD_GEN}/assembler/register_extensions.h"

    # Metadata templates (meta_gen.h + ets_meta_gen.h)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/assembler/metadata.yaml" \
        --api "${SRC_ROOT}/assembler/asm_metadata.rb" \
        --require "${SRC_ROOT}/assembler/asm_metadata.rb" \
        --template "${SRC_ROOT}/assembler/templates/meta_gen.cpp.erb" \
        --output "${BUILD_GEN}/assembler/meta_gen.h"

    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/plugins/ets/assembler/extension/metadata.yaml" \
        --api "${SRC_ROOT}/assembler/asm_metadata.rb" \
        --require "${SRC_ROOT}/assembler/asm_metadata.rb" \
        --template "${SRC_ROOT}/assembler/templates/meta_gen.cpp.erb" \
        --output "${BUILD_GEN}/assembler/ets_meta_gen.h"

    log_info "  Assembler codegen files generated"

    # Stage 9: Generate intrinsics.yaml from runtime.yaml
    log_info "Stage 9: Generating intrinsics.yaml..."
    # NOTE: runtime.rb must be before utils.rb since utils.rb extends Intrinsic class
    ruby "${SRC_ROOT}/runtime/templates/gen_intrinsics_data.rb" \
        -d "${SRC_ROOT}/runtime/runtime.yaml","${SRC_ROOT}/plugins/ets/runtime/ets_compiler_intrinsics.yaml","${SRC_ROOT}/plugins/ets/runtime/ets_libbase_runtime.yaml" \
        -t "${SRC_ROOT}/runtime/templates/intrinsics.yaml.erb" \
        -o "${BUILD_GEN}/runtime/intrinsics.yaml" \
        -r "${SRC_ROOT}/runtime/templates/runtime.rb","${SRC_ROOT}/libpandabase/utils.rb"

    # Stage 10: Generate compiler intrinsics files from intrinsics.yaml
    log_info "Stage 10: Generating compiler intrinsics files..."
    INTRINSICS_ERB_DIR="${SRC_ROOT}/compiler/optimizer/templates/intrinsics"
    # NOTE: compiler_intrinsics.rb must be before utils.rb (utils.rb extends Intrinsic class)
    for tpl in \
        intrinsics_enum.inl.erb \
        get_intrinsics.inl.erb \
        entrypoints_bridge_asm_macro.inl.erb \
        intrinsics_ir_build.inl.erb \
        intrinsics_flags.inl.erb \
        get_intrinsics_names.inl.erb \
        generate_operations_intrinsic_inst.inl.erb \
        generate_operations_intrinsic_graph.inl.erb \
        intrinsic_codegen_test.inl.erb \
        intrinsic_flags_test.inl.erb \
        can_encode_builtin.inl.erb \
        intrinsics_codegen.inl.h.erb \
        intrinsics_codegen.inl.erb \
        intrinsics_inline.inl.erb \
        intrinsics_peephole.inl.erb; do
        out="${tpl%.erb}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/runtime/intrinsics.yaml" \
            --api "${INTRINSICS_ERB_DIR}/compiler_intrinsics.rb" \
            --require "${INTRINSICS_ERB_DIR}/compiler_intrinsics.rb","${SRC_ROOT}/libpandabase/utils.rb" \
            --template "${INTRINSICS_ERB_DIR}/${tpl}" \
            --output "${BUILD_GEN}/compiler/${out}" 2>/dev/null && true
    done
    log_info "  Compiler intrinsics files generated"

    # Stage 11: Generate runtime intrinsics headers from intrinsics.yaml
    log_info "Stage 11: Generating runtime intrinsics headers..."
    RUNTIME_INTR_ERB_DIR="${SRC_ROOT}/runtime/templates"
    for tpl in \
        intrinsics_enum.h.erb \
        intrinsics_gen.h.erb \
        intrinsics.h.erb \
        unimplemented_intrinsics-inl.cpp.erb; do
        out="${tpl%.erb}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/runtime/intrinsics.yaml" \
            --api "${RUNTIME_INTR_ERB_DIR}/intrinsics.rb" \
            --require "${SRC_ROOT}/libpandabase/utils.rb" \
            --template "${RUNTIME_INTR_ERB_DIR}/${tpl}" \
            --output "${GEN_INC}/${out}" 2>/dev/null && true
    done
    log_info "  Runtime intrinsics headers generated"

    # Stage 12: Runtime plugin-generated files
    log_info "Stage 12: Generating runtime plugin headers..."
    for tpl in \
        plugins.inc.erb \
        plugins_interpreters-inl.h.erb \
        language_config_gen.inc.erb; do
        out="${tpl%.erb}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/plugin_options.yaml" \
            --api "${SRC_ROOT}/templates/plugin_options.rb" \
            --template "${SRC_ROOT}/runtime/templates/${tpl}" \
            --output "${GEN_INC}/${out}" 2>/dev/null && true
    done
    # plugins_entrypoints_gen.h.erb is in runtime/entrypoints/ (not templates/)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/runtime/entrypoints/plugins_entrypoints_gen.h.erb" \
        --output "${GEN_INC}/plugins_entrypoints_gen.h" 2>/dev/null && true
    log_info "  Runtime plugin headers generated"

    # Stage 12b: Generate asm_defines.h + cross_values
    # NOTE: Must run AFTER Stages 11-12 which generate intrinsics_enum.h, language_config_gen.inc
    #
    # asm_defines.h is generated from actual ARM64 assembly output (real offset values for
    # hand-written .S files). cross_values.h always uses stubs (all getter functions with 0
    # values), matching HongEngine — JIT is disabled, so JIT codegen doesn't use these values.
    log_info "Stage 12b: Generating asm_defines.h + cross_values..."
    mkdir -p "${BUILD_GEN}/cross_values/generated_values"
    CV_GEN_DIR="${BUILD_GEN}/cross_values/generated_values"
    CV_VALUES="${CV_GEN_DIR}/AARCH64_values_gen.h"
    CV_UMBRELLA="${BUILD_GEN}/cross_values/cross_values.h"

    NDK_CXX="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android27-clang++"
    if command -v "${NDK_CXX}" &>/dev/null; then
        ASM_DEF_SRC="${SRC_ROOT}/runtime/asm_defines/defines.cpp"
        ASM_OUT="${BUILD_GEN}/cross_values/libasm_defines.S"
        if ${NDK_CXX} -S -std=c++17 \
            --target=aarch64-linux-android26 \
            -DPANDA_TARGET_ARM64 -DPANDA_TARGET_64 -DPANDA_BUILD \
            -DPANDA_PRODUCT_BUILD -DPANDA_TARGET_MOBILE -DPANDA_TARGET_UNIX \
            -DPANDA_USE_FUTEX -DPANDA_USE_32_BIT_POINTER \
            -Wno-invalid-offsetof \
            -I"${SRC_ROOT}" \
            -I"${SRC_ROOT}/runtime" \
            -I"${SRC_ROOT}/libpandabase" \
            -I"${SRC_ROOT}/libpandafile" \
            -I"${THIRD_PARTY}/utils_native/base/include" \
            -I"${BUILD_GEN}/generated" \
            -I"${BUILD_GEN}/libpandabase/generated" \
            -I"${BUILD_GEN}/libpandafile/include" \
            -I"${BUILD_GEN}/panda_gen_options/generated" \
            -I"${BUILD_GEN}/panda_gen_options" \
            -I"${BUILD_GEN}/runtime/asm_defines" \
            -I"${BUILD_GEN}/runtime/include" \
            -I"${BUILD_GEN}/runtime" \
            -I"${BUILD_GEN}/compiler" \
            -I"${BUILD_GEN}/cross_values" \
            -I"${BUILD_GEN}" \
            -I"${SRC_ROOT}/plugins/ets" \
            -o "${ASM_OUT}" \
            "${ASM_DEF_SRC}" 2>/dev/null; then
            log_info "    defines.cpp compiled OK"
            # Generate asm_defines.h from assembly output (real offset values)
            ruby "${SRC_ROOT}/runtime/asm_defines/defines_generator.rb" \
                "${ASM_OUT}" "${GEN_INC}/asm_defines.h" 2>/dev/null
            log_info "    asm_defines.h generated (real values)"
            # Also generate AARCH64_values_gen.h with real values
            ruby "${SRC_ROOT}/cross_values/cross_values_generator.rb" \
                "${ASM_OUT}" "${CV_VALUES}" "AARCH64" 2>/dev/null && \
                log_info "    AARCH64_values_gen.h generated (real values)"
        else
            log_warn "    defines.cpp compilation failed, using stubs for asm_defines.h"
            touch "${GEN_INC}/asm_defines.h"
        fi
    fi

    # Always use stub cross_values.h — the real version from cross_values_getters_generator.rb
    # only has GetManagedThreadEntrypointOffset, but runtime_interface.h needs 178+ getters.
    log_info "    Generating cross_values.h stub (all getter functions)..."
    ruby -e '
        CV_VALUES = ARGV[0]
        CV_UMBRELLA = ARGV[1]
        def_files = ARGV[2..]
        names = []
        types = {}
        def_files.each do |f|
            next unless File.exist?(f)
            content = File.read(f)
            content.scan(/DEFINE_VALUE\s*\(\s*(\w+)\s*,/).each { |m| names << m[0] }
            content.scan(/DEFINE_VALUE_WITH_TYPE\s*\(\s*(\w+)\s*,.*,\s*([a-z_][\w_]*)\s*\)\s*$/).each { |m|
                names << m[0]
                types[m[0]] = m[1]
            }
        end
        names.uniq!
        names.sort!

        # Only write AARCH64_values_gen.h if it does not already have real values
        if !File.exist?(CV_VALUES) || File.read(CV_VALUES).include?("Autogenerated stub")
            File.open(CV_VALUES, "w") do |f|
                f.puts "// Autogenerated stub -- JIT codegen disabled"
                f.puts "#ifndef CROSS_VALUES_GENERATED_VALUES_AARCH64_VALUES_GEN_H"
                f.puts "#define CROSS_VALUES_GENERATED_VALUES_AARCH64_VALUES_GEN_H"
                f.puts "namespace ark::cross_values::AARCH64 {"
                names.each do |n|
                    t = types[n] || "ptrdiff_t"
                    f.puts "static constexpr #{t} #{n}_VAL = 0;"
                end
                f.puts "}  // namespace ark::cross_values::AARCH64"
                f.puts "#endif"
            end
        end

        File.open(CV_UMBRELLA, "w") do |f|
            f.puts "// Autogenerated stub -- JIT codegen disabled"
            f.puts "#ifndef CROSS_VALUES_CROSS_VALUES_H"
            f.puts "#define CROSS_VALUES_CROSS_VALUES_H"
            f.puts "#include \"generated_values/AARCH64_values_gen.h\""
            f.puts "#include <cstddef>"
            f.puts "#include \"libpandabase/utils/arch.h\""
            f.puts "namespace ark::cross_values {"
            names.each do |n|
                t = types[n] || "ptrdiff_t"
                pascal = n.split("_").collect(&:capitalize).join
                f.puts "[[maybe_unused]] static constexpr #{t} Get#{pascal}(Arch arch) {"
                f.puts "    return cross_values::AARCH64::#{n}_VAL;"
                f.puts "}"
            end
            f.puts ""
            f.puts "// Specific getter for TLS entrypoints offsets:"
            f.puts "[[maybe_unused]] inline ptrdiff_t GetManagedThreadEntrypointOffset(Arch arch, size_t id)"
            f.puts "{"
            f.puts "    return GetManagedThreadEntrypointsOffset(arch) + id * PointerSize(arch);"
            f.puts "}"
            f.puts "}  // namespace ark::cross_values"
            f.puts "#endif"
        end
        puts "    stub: #{names.size} getter functions generated"
    ' "${CV_VALUES}" "${CV_UMBRELLA}" \
        "${SRC_ROOT}/runtime/asm_defines/asm_defines.def" \
        "${SRC_ROOT}/plugins/ets/runtime/asm_defines/asm_defines.def"

    # Stage 13: Verification generated headers
    log_info "Stage 13: Generating verification headers..."
    VERIF_GEN_INC="${BUILD_GEN}/verification/gen/include"
    mkdir -p "${VERIF_GEN_INC}"

    # 13a: abs_int_inl_compat_checks.h
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/verification/verification.yaml" \
        --api "${SRC_ROOT}/verification/verification.rb" \
        --template "${SRC_ROOT}/verification/gen/templates/abs_int_inl_compat_checks.h.erb" \
        --output "${VERIF_GEN_INC}/abs_int_inl_compat_checks.h"

    # 13b: verifier_messages.h (from messages.yaml)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${SRC_ROOT}/verification/messages.yaml" \
        --api "${SRC_ROOT}/templates/messages.rb" \
        --template "${SRC_ROOT}/templates/messages/messages.h.erb" \
        --output "${VERIF_GEN_INC}/verifier_messages.h"

    # 13c: plugins_gen.inc (verification plugin list)
    ruby "${SRC_ROOT}/isa/gen.rb" \
        --data "${BUILD_GEN}/plugin_options.yaml" \
        --api "${SRC_ROOT}/templates/plugin_options.rb" \
        --template "${SRC_ROOT}/verification/gen/templates/plugins_gen.inc.erb" \
        --output "${VERIF_GEN_INC}/plugins_gen.inc"

    log_info "  Verification headers generated"

    # Stage 14: verifier_messages_data_gen.cpp (if not already present)
    if [ ! -f "${VERIF_GEN_INC}/verifier_messages_data_gen.cpp" ]; then
        log_info "Stage 14: Generating verifier_messages_data_gen.cpp..."
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${SRC_ROOT}/verification/messages.yaml" \
            --api "${SRC_ROOT}/templates/messages.rb" \
            --template "${SRC_ROOT}/verification/gen/templates/verifier_messages_data_gen.cpp.erb" \
            --output "${VERIF_GEN_INC}/verifier_messages_data_gen.cpp"
    fi

    # Stage 15: Verification ISA templates
    log_info "Stage 15: Generating verification ISA templates..."
    for tpl in \
        cflow_iterate_inl_gen.h.erb \
        abs_int_inl_gen.h.erb \
        job_fill_gen.h.erb \
        handle_gen.h.erb; do
        out="${tpl%.erb}"
        ruby "${SRC_ROOT}/isa/gen.rb" \
            --data "${BUILD_GEN}/isa/isa.yaml" \
            --api "${SRC_ROOT}/isa/isapi.rb" \
            --template "${SRC_ROOT}/verification/gen/templates/${tpl}" \
            --output "${VERIF_GEN_INC}/${out}" \
            --require "${SRC_ROOT}/verification/verification.rb" 2>/dev/null && true
    done
    log_info "  Verification ISA templates generated"

    log_info "Code generation complete."
}

# -----------------------------------------------------------------------------
# Step 2: Cross-compile for Android arm64-v8a
# -----------------------------------------------------------------------------
do_compile() {
    log_info "=== Cross-Compilation (Android arm64-v8a) ==="

    # Create mobile platform stubs (if not exist)
    MOBILE_PLATFORM="${SRC_ROOT}/platforms/mobile/libpandabase"
    if [ ! -d "${MOBILE_PLATFORM}" ]; then
        log_info "Creating mobile platform stubs..."
        mkdir -p "${MOBILE_PLATFORM}"
        cp "${SRC_ROOT}/platforms/common/libpandabase/coherency_line_size.h" \
           "${MOBILE_PLATFORM}/coherency_line_size.h"
        echo '// Mobile uses the same dlopen/dlsym as Unix' > "${MOBILE_PLATFORM}/library_loader_load.cpp"
        echo '#include "platforms/unix/libpandabase/library_loader_load.cpp"' >> "${MOBILE_PLATFORM}/library_loader_load.cpp"
    fi

    # Configure
    log_info "Configuring CMake..."
    cmake \
        -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release \
        -S "${SCRIPT_DIR}" \
        -B "${BUILD_ANDROID}"

    # Build
    log_info "Building..."
    # Build only the ArkCompiler runtime targets (ACE engine is a separate phase)
    cmake --build "${BUILD_ANDROID}" --target hongengine_c -- -j$(nproc)

    log_info "Compilation complete."
}

# -----------------------------------------------------------------------------
# Step 3: Install to app jniLibs
# -----------------------------------------------------------------------------
do_install() {
    log_info "=== Installing to jniLibs ==="

    mkdir -p "${JNILIBS_DIR}"

    STRIP="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

    for lib in libc_secshared.so libz.so libpandabase.so libziparchive.so libpandafile.so libarktarget_options.so libarkcompiler.so libarkaotmanager.so libarkassembler.so libarkruntime.so libhongengine_c.so; do
        if [ ! -f "${BUILD_ANDROID}/lib/${lib}" ]; then
            log_warn "  ${lib} not found, skipping"
            continue
        fi
        cp "${BUILD_ANDROID}/lib/${lib}" "${JNILIBS_DIR}/"
        if [ -x "${STRIP}" ]; then
            "${STRIP}" --strip-debug "${JNILIBS_DIR}/${lib}"
        fi
        size=$(du -h "${JNILIBS_DIR}/${lib}" | cut -f1)
        log_info "  ${lib} (${size})"
    done

    # Copy libc++_shared.so from NDK (required by libhongengine_c.so)
    local LIBCXX_NDK="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    if [ -f "${LIBCXX_NDK}" ]; then
        cp "${LIBCXX_NDK}" "${JNILIBS_DIR}/"
        if [ -x "${STRIP}" ]; then
            "${STRIP}" --strip-debug "${JNILIBS_DIR}/libc++_shared.so"
        fi
        size=$(du -h "${JNILIBS_DIR}/libc++_shared.so" | cut -f1)
        log_info "  libc++_shared.so (${size})"
    else
        log_warn "  libc++_shared.so not found in NDK, skipping"
    fi

    log_info "Install complete: ${JNILIBS_DIR}"
}

# -----------------------------------------------------------------------------
# Step 4: Clean
# -----------------------------------------------------------------------------
do_clean() {
    log_info "Cleaning build artifacts..."
    rm -rf "${BUILD_GEN}" "${BUILD_ANDROID}"
    log_info "Clean complete."
}

# =============================================================================
# Main
# =============================================================================
case "${1:-all}" in
    codegen)
        do_preflight
        do_codegen
        ;;
    patch)
        do_preflight
        do_patch
        ;;
    compile)
        do_preflight
        do_patch
        do_compile
        ;;
    install)
        do_preflight
        do_install
        ;;
    clean)
        do_clean
        ;;
    all)
        do_preflight
        do_patch
        do_codegen
        do_compile
        do_install
        log_info ""
        log_info "========== Build Complete =========="
        log_info "Libraries installed to: ${JNILIBS_DIR}"
        log_info "Next step: ./gradlew assembleDebug"
        ;;
    *)
        echo "Usage: $0 [codegen|compile|install|clean|all|patch]"
        echo ""
        echo "  codegen   - Generate code-gen files (Ruby/ERB templates)"
        echo "  compile   - Cross-compile .so files with Android NDK"
        echo "  install   - Strip and copy .so files to app jniLibs"
        echo "  clean     - Remove build artifacts"
        echo "  all       - Run all steps (default)"
        exit 1
        ;;
esac
