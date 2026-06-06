package app.hackeris.hoa

import android.app.ActivityManager
import android.os.Bundle
import android.util.Log
import ohos.stage.ability.adapter.StageActivity
import org.json.JSONObject
import java.io.File

open class HoaAbilityActivity : StageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: "app.hackeris.harmonyexample"
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: "entry"
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"
        val slot = intent.getIntExtra("PROCESS_SLOT", -1)

        if (slot >= 0) {
            val contentDir = java.io.File(filesDir, "hap/$bundleName.$moduleName").absolutePath
            ProcessSlotManager.claimSlot(this, slot, contentDir)
            Log.e(TAG, "Process slot $slot claimed, PID=${android.os.Process.myPid()}, contentDir=$contentDir")
        }

        val instanceName = "$bundleName:$moduleName:$abilityName:"
        Log.e(TAG, "========== HoaAbilityActivity onCreate START ==========")
        Log.e(TAG, "bundleName=$bundleName, moduleName=$moduleName, abilityName=$abilityName")
        Log.e(TAG, "instanceName=$instanceName, slot=$slot")

        // Check if StageApplication init succeeded
        val app = applicationContext as? HoaApplication
        if (app != null && !app.initSuccess) {
            Log.e(TAG, "StageApplication init FAILED — ArkUI rendering will not work")
            Log.e(TAG, "  Error was: ${app.initError?.message}")
        }

        // Verify module exists before handing off to ArkUI-X runtime.
        // If the module is not found, the runtime creates a null stage and crashes
        // with StackOverflow in WindowViewSurface.onHoverEvent.
        if (!moduleExists(bundleName, moduleName)) {
            Log.e(TAG, "Module not found: bundleName=$bundleName, moduleName=$moduleName")
            Log.e(TAG, "  Checked: filesDir/hap/$bundleName.$moduleName/")
            android.widget.Toast.makeText(this, getString(R.string.toast_module_not_found, bundleName, moduleName), android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            setInstanceName(instanceName)
            Log.e(TAG, "setInstanceName() OK")
        } catch (e: Exception) {
            Log.e(TAG, "setInstanceName() FAILED", e)
        }

        // Preload all .so files from the HAP libs directory so that
        // DT_NEEDED dependencies within the HAP resolve correctly.
        // Without this, libentry.so → libhelper.so fails because the
        // linker does not search the HAP extraction directory by default.
        preloadNativeDeps(bundleName, moduleName)

        try {
            super.onCreate(savedInstanceState)
            Log.e(TAG, "super.onCreate() completed — ArkUI rendering surface should be created")
            Log.e(TAG, "instanceId=${getInstanceId()}, instanceName=${getInstanceName()}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "FATAL: Native library link error during Activity onCreate", e)
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Activity onCreate failed", e)
        }

        Log.e(TAG, "========== HoaAbilityActivity onCreate END ==========")
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Apply HAP metadata (title + icon) AFTER super.onCreate() so that
        // any title/task-description set by StageActivity is overwritten.
        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: "app.hackeris.harmonyexample"
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: "entry"
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"
        applyHapTaskDescription(bundleName, moduleName, abilityName)
    }

    override fun onResume() {
        Log.e(TAG, "onResume — UIAbility.onForeground() should fire")
        super.onResume()
    }

    override fun onPause() {
        Log.e(TAG, "onPause — UIAbility.onBackground() should fire")
        super.onPause()
    }

    override fun onDestroy() {
        Log.e(TAG, "onDestroy — UIAbility.onDestroy() should fire")
        val slot = intent.getIntExtra("PROCESS_SLOT", -1)
        if (slot >= 0) {
            ProcessSlotManager.releaseSlot(this, slot)
            Log.e(TAG, "Process slot $slot released")
        }
        super.onDestroy()
        // Kill the process so the next launch gets a fresh ArkUI-X runtime.
        // The ResourceManager in StageApplication is process-global and
        // AddResource() fails if called again with the same path.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onBackPressed() {
        Log.e(TAG, "onBackPressed")
        super.onBackPressed()
    }

    // ============================================================
    // Apply the HAP's app name and icon to the Android Activity,
    // so the task switcher / recents screen shows the HAP identity
    // rather than the host app's "HOA" label and launcher icon.
    // ============================================================
    //
    // Limitations (by design — kept simple intentionally):
    //
    //   1. Title: String resources referenced by module.json (e.g.
    //      "$string:app_name") are stored in the binary resources.index.
    //      Without a parser for that format, the actual display string
    //      is not accessible.  We fall back to using the bundleName,
    //      which is always available and human-recognisable for most
    //      real-world HAPs (e.g. "top.wangchenyan.wanharmony" →
    //      "wanharmony" or "top.wangchenyan.wanharmony").
    //
    //   2. Icon:  Resource references like "$media:icon" are resolved
    //      by scanning resources/base/media/ for {name}.{ext}.  Only
    //      the "base" density bucket is checked.  HAPs that place the
    //      icon exclusively in density-specific directories (e.g.
    //      resources/xxxhdpi/media/) will not be matched.  SVG icons
    //      are skipped because Android cannot decode them natively.
    //
    //   3. The icon loaded here is used for the task-switcher thumbnail
    //      only.  It does NOT change the launcher icon (the host APK
    //      icon remains) and does NOT affect the splash / start-window
    //      shown during cold start.
    //
    private fun applyHapTaskDescription(
        bundleName: String, moduleName: String, abilityName: String
    ) {
        val fullModuleName = "$bundleName.$moduleName"
        val moduleJsonFile = File(filesDir, "hap/$fullModuleName/module.json")
        if (!moduleJsonFile.exists()) {
            setTitle(bundleName)
            return
        }

        var displayName = bundleName

        try {
            val json = JSONObject(moduleJsonFile.readText())

            // Prefer the app.label from module.json.
            val appObj = json.optJSONObject("app")
            val rawLabel = appObj?.optString("label", "") ?: ""
            if (rawLabel.isNotBlank()) {
                if (rawLabel.startsWith("\$string:")) {
                    val key = rawLabel.removePrefix("\$string:")
                    val moduleDir = File(filesDir, "hap/$fullModuleName")
                    val resolved = app.hackeris.hoa.hap.HapBundleLoader.parseStringFromIndex(moduleDir, key)
                    if (resolved != null) displayName = resolved
                } else {
                    displayName = rawLabel
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyHapTaskDescription failed", e)
        }

        val bitmap = app.hackeris.hoa.hap.HapBundleLoader.loadHapIcon(
            File(filesDir, "hap/$fullModuleName")
        )

        setTitle(displayName)
        @Suppress("DEPRECATION")
        if (bitmap != null) {
            setTaskDescription(ActivityManager.TaskDescription(displayName, bitmap))
        } else {
            setTaskDescription(ActivityManager.TaskDescription(displayName))
        }
        Log.e(TAG, "applyHapTaskDescription: title=$displayName icon=${bitmap != null}")
    }

    // ============================================================
    // Preload all .so files from the HAP libs directory.
    //
    // HAP .so files that DT_NEEDED other .so files in the same HAP
    // (e.g. libentry.so → libhelper.so) can't resolve those deps
    // because the linker does not search the extraction directory.
    //
    // By loading all .so files via System.load() before ArkUI-X
    // dlopen's the main module, every dependency is already in
    // memory and DT_NEEDED resolution succeeds by refcount bump.
    //
    // Files whose own deps are not yet satisfied will fail (caught);
    // they will be loaded later by ArkUI-X once their deps are met.
    // ============================================================
    private fun preloadNativeDeps(bundleName: String, moduleName: String) {
        val fullModuleName = "$bundleName.$moduleName"
        val libsDir = File(filesDir, "hap/$fullModuleName/libs")
        if (!libsDir.isDirectory) {
            Log.e(TAG, "preloadNativeDeps: libs dir not found at $libsDir")
            return
        }

        val nativeAbi = android.os.Build.SUPPORTED_ABIS[0]
        val soFiles = libsDir.walkTopDown()
            .filter { it.isFile && it.extension == "so" && it.parentFile?.name == nativeAbi }
            .toList()

        if (soFiles.isEmpty()) {
            Log.e(TAG, "preloadNativeDeps: no .so files found")
            return
        }

        // Patch every .so's RUNPATH to "$ORIGIN" as a best-effort fix.
        // HAPs built without RUNPATH (CMAKE_SKIP_RPATH TRUE) have none;
        // topological dependency loading in nativeLoad handles those.
        for (soFile in soFiles) {
            val patched = app.hackeris.hoa.hap.ElfPatcher.patchRunpath(soFile.absolutePath)
            if (patched) {
                Log.e(TAG, "preloadNativeDeps: RUNPATH→\$ORIGIN ${soFile.name}")
            }
        }

        // With RUNPATH=$ORIGIN the linker can walk DT_NEEDED chains
        // transitively, so topological order no longer matters.
        // Still do multi-pass for robustness in case some .so files
        // have non-trivial dependency shapes.
        Log.e(TAG, "preloadNativeDeps: found ${soFiles.size} .so file(s)")
        var remaining = soFiles.toMutableList()
        var pass = 0
        while (remaining.isNotEmpty()) {
            pass++
            var progress = false
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val soFile = iter.next()
                val ret = app.hackeris.hoa.hap.ElfPatcher.nativeLoad(soFile.absolutePath)
                if (ret == 0) {
                    iter.remove()
                    progress = true
                    Log.e(TAG, "preloadNativeDeps: loaded ${soFile.name} (pass $pass)")
                    // Patch GOT entries to redirect sigaction to libb.so bridge
                    val n = app.hackeris.hoa.hap.ElfPatcher.patchGot(soFile.absolutePath)
                    if (n > 0) Log.e(TAG, "preloadNativeDeps: patched $n sigaction GOT entries in ${soFile.name}")
                } else {
                    Log.w(TAG, "preloadNativeDeps: FAILED ${soFile.name} (nativeLoad returned $ret)")
                }
            }
            if (!progress) break
        }

        if (remaining.isNotEmpty()) {
            Log.w(TAG, "preloadNativeDeps: ${remaining.size} .so(s) still unresolved after $pass passes: ${remaining.map { it.name }}")
        }
    }

    private fun moduleExists(bundleName: String, moduleName: String): Boolean {
        // Check app data dir: filesDir/hap/$bundleName.$moduleName/
        val fullName = "$bundleName.$moduleName"
        val dynamicDir = java.io.File(filesDir, "hap/$fullName")
        if (dynamicDir.isDirectory && dynamicDir.listFiles()?.isNotEmpty() == true) return true

        return false
    }


    companion object {
        private const val TAG = "HOA.Ability"
    }
}
