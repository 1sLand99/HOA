package app.hackeris.hoa

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hackeris.hoa.hap.HapExtractor
import app.hackeris.hoa.hap.HapInstaller
import java.io.File

class DevTestActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var extractButton: Button
    private lateinit var launchButton: Button

    // If provided via intent extras, these override the hardcoded test HAP.
    private val targetBundle by lazy { intent.getStringExtra("targetBundle") }
    private val targetModule by lazy { intent.getStringExtra("targetModule") }
    private val targetAbility by lazy { intent.getStringExtra("targetAbility") }
    private val autoLaunch by lazy { intent.getBooleanExtra("autoLaunch", false) }
    // Path to a HAP file on device to install before launching.
    private val installHapPath by lazy { intent.getStringExtra("installHapPath") }

    // --- Default (hardcoded) test HAP ---
    private val defaultBundle = "app.hackeris.harmonyexample"
    private val defaultModule = "entry"
    private val defaultAbility = "EntryAbility"

    private val bundleName get() = targetBundle ?: defaultBundle
    private val moduleName get() = targetModule ?: defaultModule
    private val abilityName get() = targetAbility ?: defaultAbility

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devtest)

        statusText = findViewById(R.id.devtest_status)
        extractButton = findViewById(R.id.devtest_extract_button)
        launchButton = findViewById(R.id.devtest_launch_button)

        if (autoLaunch) {
            extractButton.isEnabled = false
            extractButton.text = getString(R.string.btn_auto_mode)
            Log.e(TAG, "Auto-launch mode bundle=$bundleName module=$moduleName ability=$abilityName installHapPath=$installHapPath")

            if (installHapPath != null) {
                // Install HAP from device path, then launch.
                installAndLaunch()
            } else if (targetBundle != null) {
                // External target — HAP is already installed via MainActivity.
                // No extraction needed.
                launchHap()
            } else {
                // Default test HAP from assets — extract first.
                extractAndLaunch()
            }
        } else {
            extractButton.setOnClickListener {
                extractButton.isEnabled = false
                extractButton.text = getString(R.string.btn_extracting)
                extractInBackground()
            }
            launchButton.setOnClickListener {
                launchHap()
            }
        }

        refreshStatus()
        Log.e(TAG, "DevTestActivity created, autoLaunch=$autoLaunch params=${targetBundle != null}")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun extractInBackground() {
        Thread {
            try {
                val ok = doExtract()
                runOnUiThread {
                    if (ok) {
                        Toast.makeText(this, getString(R.string.toast_extracted), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.toast_extract_failed), Toast.LENGTH_LONG).show()
                    }
                    refreshStatus()
                    extractButton.isEnabled = true
                    extractButton.text = getString(R.string.btn_re_extract)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extract failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_error_fmt, e.message), Toast.LENGTH_LONG).show()
                    refreshStatus()
                    extractButton.isEnabled = true
                    extractButton.text = getString(R.string.btn_extract_hap)
                }
            }
        }.start()
    }

    private fun installAndLaunch() {
        val path = installHapPath ?: return
        Thread {
            try {
                val hapFile = File(path)
                if (!hapFile.exists()) {
                    runOnUiThread {
                        Toast.makeText(this, "HAP file not found: $path", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                Log.e(TAG, "Installing HAP from $path ...")
                val installed = HapInstaller(this).install(hapFile.absolutePath)
                Log.e(TAG, "Installed OK: bundle=${installed.bundleName} module=${installed.moduleName} ability=${installed.mainAbility}")
                runOnUiThread {
                    refreshStatus()
                    Toast.makeText(this, "Installed ${installed.bundleName}/${installed.mainAbility}", Toast.LENGTH_SHORT).show()
                    launchHap(installed.bundleName, installed.moduleName, installed.mainAbility)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun extractAndLaunch() {
        Thread {
            try {
                val ready = isHapExtracted() || doExtract()
                runOnUiThread {
                    refreshStatus()
                    if (ready) {
                        Toast.makeText(this, getString(R.string.toast_auto_launching), Toast.LENGTH_SHORT).show()
                        launchHap()
                    } else {
                        Toast.makeText(this, getString(R.string.toast_auto_extract_failed), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto extract+launch failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_auto_error_fmt, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun doExtract(): Boolean {
        return HapExtractor.extractHapToFilesDir(
            this,
            "hap/entry.hap",
            bundleName,
            moduleName
        )
    }

    private fun isHapExtracted(): Boolean {
        val dir = File(filesDir, "hap/$bundleName.$moduleName")
        return dir.isDirectory && File(dir, "module.json").exists()
    }

    private fun launchHap() {
        launchHap(bundleName, moduleName, abilityName)
    }

    private fun launchHap(bundle: String, module: String, ability: String) {
        // Validate target module exists on disk.
        val fullName = "$bundle.$module"
        val moduleDir = File(filesDir, "hap/$fullName")
        if (!moduleDir.isDirectory || moduleDir.listFiles()?.isEmpty() == true) {
            Toast.makeText(this, getString(R.string.toast_module_not_found, bundle, module), Toast.LENGTH_LONG).show()
            Log.e(TAG, "Target module not found: $fullName")
            return
        }

        if (ProcessSlotManager.launchHap(this, bundle, module, ability) < 0) {
            Toast.makeText(this, getString(R.string.toast_slots_full_fmt, ProcessSlotManager.MAX_SLOTS), Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        val ready = isHapExtracted()
        if (ready) {
            val modulesAbc = File(filesDir, "hap/$bundleName.$moduleName/ets/modules.abc")
            val abcSize = if (modulesAbc.exists()) modulesAbc.length() else 0
            statusText.text = getString(R.string.devtest_status_ready_fmt, abcSize)
            launchButton.isEnabled = true
            launchButton.text = getString(R.string.btn_launch_test_hap)
        } else {
            statusText.text = getString(R.string.devtest_status_not_extracted)
            launchButton.isEnabled = targetBundle != null  // allow direct launch of installed HAPs
            if (targetBundle != null) {
                launchButton.text = "Launch $bundleName"
            }
        }
    }

    companion object {
        private const val TAG = "HOA.DevTest"
    }
}
