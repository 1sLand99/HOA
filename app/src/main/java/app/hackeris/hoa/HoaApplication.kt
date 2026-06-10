package app.hackeris.hoa

import android.webkit.WebView
import app.hackeris.hoa.logging.LogWriter
import ohos.stage.ability.adapter.StageApplication
import ohos.stage.ability.adapter.StageApplicationDelegate
import java.io.File

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
        // Android 10+ requires setDataDirectorySuffix for WebView multi-process.
        // Without this, the second HAP process that creates a WebView gets a
        // white screen because the WebView rendering engine data directory is
        // locked by the first process.  Each process gets its own suffix so
        // they can coexist.  Colon (:) in process names is replaced with '_'.
        val suffix = currentProcessName().replace(':', '_')
        try {
            WebView.setDataDirectorySuffix(suffix)
            LogWriter.i(TAG, "WebView.setDataDirectorySuffix(\"$suffix\") — OK")
        } catch (e: Exception) {
            LogWriter.e(TAG, "WebView.setDataDirectorySuffix(\"$suffix\") — FAILED", e)
        }

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

        // Pre-load HMS Account stub so NAPI module "core.account.extendservice"
        // is registered before any HAP tries to import it.
        try {
            System.loadLibrary("hms_account")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_account\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_account\") — FAILED", e)
        }

        // Pre-load HMS IAP + Payment stubs so NAPI modules "core.iap"
        // and "core.payment.paymentService" are registered before any HAP
        // tries to import them.
        try {
            System.loadLibrary("hms_iap")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_iap\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_iap\") — FAILED", e)
        }

        // Pre-load @kit.ShareKit stub so NAPI module "collaboration.systemShare"
        // is registered before any HAP tries to import it.
        try {
            System.loadLibrary("hms_share")
            LogWriter.e(TAG, "System.loadLibrary(\"hms_share\") — SUCCESS")
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.e(TAG, "System.loadLibrary(\"hms_share\") — FAILED", e)
        }

        // ——— HOA: redirect storage (Preferences/RDB/KV Store) to per-HAP dir ———
        // By default ArkUI-X sets filesDir to the app-level path so all HAPs
        // share files/preference/, files/database/, etc.  We override with the
        // HAP module directory so each HAP gets its own isolated data sandbox.
        redirectStorageToHapModule()

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

    /**
     * Redirect ArkUI-X storage (Preferences, RDB, KV Store) from the shared
     * app-level filesDir to the per-HAP module directory.
     *
     * Without this, all HAPs share files/preference/, files/database/, etc.
     * and a store named "settings" in two different HAPs would conflict.
     *
     * Called BEFORE [super.onCreate] so that createStagePath() picks up
     * the HAP-specific data directory.
     */
    private fun redirectStorageToHapModule() {
        val procName = currentProcessName()
        val slot = extractSlot(procName)
        if (slot < 0) {
            LogWriter.w(TAG, "redirectStorageToHapModule: cannot parse slot from '$procName'")
            return
        }

        val contentDir = ProcessSlotManager.getContentDir(this, slot)
        if (contentDir == null) {
            LogWriter.w(TAG, "redirectStorageToHapModule: no contentDir for slot $slot — " +
                "slot may not have been allocated with contentDir yet")
            return
        }

        // Ensure storage subdirectories exist under the HAP module dir
        for (sub in arrayOf("temp", "files", "preference", "database", "cache")) {
            File(contentDir, sub).mkdirs()
        }

        StageApplicationDelegate.sModuleDataDir = contentDir
        LogWriter.e(TAG, "redirectStorageToHapModule: data dir → $contentDir")
    }

    /**
     * Extracts the slot number from a HAP process name like
     * "app.hackeris.hoa:hap3" → 3.  Returns -1 on failure.
     */
    private fun extractSlot(procName: String): Int {
        val idx = procName.lastIndexOf(":hap")
        if (idx < 0) return -1
        return procName.substring(idx + 4).toIntOrNull() ?: -1
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
