#include <jni.h>
#include <android/log.h>
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

    /* &sigaction resolves to libb.so's bridge via -Wl,-Bsymbolic. */
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
