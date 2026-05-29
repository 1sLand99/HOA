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
