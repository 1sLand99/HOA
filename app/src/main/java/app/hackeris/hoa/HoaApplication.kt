package app.hackeris.hoa

import app.hackeris.hoa.logging.LogWriter
import ohos.stage.ability.adapter.StageApplication

class HoaApplication : StageApplication() {

    var initSuccess = false
        private set

    var initError: Throwable? = null
        private set

    override fun onCreate() {
        LogWriter.init(getExternalFilesDir(null) ?: filesDir)
        LogWriter.e(TAG, "========== HOA Application onCreate START ==========")
        LogWriter.e(TAG, "Process: ${android.os.Process.myPid()}  Name: ${currentProcessName()}")

        // Only initialize the ArkUI-X runtime (libarkui_android.so, ~79 MB) in
        // HAP worker processes (":hap*").  The main process only hosts
        // MainActivity and does not need the native engine at all.
        // This shaves ~1-2 s off the cold-start time of the launcher activity.
        if (isHapProcess()) {
            LogWriter.e(TAG, "HAP process detected — initializing ArkUI-X runtime")
            initArkUIX()
        } else {
            LogWriter.e(TAG, "Main process — skipping ArkUI-X init (not needed for launcher)")
            // Load libb.so (musl ABI bridge) even in the main process.
            // HapInstaller (which runs in the main process) needs the ELF
            // patching JNI that lives in libb.so.
            try {
                System.loadLibrary("b")
                LogWriter.e(TAG, "System.loadLibrary(\"b\") — SUCCESS (main process)")
            } catch (e: UnsatisfiedLinkError) {
                LogWriter.e(TAG, "System.loadLibrary(\"b\") — FAILED (main process)", e)
            }
            initSuccess = true   // main process is always "ready"
        }

        LogWriter.e(TAG, "========== HOA Application onCreate END ==========")
    }

    private fun initArkUIX() {
        // Load musl ABI bridge (libb.so) FIRST — it must be in the linker
        // namespace before any HAP .so files load, so musl symbols resolve
        // through libb.so instead of bionic libc.so.
        try {
            System.loadLibrary("b")
            LogWriter.e(TAG, "System.loadLibrary(\"b\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"b\") — FAILED", e)
        }

        // Try explicit native library load first for better error reporting
        try {
            System.loadLibrary("arkui_android")
            LogWriter.e(TAG, "System.loadLibrary(\"arkui_android\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"arkui_android\") — FAILED", e)
            LogWriter.e(TAG, "  nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
            val libFile = java.io.File(applicationInfo.nativeLibraryDir, "libarkui_android.so")
            LogWriter.e(TAG, "  libarkui_android.so exists: ${libFile.exists()}, size: ${if (libFile.exists()) libFile.length() else 0}")
            initError = e
        }

        // Pre-load HDS (Huawei Design System) stub so its NAPI module
        // "hds.hdsBaseComponent" is registered before any HAP tries to
        // import "@hms:hds.hdsBaseComponent".
        try {
            System.loadLibrary("hms_hds")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_hds\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_hds\") — FAILED", e)
        }

        // Pre-load HMS Security stubs so NAPI modules "security.deviceCertificate",
        // "core.AAID", and "security.safetyDetect" are registered before any HAP
        // tries to import them.
        try {
            System.loadLibrary("hms_security")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_security\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_security\") — FAILED", e)
        }

        // Pre-load HMS Push stubs so NAPI modules "core.push.pushService"
        // and "core.push.pushCommon" are registered before any HAP
        // tries to import them.
        try {
            System.loadLibrary("hms_push")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_push\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_push\") — FAILED", e)
        }

        try {
            super.onCreate()
            initSuccess = true
            LogWriter.e(TAG, "StageApplication.onCreate() completed successfully")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "FATAL: UnsatisfiedLinkError during StageApplication.onCreate()", e)
            initError = e
        } catch (e: Exception) {
            LogWriter.e(TAG, "FATAL: StageApplication.onCreate() failed", e)
            initError = e
        }

        // Enable OHOS HAP mode AFTER super.onCreate() because the JNI methods
        // (including nativeSetOhosHapMode) are registered lazily by
        // AppModeConfig.nativeInitAppMode() → StageJniRegistry::Register().
        try {
            StageApplication.setOhosHapMode(true)
            LogWriter.e(TAG, "setOhosHapMode(true) OK")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "setOhosHapMode not available in current .so — patches inactive", e)
        }
    }

    private fun isHapProcess(): Boolean {
        val name = currentProcessName()
        return name.contains(":hap")
    }

    private fun currentProcessName(): String {
        // ActivityManager.getRunningAppProcesses() works reliably on all API levels.
        val pid = android.os.Process.myPid()
        val manager = getSystemService(android.app.ActivityManager::class.java)
        manager?.runningAppProcesses?.forEach { info ->
            if (info.pid == pid) return info.processName
        }
        return "unknown"
    }

    companion object {
        private const val TAG = "HOA.App"
    }
}
