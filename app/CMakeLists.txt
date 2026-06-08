cmake_minimum_required(VERSION 3.18.1)
project("hoa_native_stubs")

# libace_napi.z.so — OHOS NAPI stub
# Re-exports NAPI symbols from libarkui_android.so at runtime.
# SONAME must be "libace_napi.z.so" to match OHOS HAP DT_NEEDED.
add_library(ace_napi_z SHARED src/main/cpp/ace_napi_z_stub.c)

set_target_properties(ace_napi_z PROPERTIES
    OUTPUT_NAME "ace_napi.z"
    LINK_FLAGS "-Wl,-soname,libace_napi.z.so -nostartfiles -nostdlib"
)

# Link against libarkui_android.so to validate symbols at link time.
# At runtime, the dynamic linker resolves them transitively through the APK.
target_link_directories(ace_napi_z PRIVATE ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI})
target_link_libraries(ace_napi_z PRIVATE arkui_android)

# libhilog_ndk.z.so — OHOS hilog NDK stub
# Forwards OH_LOG_Print to Android's __android_log_print.
# SONAME must be "libhilog_ndk.z.so" to match OHOS HAP DT_NEEDED.
add_library(hilog_ndk_z SHARED src/main/cpp/hilog_stub.c)

set_target_properties(hilog_ndk_z PROPERTIES
    OUTPUT_NAME "hilog_ndk.z"
    LINK_FLAGS "-Wl,-soname,libhilog_ndk.z.so -nostartfiles"
)

target_link_libraries(hilog_ndk_z PRIVATE log)

# libohaudio.so — OHOS OHAudio stub
# Provides dummy OH_AudioStreamBuilder_* / OH_AudioRenderer_* symbols.
# GenerateRenderer returns AUDIOSTREAM_ERROR_SYSTEM so callers can
# fall back to silent / no-audio mode.
# SONAME must be "libohaudio.so" to match OHOS HAP DT_NEEDED.
add_library(ohaudio SHARED src/main/cpp/ohaudio_stub.c)

set_target_properties(ohaudio PROPERTIES
    OUTPUT_NAME "ohaudio"
    LINK_FLAGS "-Wl,-soname,libohaudio.so -nostartfiles -nostdlib"
)

# libsettings_napi.z.so — now built by ArkUI-X plugins/settings/build_settings.sh
# and synced via scripts/sync_arkui_x.sh