package app.hackeris.hoa

import android.util.Log
import ohos.stage.ability.adapter.StageApplication

class HoaApplication : StageApplication() {

    var initSuccess = false
        private set

    var initError: Throwable? = null
        private set

    override fun onCreate() {
        Log.e(TAG, "========== HOA Application onCreate START ==========")
        Log.e(TAG, "Process: ${android.os.Process.myPid()}  Name: ${currentProcessName()}")

        // Only initialize the ArkUI-X runtime (libarkui_android.so, ~79 MB) in
        // HAP worker processes (":hap*").  The main process only hosts
        // MainActivity and does not need the native engine at all.
        // This shaves ~1-2 s off the cold-start time of the launcher activity.
        if (isHapProcess()) {
            Log.e(TAG, "HAP process detected — initializing ArkUI-X runtime")
            initArkUIX()
        } else {
            Log.e(TAG, "Main process — skipping ArkUI-X init (not needed for launcher)")
            initSuccess = true   // main process is always "ready"
        }

        Log.e(TAG, "========== HOA Application onCreate END ==========")
    }

    private fun initArkUIX() {
        // Try explicit native library load first for better error reporting
        try {
            System.loadLibrary("arkui_android")
            Log.e(TAG, "System.loadLibrary(\"arkui_android\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "System.loadLibrary(\"arkui_android\") — FAILED", e)
            Log.e(TAG, "  nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
            val libFile = java.io.File(applicationInfo.nativeLibraryDir, "libarkui_android.so")
            Log.e(TAG, "  libarkui_android.so exists: ${libFile.exists()}, size: ${if (libFile.exists()) libFile.length() else 0}")
            initError = e
        }

        // Pre-load HDS (Huawei Design System) stub so its NAPI module
        // "hds.hdsBaseComponent" is registered before any HAP tries to
        // import "@hms:hds.hdsBaseComponent".
        try {
            System.loadLibrary("hms_hds")
            Log.e(TAG, "System.loadLibrary(\"hms_hds\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "System.loadLibrary(\"hms_hds\") — FAILED", e)
        }

        try {
            super.onCreate()
            initSuccess = true
            Log.e(TAG, "StageApplication.onCreate() completed successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "FATAL: UnsatisfiedLinkError during StageApplication.onCreate()", e)
            initError = e
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: StageApplication.onCreate() failed", e)
            initError = e
        }

        // Enable OHOS HAP mode AFTER super.onCreate() because the JNI methods
        // (including nativeSetOhosHapMode) are registered lazily by
        // AppModeConfig.nativeInitAppMode() → StageJniRegistry::Register().
        try {
            StageApplication.setOhosHapMode(true)
            Log.e(TAG, "setOhosHapMode(true) OK")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "setOhosHapMode not available in current .so — patches inactive", e)
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
