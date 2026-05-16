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
        // Cache decoded HAP icons to avoid re-reading module.json and
        // re-decoding bitmaps on every getView() call.  The cache is small
        // (< 10 entries) and cleared when refreshHapList() replaces this adapter.
        private val iconCache = mutableMapOf<String, android.graphics.Bitmap?>()

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

            val iconView = view.findViewById<android.widget.ImageView>(R.id.hap_icon)
            val cacheKey = "${hap.bundleName}.${hap.moduleName}"
            val cached = iconCache[cacheKey]
            if (cached != null || iconCache.containsKey(cacheKey)) {
                iconView.setImageBitmap(cached)
            } else {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                Thread {
                    val bitmap = loadHapIcon(hap)
                    iconCache[cacheKey] = bitmap
                    runOnUiThread {
                        if (installedHaps.getOrNull(position) == hap) {
                            iconView.setImageBitmap(bitmap)
                        }
                    }
                }.start()
            }

            return view
        }

        private fun loadHapIcon(hap: InstalledHap): android.graphics.Bitmap? {
            val fullModuleName = "${hap.bundleName}.${hap.moduleName}"
            val moduleJsonFile = java.io.File(filesDir, "hap/$fullModuleName/module.json")
            if (!moduleJsonFile.exists()) return null

            try {
                val json = org.json.JSONObject(moduleJsonFile.readText())
                val moduleObj = json.getJSONObject("module")
                val abilities = moduleObj.optJSONArray("abilities")
                if (abilities != null) {
                    for (i in 0 until abilities.length()) {
                        val ability = abilities.getJSONObject(i)
                        if (ability.getString("name") == hap.mainAbility) {
                            val iconRef = ability.optString("startWindowIcon", "")
                                .ifEmpty { ability.optString("icon", "") }
                            return loadBitmapFromRef(fullModuleName, iconRef)
                        }
                    }
                }
            } catch (_: Exception) { }
            return null
        }

        private fun loadBitmapFromRef(fullModuleName: String, iconRef: String): android.graphics.Bitmap? {
            if (!iconRef.startsWith("\$media:")) return null
            val mediaName = iconRef.removePrefix("\$media:")
            val mediaDir = java.io.File(filesDir, "hap/$fullModuleName/resources/base/media")
            if (!mediaDir.isDirectory) return null

            for (ext in listOf("png", "jpg", "jpeg", "webp")) {
                val file = java.io.File(mediaDir, "$mediaName.$ext")
                if (file.exists()) {
                    return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                }
            }
            return null
        }
    }

    companion object {
        private const val TAG = "HOA.Main"
        private const val REQUEST_PICK_HAP = 1001
    }
}
