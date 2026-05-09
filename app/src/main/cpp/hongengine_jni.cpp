#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <string>
#include <cstdio>

#include "hongengine_c_api.h"

#define LOG_TAG "HOA.Runtime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct JniEngineState {
    HongEngineState* c_state = nullptr;
    JavaVM* jvm = nullptr;
    jobject assetMgr = nullptr;
    ANativeWindow* window = nullptr;

    ~JniEngineState() {
        if (window) ANativeWindow_release(window);
        if (c_state) hongengine_state_destroy(c_state);
    }
};

static JniEngineState* GetState(jlong ptr) {
    return reinterpret_cast<JniEngineState*>(ptr);
}

// Error to jstring helper
static jstring ErrorToJava(JNIEnv* env, const char* msg, HongEngineError code) {
    char buf[512];
    snprintf(buf, sizeof(buf), "[%d] %s", code, msg);
    return env->NewStringUTF(buf);
}

extern "C" {

// =============================================================================
// nativeInit
// =============================================================================
JNIEXPORT jlong JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeInit(
    JNIEnv* env, jclass, jobject assetMgr, jstring filesDir) {

    auto* state = new JniEngineState();
    env->GetJavaVM(&state->jvm);

    const char* dir = env->GetStringUTFChars(filesDir, nullptr);
    state->c_state = hongengine_state_create(dir);
    env->ReleaseStringUTFChars(filesDir, dir);

    state->assetMgr = env->NewGlobalRef(assetMgr);

    LOGE("nativeInit: filesDir=%s", hongengine_state_get_files_dir(state->c_state));
    return reinterpret_cast<jlong>(state);
}

// =============================================================================
// nativeSetSurface
// =============================================================================
JNIEXPORT void JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeSetSurface(
    JNIEnv* env, jclass, jlong ptr, jobject surface) {

    auto* state = GetState(ptr);
    if (state->window) {
        ANativeWindow_release(state->window);
        state->window = nullptr;
    }
    if (surface) {
        state->window = ANativeWindow_fromSurface(env, surface);
        LOGE("nativeSetSurface: surface set");
    }
}

// =============================================================================
// nativeSurfaceChanged
// =============================================================================
JNIEXPORT void JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeSurfaceChanged(
    JNIEnv*, jclass, jlong ptr, jint w, jint h) {

    auto* state = GetState(ptr);
    LOGE("nativeSurfaceChanged: %dx%d", w, h);
    if (state->window) {
        ANativeWindow_setBuffersGeometry(state->window, w, h,
                                         AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    }
}

// =============================================================================
// nativeSurfaceDestroyed
// =============================================================================
JNIEXPORT void JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeSurfaceDestroyed(
    JNIEnv*, jclass, jlong ptr) {
    LOGE("nativeSurfaceDestroyed");
}

// =============================================================================
// nativeRunPage — creates ETS runtime and executes entry point
// =============================================================================
JNIEXPORT jstring JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeRunPage(
    JNIEnv* env, jclass, jlong ptr,
    jstring bundleName, jstring abilityName, jstring abcPath, jstring entryPoint) {

    auto* state = GetState(ptr);

    const char* bundle = env->GetStringUTFChars(bundleName, nullptr);
    const char* ability = env->GetStringUTFChars(abilityName, nullptr);
    const char* abc = env->GetStringUTFChars(abcPath, nullptr);
    const char* ep = entryPoint ? env->GetStringUTFChars(entryPoint, nullptr) : nullptr;

    LOGE("nativeRunPage: bundle=%s ability=%s abc=%s entryPoint=%s",
         bundle, ability, abc, ep ? ep : "(default)");

    // Build stdlib path (etsstdlib.abc must be extracted first)
    char stdlibPath[1024];
    snprintf(stdlibPath, sizeof(stdlibPath), "%s/test/etsstdlib.abc",
             hongengine_state_get_files_dir(state->c_state));

    // Validate ABC files
    HongAbcInfo info = hongengine_validate_abc(abc);
    LOGE("ABC validation: path=%s valid=%d error=%s", abc, info.valid,
         info.error ? info.error : "ok");
    if (info.valid) {
        LOGE("ABC details: magic=%s version=%s fileSize=%u numClasses=%u",
             info.magic, info.version, info.file_size, info.num_classes);
    }

    HongAbcInfo stdlibInfo = hongengine_validate_abc(stdlibPath);
    LOGE("ABC stdlib validation: path=%s valid=%d error=%s", stdlibPath,
         stdlibInfo.valid, stdlibInfo.error ? stdlibInfo.error : "ok");

    // Create ETS runtime
    HongEngineError err = hongengine_ets_create_runtime(state->c_state, abc, stdlibPath);
    if (err != HONGENGINE_OK) {
        const char* detail = hongengine_ets_get_last_error(state->c_state);
        LOGE("ETS runtime creation FAILED: code=%d detail=%s", err, detail ? detail : "unknown");
        jstring result = ErrorToJava(env, detail ? detail : "ETS runtime creation failed", err);
        env->ReleaseStringUTFChars(bundleName, bundle);
        env->ReleaseStringUTFChars(abilityName, ability);
        env->ReleaseStringUTFChars(abcPath, abc);
        if (ep) env->ReleaseStringUTFChars(entryPoint, ep);
        return result;
    }
    LOGE("ETS runtime created OK");

    // Execute entry point
    HongEtsResult result = hongengine_ets_execute_module(state->c_state, nullptr, ep);
    if (result.success) {
        LOGE("ETS module executed OK: exit_code=%d", result.exit_code);
    } else {
        LOGE("ETS module execution FAILED: exit_code=%d error=%s",
             result.exit_code, result.error ? result.error : "unknown");
    }

    env->ReleaseStringUTFChars(bundleName, bundle);
    env->ReleaseStringUTFChars(abilityName, ability);
    env->ReleaseStringUTFChars(abcPath, abc);
    if (ep) env->ReleaseStringUTFChars(entryPoint, ep);

    if (result.success) return env->NewStringUTF("OK");
    return ErrorToJava(env, result.error ? result.error : "execution failed", HONGENGINE_OK);
}

// =============================================================================
// nativeTryEntryPoint — execute a custom entry point on an already-created VM
// =============================================================================
JNIEXPORT jstring JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeTryEntryPoint(
    JNIEnv* env, jclass, jlong ptr, jstring entryPoint) {

    auto* state = GetState(ptr);
    const char* ep = env->GetStringUTFChars(entryPoint, nullptr);

    LOGE("nativeTryEntryPoint: ep=%s", ep);

    HongEtsResult result = hongengine_ets_execute_module(state->c_state, nullptr, ep);
    if (result.success) {
        LOGE("nativeTryEntryPoint OK: exit_code=%d", result.exit_code);
    } else {
        LOGE("nativeTryEntryPoint FAILED: %s", result.error ? result.error : "unknown");
    }

    env->ReleaseStringUTFChars(entryPoint, ep);

    if (result.success) return env->NewStringUTF("OK");
    return ErrorToJava(env, result.error ? result.error : "entry point failed", HONGENGINE_OK);
}

// =============================================================================
// nativeDestroy
// =============================================================================
JNIEXPORT void JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeDestroy(
    JNIEnv* env, jclass, jlong ptr) {

    auto* state = GetState(ptr);
    LOGE("nativeDestroy: releasing resources");

    if (state->assetMgr) {
        env->DeleteGlobalRef(state->assetMgr);
        state->assetMgr = nullptr;
    }

    delete state;
    LOGE("nativeDestroy: complete");
}

// =============================================================================
// nativeOnShow / nativeOnHide
// =============================================================================
JNIEXPORT void JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeOnShow(JNIEnv*, jclass, jlong) {
    LOGE("nativeOnShow");
}

JNIEXPORT void JNICALL
Java_app_hackeris_hoa_runtime_StageActivityV2_nativeOnHide(JNIEnv*, jclass, jlong) {
    LOGE("nativeOnHide");
}

} // extern "C"
