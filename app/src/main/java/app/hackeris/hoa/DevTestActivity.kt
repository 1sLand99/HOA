package app.hackeris.hoa

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hackeris.hoa.hap.HapExtractor
import java.io.File

class DevTestActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var extractButton: Button
    private lateinit var launchButton: Button

    private val autoLaunch by lazy {
        intent.getBooleanExtra("autoLaunch", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devtest)

        statusText = findViewById(R.id.devtest_status)
        extractButton = findViewById(R.id.devtest_extract_button)
        launchButton = findViewById(R.id.devtest_launch_button)

        if (autoLaunch) {
            extractButton.isEnabled = false
            extractButton.text = "AUTO MODE"
            Log.e(TAG, "Auto-launch mode, extracting and launching...")
            extractAndLaunch()
        } else {
            extractButton.setOnClickListener {
                extractButton.isEnabled = false
                extractButton.text = "Extracting..."
                extractInBackground()
            }
            launchButton.setOnClickListener {
                launchHap()
            }
        }

        refreshStatus()
        Log.e(TAG, "DevTestActivity created, autoLaunch=$autoLaunch")
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
                        Toast.makeText(this, "Test HAP extracted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Extraction failed", Toast.LENGTH_LONG).show()
                    }
                    refreshStatus()
                    extractButton.isEnabled = true
                    extractButton.text = "Re-extract Test HAP from Assets"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extract failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    refreshStatus()
                    extractButton.isEnabled = true
                    extractButton.text = "Extract Test HAP from Assets"
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
                        Toast.makeText(this, "Auto: launching...", Toast.LENGTH_SHORT).show()
                        launchHap()
                    } else {
                        Toast.makeText(this, "Auto: extract FAILED", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto extract+launch failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Auto: error — ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun doExtract(): Boolean {
        return HapExtractor.extractHapToFilesDir(
            this,
            "hap/entry.hap",
            "app.hackeris.harmonyexample",
            "entry"
        )
    }

    private fun isHapExtracted(): Boolean {
        val dir = File(filesDir, "arkui-x/app.hackeris.harmonyexample.entry")
        return dir.isDirectory && File(dir, "module.json").exists()
    }

    private fun launchHap() {
        val slot = ProcessSlotManager.allocateSlot(this)
        if (slot < 0) {
            Toast.makeText(this, "All ${ProcessSlotManager.MAX_SLOTS} process slots in use", Toast.LENGTH_LONG).show()
            Log.w(TAG, "All process slots occupied")
            return
        }
        Log.e(TAG, "Launching test HAP slot=$slot")
        val intent = Intent().apply {
            setClassName(packageName, "${packageName}.HoaAbilityActivity$slot")
            putExtra("BUNDLE_NAME", "app.hackeris.harmonyexample")
            putExtra("MODULE_NAME", "entry")
            putExtra("ABILITY_NAME", "EntryAbility")
            putExtra("PROCESS_SLOT", slot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    private fun refreshStatus() {
        val ready = isHapExtracted()
        if (ready) {
            val modulesAbc = File(filesDir, "arkui-x/app.hackeris.harmonyexample.entry/ets/modules.abc")
            val abcSize = if (modulesAbc.exists()) modulesAbc.length() else 0
            statusText.text = "Test HAP: READY\nmodules.abc: $abcSize bytes"
            launchButton.isEnabled = true
        } else {
            statusText.text = "Test HAP: NOT EXTRACTED"
            launchButton.isEnabled = false
        }
    }

    companion object {
        private const val TAG = "HOA.DevTest"
    }
}
