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
        applyWindowInsetsPadding()
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

    // ================================================================
    // setRequestedOrientation — MIUI Activity-restart workaround
    // ================================================================
    //
    // Background:
    //   OHOS HAPs call setMainWindowOrientation(portrait/landscape/...)
    //   expecting a lightweight, non-destructive window-level change.
    //   The call chain is:
    //
    //     ArkTS: window.setMainWindowOrientation(portrait)
    //       → NAPI: JsWindow::OnSetPreferredOrientation
    //       → C++:  VirtualRSWindow::SetRequestedOrientation
    //       → JNI:  SubWindowManagerJni::RequestOrientation
    //       → Java: SubWindowManager.requestOrientation()
    //               → mRootActivity.setRequestedOrientation(...)
    //
    //   On standard Android, configChanges="orientation|screenSize" in the
    //   manifest means the Activity is NOT restarted — onConfigurationChanged
    //   fires instead.  On MIUI (Xiaomi), however, setRequestedOrientation()
    //   bypasses configChanges entirely: the OS destroys the Activity and
    //   cold-starts a new process.  isChangingConfigurations is false during
    //   this destroy, so it is indistinguishable from a "real" finish().
    //
    //   The process restart drops the entire ArkUI navigation stack and the
    //   user sees the HAP home page instead of the current page.  Example:
    //   BackdropBlurStyleTabBar in ui-examples — renders briefly then white
    //   screen (before this fix) or snaps back to home (with onDestroy fix
    //   alone).
    //
    // Workaround:
    //   We compare the device's current orientation (from Configuration) with
    //   the target orientation encoded in the requestedOrientation flag:
    //
    //     - Match (same orientation):  safe — forward to the real
    //       setRequestedOrientation().  Calling it with the already-active
    //       value is a no-op at the OS level and won't trigger a restart.
    //
    //     - Mismatch (real switch):  blocked — the MIUI restart would nuke
    //       the ArkUI runtime.  Log a warning so the limitation is visible.
    //
    //   Most HAP calls are of the "match" variety (e.g. a portrait HAP
    //   locking itself to portrait on an already-portrait device), so this
    //   handles the common case correctly.
    //
    // Long-term fix:
    //   SubWindowManager.java in ArkUI-X should avoid Activity-level
    //   setRequestedOrientation() and use a lower-level API (e.g.
    //   WindowManager / Display rotation) that doesn't trigger an Activity
    //   lifecycle transition on MIUI.
    // ================================================================
    override fun setRequestedOrientation(requestedOrientation: Int) {
        val config = resources.configuration
        val currentOrientation = config.orientation
        val targetOrientation = when (requestedOrientation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT ->
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ->
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            else -> currentOrientation  // UNSPECIFIED, BEHIND, etc. — don't change
        }

        if (targetOrientation == currentOrientation) {
            Log.e(TAG, "setRequestedOrientation($requestedOrientation) — already in target orientation, forwarding")
            super.setRequestedOrientation(requestedOrientation)
        } else {
            Log.e(TAG, "setRequestedOrientation($requestedOrientation) — BLOCKED: " +
                "current=$currentOrientation target=$targetOrientation. " +
                "Would trigger MIUI Activity restart and lose ArkUI navigation state.")
        }
    }

    // ================================================================
    // onDestroy — only kill process on "real" destroy, not config changes
    // ================================================================
    //
    // Why we kill the process on destroy:
    //   Each HAP runs in an isolated process (:hap0–:hap9).  The ArkUI-X
    //   StageApplication / ResourceManager is process-global and
    //   AddResource() fails if called a second time with the same path.
    //   Killing the process guarantees a clean slate for the next launch.
    //
    // Why we DON'T kill on config changes:
    //   When isChangingConfigurations == true, the OS is destroying the
    //   Activity temporarily (e.g. physical device rotation) and will
    //   recreate it immediately in the same process.  Killing here would
    //   leave the user staring at a dead window.
    //
    // NOTE: setRequestedOrientation() on MIUI triggers a destroy with
    //       isChangingConfigurations == FALSE (see setRequestedOrientation
    //       override above).  That path is blocked before it reaches
    //       onDestroy, so this check alone was not sufficient — the
    //       setRequestedOrientation workaround is also required.
    // ================================================================
    override fun onDestroy() {
        Log.e(TAG, "onDestroy — UIAbility.onDestroy() should fire, isChangingConfigurations=$isChangingConfigurations")
        val slot = intent.getIntExtra("PROCESS_SLOT", -1)
        if (slot >= 0) {
            ProcessSlotManager.releaseSlot(this, slot)
            Log.e(TAG, "Process slot $slot released")
        }
        super.onDestroy()
        if (isChangingConfigurations) {
            Log.e(TAG, "onDestroy — skipping killProcess (config change, activity will be recreated)")
            return
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // Apply Android system window insets (status bar, navigation bar) as
    // padding on the root content view so ArkUI content doesn't render under
    // the system bars.  Without this, HAPs using expandSafeArea(TOP) bleed
    // into the status bar and become unreadable / unclickable.
    private fun applyWindowInsetsPadding() {
        val rootView = window.decorView.findViewById<android.view.View>(android.R.id.content)
        if (rootView != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
                val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                view.setPadding(0, statusBars.top, 0, navBars.bottom)
                insets
            }
        }
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
