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
        Log.e(TAG, "Process: ${android.os.Process.myPid()}")
        Log.e(TAG, "ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

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

        // Run Stage 1 verification checklist
        try {
            val results = Stage1Verifier.runAll(this)
            val failed = results.count { !it.passed }
            if (failed > 0) {
                Log.w(TAG, "Stage 1 verification: $failed check(s) FAILED — see HOA.Verify logs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stage 1 verifier crashed", e)
        }

        Log.e(TAG, "========== HOA Application onCreate END ==========")
    }

    companion object {
        private const val TAG = "HOA.App"
    }
}
