#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <signal.h>
#include <string.h>

struct got_target {
    const char *name;
    void *addr;
};

// Forward declarations
int elf_patch_libc_to_libb(const char *path);
int elf_patch_got_signal(const char *so_basename,
                          const struct got_target *targets, int ntargets);
int elf_patch_runpath_to_origin(const char *path);

JNIEXPORT jboolean JNICALL
Java_app_hackeris_hoa_hap_ElfPatcher_patchSo(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return JNI_FALSE;

    int ret = elf_patch_libc_to_libb(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_app_hackeris_hoa_hap_ElfPatcher_patchGot(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return 0;

    const char *slash = strrchr(cpath, '/');
    const char *basename = slash ? slash + 1 : cpath;

    /*
     * Each target redirects a GOT entry in the HAP .so from the symbol's
     * default resolution (usually bionic's libc.so) to libb.so's bridge
     * implementation.  &func resolves to libb.so's own definition via
     * -Wl,-Bsymbolic.
     *
     * sigaction:  musl↔bionic struct sigaction ABI 不同，需经
     *             signal_bridge.c 做布局转换。
     *
     * __tls_get_addr 不在此列：
     *   aarch64 上 OHOS NDK 默认使用 TLSDESC 模型访问 __thread 变量，
     *   解析器由 linker 的 TLSDESC resolver 提供，不调用 __tls_get_addr。
     *   bionic 线程的 __thread 开箱即用（bionic linker 处理 TLSDESC）；
     *   musl 线程的 __thread 需额外干预 TLSDESC entry 而非 GOT 符号。
     */
    struct got_target targets[] = {
        { "sigaction", (void *)&sigaction },
    };

    int n = elf_patch_got_signal(basename, targets,
                                 sizeof(targets) / sizeof(targets[0]));
    __android_log_print(ANDROID_LOG_INFO, "BRIDGE-GOT",
                        "patchGot(%s): patched %d GOT entries",
                        basename, n);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return n;
}

/*
 * Load a shared library via direct dlopen() from native code with
 * RTLD_GLOBAL.  This bypasses the Android classloader namespace (clns)
 * and loads into the default namespace where symbol visibility between
 * DT_NEEDED dependencies works correctly.
 *
 * Returns 0 on success, -1 on failure.  Error message is logged and
 * also returned via JNI layer.
 */
JNIEXPORT jint JNICALL
Java_app_hackeris_hoa_hap_ElfPatcher_nativeLoad(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return -1;

    void *handle = dlopen(cpath, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        const char *err = dlerror();
        __android_log_print(ANDROID_LOG_WARN, "BRIDGE-LOAD",
                            "dlopen(%s): %s", cpath, err ? err : "unknown error");
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, "BRIDGE-LOAD",
                        "dlopen(%s) OK (RTLD_GLOBAL)", cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return 0;
}

/*
 * Patch the DT_RUNPATH (and DT_RPATH) of an ELF shared library to
 * "$ORIGIN" so that DT_NEEDED dependencies are resolved from the
 * same directory at runtime.
 *
 * Returns JNI_TRUE if a RUNPATH/RPATH was patched, JNI_FALSE if
 * the file had none or on error.
 */
JNIEXPORT jboolean JNICALL
Java_app_hackeris_hoa_hap_ElfPatcher_patchRunpath(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return JNI_FALSE;

    int ret = elf_patch_runpath_to_origin(cpath);

    __android_log_print(ANDROID_LOG_INFO, "BRIDGE-RPATH",
                        "patchRunpath(%s): %s",
                        cpath, ret == 0 ? "patched" : "no runpath or error");

    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}
