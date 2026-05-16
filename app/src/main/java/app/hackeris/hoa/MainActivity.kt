package app.hackeris.hoa

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hackeris.hoa.hap.HapInstaller
import app.hackeris.hoa.hap.InstalledHap

class MainActivity : AppCompatActivity() {

    private lateinit var installer: HapInstaller
    private lateinit var hapList: ListView
    private lateinit var emptyHint: TextView
    private lateinit var installButton: Button
    private lateinit var runtimeStatus: TextView

    private val hapAdapter = HapListAdapter()
    private var installedHaps = listOf<InstalledHap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installer = HapInstaller(this)
        runtimeStatus = findViewById(R.id.runtime_status)
        hapList = findViewById(R.id.hap_list)
        emptyHint = findViewById(R.id.empty_hint)
        installButton = findViewById(R.id.install_button)

        hapList.adapter = hapAdapter
        hapList.setOnItemClickListener { _, _, position, _ ->
            val hap = installedHaps[position]
            launchHap(hap)
        }
        hapList.setOnItemLongClickListener { _, _, position, _ ->
            val hap = installedHaps[position]
            confirmUninstall(hap)
            true
        }

        installButton.setOnClickListener {
            openHapPicker()
        }

        updateRuntimeStatus()
        Log.e(TAG, "========== HOA MainActivity START ==========")
    }

    override fun onResume() {
        super.onResume()
        refreshHapList()
    }

    private fun updateRuntimeStatus() {
        val nativeLibs = listOf(
            "libarkui_android.so",
            "libhilog.so",
            "libarkui_componentsnapshot.so",
            "libarkui_focuscontroller.so"
        )
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val allLibsPresent = nativeLibs.all { lib ->
            java.io.File(nativeLibDir, lib).exists()
        }

        val runtime = if (allLibsPresent) "OK" else "INCOMPLETE"
        runtimeStatus.text = "Runtime: $runtime  |  ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}"
    }

    private fun refreshHapList() {
        installedHaps = installer.getInstalledHaps()
        hapAdapter.notifyDataSetChanged()

        if (installedHaps.isEmpty()) {
            hapList.visibility = View.GONE
            emptyHint.visibility = View.VISIBLE
        } else {
            hapList.visibility = View.VISIBLE
            emptyHint.visibility = View.GONE
        }
    }

    private fun openHapPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip"))
        }
        startActivityForResult(intent, REQUEST_PICK_HAP)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PICK_HAP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            installHapFromUri(uri)
        }
    }

    private fun installHapFromUri(uri: Uri) {
        installButton.isEnabled = false
        installButton.text = "Installing..."

        Thread {
            try {
                val result = contentResolver.openInputStream(uri)?.use { input ->
                    installer.install(input)
                } ?: throw IllegalStateException("Cannot open selected file")

                runOnUiThread {
                    Toast.makeText(this, "Installed: ${result.bundleName}", Toast.LENGTH_SHORT).show()
                    refreshHapList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "HAP install failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    installButton.isEnabled = true
                    installButton.text = "Install HAP"
                }
            }
        }.start()
    }

    private fun launchHap(hap: InstalledHap) {
        val slot = ProcessSlotManager.allocateSlot(this)
        if (slot < 0) {
            AlertDialog.Builder(this)
                .setTitle("No available slots")
                .setMessage("All ${ProcessSlotManager.MAX_SLOTS} process slots are in use. Close a running HAP before launching another.")
                .setPositiveButton("OK", null)
                .show()
            Log.w(TAG, "All ${ProcessSlotManager.MAX_SLOTS} process slots occupied")
            return
        }
        Log.e(TAG, "Launching HAP: ${hap.bundleName}/${hap.moduleName} ability=${hap.mainAbility} slot=$slot")
        val intent = Intent().apply {
            setClassName(packageName, "${packageName}.HoaAbilityActivity$slot")
            putExtra("BUNDLE_NAME", hap.bundleName)
            putExtra("MODULE_NAME", hap.moduleName)
            putExtra("ABILITY_NAME", hap.mainAbility)
            putExtra("PROCESS_SLOT", slot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    private fun confirmUninstall(hap: InstalledHap) {
        AlertDialog.Builder(this)
            .setTitle("Uninstall HAP")
            .setMessage("Remove ${hap.bundleName}/${hap.moduleName}?")
            .setPositiveButton("Uninstall") { _, _ ->
                installer.uninstall(hap.bundleName)
                refreshHapList()
                Toast.makeText(this, "Uninstalled: ${hap.bundleName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class HapListAdapter : BaseAdapter() {
        override fun getCount() = installedHaps.size
        override fun getItem(position: Int) = installedHaps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_hap, parent, false)
            val hap = installedHaps[position]

            view.findViewById<TextView>(R.id.hap_bundle_name).text = hap.bundleName
            view.findViewById<TextView>(R.id.hap_module_info).text =
                "${hap.moduleName} | ${hap.moduleConfig.type} | v${hap.moduleConfig.versionName}"
            view.findViewById<TextView>(R.id.hap_ability_info).text =
                if (hap.mainAbility.isNotBlank()) "Ability: ${hap.mainAbility}" else "No ability"

            return view
        }
    }

    companion object {
        private const val TAG = "HOA.Main"
        private const val REQUEST_PICK_HAP = 1001
    }
}
