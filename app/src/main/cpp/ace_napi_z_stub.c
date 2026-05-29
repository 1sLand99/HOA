/*
 * libace_napi.z.so stub for HOA.
 *
 * OHOS HAP native .so files link against libace_napi.z.so via DT_NEEDED.
 * This stub re-exports NAPI symbols from libarkui_android.so, which already
 * contains the full NAPI implementation (napi_module_register, etc.).
 *
 * The stub has SONAME "libace_napi.z.so" (matching the OHOS name) and
 * DT_NEEDED "libarkui_android.so", so the dynamic linker transitively
 * resolves all NAPI symbols through the stub.
 *
 * Build:
 *   NDK clang -shared -fPIC -o libace_napi.z.so ace_napi_z_stub.c \
 *     -Wl,-soname,libace_napi.z.so -L<jniLibs> -larkui_android -nostartfiles -nostdlib
 */
/* empty — all symbols inherited from libarkui_android.so via DT_NEEDED */
