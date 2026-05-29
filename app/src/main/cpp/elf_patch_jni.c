#include <jni.h>

// Forward declaration from elf_patch.c
int elf_patch_libc_to_libb(const char *path);

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
