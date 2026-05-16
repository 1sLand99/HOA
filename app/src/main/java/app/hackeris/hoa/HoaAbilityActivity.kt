package app.hackeris.hoa

import android.os.Bundle
import android.util.Log
import ohos.stage.ability.adapter.StageActivity

open class HoaAbilityActivity : StageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: "app.hackeris.harmonyexample"
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: "entry"
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: "EntryAbility"
        val slot = intent.getIntExtra("PROCESS_SLOT", -1)

        if (slot >= 0) {
            ProcessSlotManager.claimSlot(this, slot)
            Log.e(TAG, "Process slot $slot claimed, PID=${android.os.Process.myPid()}")
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
            android.widget.Toast.makeText(this, "Module not found: $bundleName/$moduleName", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            setInstanceName(instanceName)
            Log.e(TAG, "setInstanceName() OK")
        } catch (e: Exception) {
            Log.e(TAG, "setInstanceName() FAILED", e)
        }

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
