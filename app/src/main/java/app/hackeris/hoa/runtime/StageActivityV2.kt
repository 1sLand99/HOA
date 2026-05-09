package app.hackeris.hoa.runtime

import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * StageActivityV2 — hosts the self-built OpenHarmony runtime (HongEngine-based).
 *
 * Loads 12 native .so files (ArkCompiler + dependencies), creates an ETS VM,
 * loads a HAP's modules.abc, and executes the entry ability.
 *
 * This is the native OHOS runtime path (Path A), separate from the ArkUI-X
 * prebuilt runtime used by HoaAbilityActivity.
 */
class StageActivityV2 : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "HOA.StageV2"

        // .so load order (dependency chain — MUST be in this order)
        private val LIBS = listOf(
            "c++_shared",       // NDK C++ runtime
            "z",                // libz.so with minizip (before system libz)
            "c_secshared",      // Secure C runtime
            "pandabase",        // Panda base library
            "ziparchive",       // ZIP archive support
            "pandafile",        // ABC file reader
            "arktarget_options",
            "arkassembler",
            "arkaotmanager",
            "arkcompiler",      // JIT compiler
            "arkruntime",       // Core VM runtime (~190MB)
            "hongengine_c",     // C API wrapper
            "hongengine"        // JNI bridge (compiled by CMake)
        )

        // Entry point candidates to try (in order) for ES module HAPs.
        // The first one that resolves wins.
        private val ENTRY_POINT_CANDIDATES = listOf(
            "NONEXISTENT::nonexistent",           // deliberately invalid — test if resolution works
            "entry.ETSGLOBAL::func_main_0",
            "entry/src/main/ets/entryability/EntryAbility.ETSGLOBAL::func_main_0",
            "_GLOBAL::func_main_0",
        )
    }

    private var nativeState: Long = 0
    private var surfaceView: SurfaceView? = null
    private var surfaceCreated = false
    private var runtimeInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "========== StageActivityV2 onCreate ==========")

        // Load native libraries
        loadNativeLibraries()

        // Extract test ABC + ICU data from assets
        extractRuntimeAssets()

        // Extract HAP if path provided
        extractHapIfPresent()

        // Create SurfaceView for future ACE rendering
        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
        }
        setContentView(surfaceView)
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        if (nativeState != 0L) nativeOnShow(nativeState)
    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause")
        if (nativeState != 0L) nativeOnHide(nativeState)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "onDestroy")
        if (nativeState != 0L) {
            nativeDestroy(nativeState)
            nativeState = 0
        }
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.e(TAG, "surfaceCreated")
        surfaceCreated = true
        tryInitRuntime(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.e(TAG, "surfaceChanged: ${width}x${height}")
        if (nativeState != 0L) nativeSurfaceChanged(nativeState, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.e(TAG, "surfaceDestroyed")
        surfaceCreated = false
        if (nativeState != 0L) nativeSurfaceDestroyed(nativeState)
    }

    // ---- Initialization ----

    private fun tryInitRuntime(surface: Surface) {
        if (runtimeInitialized) return

        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: "app.hackeris.harmonyexample"
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: "entry"
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"

        Log.e(TAG, "Initializing runtime for: $bundleName:$moduleName:$abilityName")

        // Initialize native state
        nativeState = nativeInit(assets, filesDir.absolutePath)
        Log.e(TAG, "nativeInit returned: $nativeState")

        if (nativeState != 0L) {
            nativeSetSurface(nativeState, surface)
            runtimeInitialized = true

            // Find the ABC file
            val abcFile = findAbcFile(bundleName, moduleName)
            if (abcFile != null) {
                val isHapModule = (abcFile.name == "modules.abc")

                // Step 1: Create runtime (executes default _GLOBAL::main — expected to fail for HAP)
                val firstResult = nativeRunPage(nativeState, bundleName, abilityName,
                                               abcFile.absolutePath, null)
                Log.e(TAG, "nativeRunPage (default entry) result: $firstResult")

                // Step 2: If HAP module, try ES module entry points
                if (isHapModule) {
                    tryEntryPoints()
                }

                if (firstResult != "OK") {
                    Toast.makeText(this, "Default entry failed. Check logcat.", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.e(TAG, "No ABC file found for $bundleName/$moduleName")
                Toast.makeText(this, "No modules.abc found for $bundleName/$moduleName", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Try each entry point candidate on the already-created VM.
     */
    private fun tryEntryPoints() {
        for (ep in ENTRY_POINT_CANDIDATES) {
            Log.e(TAG, "Trying entry point: $ep")
            val result = nativeTryEntryPoint(nativeState, ep)
            Log.e(TAG, "nativeTryEntryPoint[$ep] result: $result")
            if (result == "OK") {
                Log.e(TAG, "Entry point SUCCESS: $ep")
                Toast.makeText(this, "HAP launched via: $ep", Toast.LENGTH_LONG).show()
                return
            }
        }

        Log.e(TAG, "All entry point candidates failed")
        Toast.makeText(this, "All entry points failed. Check logcat for details.", Toast.LENGTH_LONG).show()
    }

    /**
     * Extract a HAP zip to filesDir/hap_extracted/ if HAP_PATH intent extra is set.
     */
    private fun extractHapIfPresent() {
        val hapPath = intent.getStringExtra("HAP_PATH")
        if (hapPath == null) {
            Log.e(TAG, "No HAP_PATH intent extra — will look for extracted HAP or test ABC")
            return
        }

        val hapFile = File(hapPath)
        if (!hapFile.isFile) {
            Log.e(TAG, "HAP file not found: $hapPath")
            return
        }

        val destDir = File(filesDir, "hap_extracted")
        if (destDir.exists() && File(destDir, "ets/modules.abc").isFile) {
            Log.e(TAG, "HAP already extracted to ${destDir.absolutePath}")
            return
        }

        destDir.deleteRecursively()
        destDir.mkdirs()

        try {
            ZipFile(hapFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val target = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            val abcFile = File(destDir, "ets/modules.abc")
            Log.e(TAG, "HAP extracted OK. modules.abc size=${if (abcFile.isFile) abcFile.length() else -1}")
        } catch (e: Exception) {
            Log.e(TAG, "HAP extraction failed: ${e.message}")
        }
    }

    private fun findAbcFile(bundleName: String, moduleName: String): File? {
        // 1. Check extracted HAP dir
        val hapDir = File(filesDir, "hap_extracted")
        val hapAbc = File(hapDir, "ets/modules.abc")
        if (hapAbc.isFile) {
            Log.e(TAG, "Found HAP ABC: ${hapAbc.absolutePath}")
            return hapAbc
        }

        // 2. Check dynamic HAP dir (filesDir/arkui-x/$bundleName.$moduleName/)
        val dynamicDir = File(filesDir, "arkui-x/$bundleName.$moduleName")
        val dynamicAbc = File(dynamicDir, "ets/modules.abc")
        if (dynamicAbc.isFile) {
            Log.e(TAG, "Found dynamic ABC: ${dynamicAbc.absolutePath}")
            return dynamicAbc
        }

        // 3. Fallback: test hello.abc
        val testAbc = File(filesDir, "test/hello.abc")
        if (testAbc.isFile) {
            Log.e(TAG, "Using test ABC: ${testAbc.absolutePath}")
            return testAbc
        }

        return null
    }

    private fun extractRuntimeAssets() {
        val testDir = File(filesDir, "test")
        testDir.mkdirs()

        // Extract hello.abc
        val helloAbc = File(testDir, "hello.abc")
        if (!helloAbc.exists()) {
            try {
                assets.open("arkui-x/test/hello.abc").use { input ->
                    helloAbc.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.e(TAG, "Extracted hello.abc (${helloAbc.length()} bytes)")
            } catch (_: Exception) {
                Log.e(TAG, "No hello.abc in assets — will need a HAP to be installed")
            }
        }

        // Extract etsstdlib.abc
        val stdlibAbc = File(testDir, "etsstdlib.abc")
        if (!stdlibAbc.exists()) {
            try {
                assets.open("arkui-x/test/etsstdlib.abc").use { input ->
                    stdlibAbc.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.e(TAG, "Extracted etsstdlib.abc (${stdlibAbc.length()} bytes)")
            } catch (_: Exception) {
                Log.e(TAG, "No etsstdlib.abc in assets — ETS runtime will need it")
            }
        }
    }

    // ---- Native library loading ----

    private fun loadNativeLibraries() {
        for (lib in LIBS) {
            try {
                System.loadLibrary(lib)
                Log.e(TAG, "Loaded: lib$lib.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "FAILED to load lib$lib.so: ${e.message}")
                if (lib !in listOf("c++_shared", "hongengine")) {
                    // c++_shared may be loaded by app already; hongengine is compiled by CMake
                    throw e
                }
            }
        }
        Log.e(TAG, "All native libraries loaded OK")
    }

    // ---- JNI native methods ----

    private external fun nativeInit(assetMgr: android.content.res.AssetManager, filesDir: String): Long
    private external fun nativeSetSurface(ptr: Long, surface: Surface)
    private external fun nativeSurfaceChanged(ptr: Long, width: Int, height: Int)
    private external fun nativeSurfaceDestroyed(ptr: Long)
    private external fun nativeRunPage(ptr: Long, bundleName: String, abilityName: String,
                                       abcPath: String, entryPoint: String?): String
    private external fun nativeTryEntryPoint(ptr: Long, entryPoint: String): String
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeOnShow(ptr: Long)
    private external fun nativeOnHide(ptr: Long)
}
