package app.hackeris.hoa

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import app.hackeris.hoa.logging.LogCollector
import app.hackeris.hoa.logging.LogWriter
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import app.hackeris.hoa.hap.HapBundleLoader
import app.hackeris.hoa.hap.HapInstaller
import app.hackeris.hoa.hap.InstalledHap
import app.hackeris.hoa.hdc.DEFAULT_PORT
import app.hackeris.hoa.hdc.HdcService

enum class SortMode {
    NAME, TIME_DESC, TIME_ASC
}

class MainActivity : AppCompatActivity() {

    private lateinit var installer: HapInstaller
    private lateinit var hapList: ListView
    private lateinit var emptyHint: TextView
    private lateinit var installButton: Button
    private var searchView: SearchView? = null
    private var sortMenu: Menu? = null

    private val hapAdapter = HapListAdapter()
    private var allHaps = listOf<InstalledHap>()
    private var installedHaps = listOf<InstalledHap>()
    private var sortMode = SortMode.TIME_ASC
    private var searchQuery = ""

    private val hapInstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHapList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installer = HapInstaller(this)
        hapList = findViewById(R.id.hap_list)
        emptyHint = findViewById(R.id.empty_hint)
        installButton = findViewById(R.id.install_button)

        hapList.adapter = hapAdapter
        hapList.setOnItemClickListener { _, _, position, _ ->
            val hap = installedHaps[position]
            launchHap(hap)
        }
        hapList.setOnItemLongClickListener { _, view, position, _ ->
            val hap = installedHaps[position]
            showLongPressMenu(view, hap)
            true
        }

        installButton.setOnClickListener {
            openHapPicker()
        }

        LogWriter.e(TAG, "========== HOA MainActivity START ==========")

        // Handle HAP file opened from file manager or shared from another app
        handleIntent(intent)

        // First-launch disclaimer
        val prefs = getSharedPreferences("hoa_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("first_launch_done", false)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.first_launch_title))
                .setMessage(getString(R.string.first_launch_msg))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.first_launch_btn)) { _, _ ->
                    prefs.edit().putBoolean("first_launch_done", true).apply()
                }
                .show()
        }

        registerReceiver(hapInstallReceiver, IntentFilter("app.hackeris.hoa.HAP_INSTALLED"))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(hapInstallReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshHapList()
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                // Shared file comes via EXTRA_STREAM
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> return
        }
        if (uri != null) {
            LogWriter.e(TAG, "Handling HAP from intent: action=${intent.action} uri=$uri")
            previewAndInstallHap(uri)
        }
    }

    private fun refreshHapList() {
        allHaps = installer.getInstalledHaps()
        filterAndSort()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)

        val searchItem = menu.findItem(R.id.action_search)
        searchView = (searchItem.actionView as SearchView).apply {
            queryHint = getString(R.string.hint_search_haps)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    searchQuery = query?.trim()?.lowercase() ?: ""
                    filterAndSort()
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    searchQuery = newText?.trim()?.lowercase() ?: ""
                    filterAndSort()
                    return true
                }
            })
        }

        sortMenu = menu.findItem(R.id.action_sort_menu).subMenu
        sortMenu?.findItem(sortModeToItemId())?.isChecked = true

        // Set initial HDC toggle title
        val hdcItem = menu.findItem(R.id.action_hdc)
        hdcItem.setTitle(if (isHdcRunning()) R.string.menu_hdc_stop else R.string.menu_hdc_start)

        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val newMode = when (item.itemId) {
            R.id.action_sort_name -> SortMode.NAME
            R.id.action_sort_time_desc -> SortMode.TIME_DESC
            R.id.action_sort_time_asc -> SortMode.TIME_ASC
            R.id.action_permissions -> {
                showPermissionsDialog()
                return true
            }
            R.id.action_feedback -> {
                showFeedbackDialog()
                return true
            }
            R.id.action_check_update -> {
                UpdateChecker.check(this) { result ->
                    UpdateChecker.showResult(this, result)
                }
                return true
            }
            R.id.action_hdc -> {
                toggleHdc(item)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
        if (newMode != sortMode) {
            sortMode = newMode
            item.isChecked = true
            filterAndSort()
        }
        return true
    }

    private fun sortModeToItemId(): Int = when (sortMode) {
        SortMode.NAME -> R.id.action_sort_name
        SortMode.TIME_DESC -> R.id.action_sort_time_desc
        SortMode.TIME_ASC -> R.id.action_sort_time_asc
    }

    private fun filterAndSort() {
        val filtered = if (searchQuery.isEmpty()) {
            allHaps
        } else {
            allHaps.filter { hap ->
                val label = HapBundleLoader.resolveLabel(hap.contentDir, hap.moduleConfig)
                label.lowercase().contains(searchQuery) ||
                    hap.bundleName.lowercase().contains(searchQuery) ||
                    hap.moduleName.lowercase().contains(searchQuery)
            }
        }

        installedHaps = when (sortMode) {
            SortMode.NAME -> filtered.sortedBy {
                HapBundleLoader.resolveLabel(it.contentDir, it.moduleConfig).lowercase()
            }
            SortMode.TIME_DESC -> filtered.sortedByDescending { it.contentDir.lastModified() }
            SortMode.TIME_ASC -> filtered.sortedBy { it.contentDir.lastModified() }
        }

        hapAdapter.notifyDataSetChanged()

        if (installedHaps.isEmpty()) {
            hapList.visibility = View.GONE
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = if (allHaps.isEmpty()) {
                getString(R.string.hint_no_haps)
            } else {
                getString(R.string.hint_no_search_results)
            }
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
            previewAndInstallHap(uri)
        }
    }

    private fun previewAndInstallHap(uri: Uri) {
        installButton.isEnabled = false
        installButton.text = getString(R.string.btn_extracting)

        Thread {
            try {
                val tmpFile = java.io.File(cacheDir, "hap_preview_${System.currentTimeMillis()}")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("Cannot open selected file")

                var hapPath: String
                var config: app.hackeris.hoa.hap.ModuleConfig
                try {
                    config = HapBundleLoader().previewConfig(tmpFile.absolutePath)
                    hapPath = tmpFile.absolutePath
                } catch (e: app.hackeris.hoa.hap.HapParseException) {
                    if (!HapBundleLoader().isAppPackage(tmpFile.absolutePath)) throw e
                    hapPath = HapBundleLoader().unwrapSingleHap(tmpFile.absolutePath)
                    tmpFile.delete()
                    config = HapBundleLoader().previewConfig(hapPath)
                }

                val finalHapPath = hapPath
                runOnUiThread {
                    installButton.isEnabled = true
                    installButton.text = getString(R.string.btn_install_hap)
                    showInstallPreviewDialog(java.io.File(finalHapPath), config)
                }
            } catch (e: Exception) {
                LogWriter.e(TAG, "HAP preview failed", e)
                runOnUiThread {
                    Toast.makeText(
                        this, getString(R.string.toast_install_failed_fmt, e.message), Toast.LENGTH_LONG
                    ).show()
                    installButton.isEnabled = true
                    installButton.text = getString(R.string.btn_install_hap)
                }
            }
        }.start()
    }

    private fun showInstallPreviewDialog(
        tmpFile: java.io.File, config: app.hackeris.hoa.hap.ModuleConfig
    ) {
        val sb = StringBuilder()

        fun row(label: String, value: String) {
            sb.append(label).append(": ").append(value).append("\n")
        }

        row(getString(R.string.label_bundle_name), config.bundleName.ifEmpty { "—" })
        row(getString(R.string.label_module),
            "${config.name} (${config.type})")
        row(getString(R.string.label_version),
            "${config.versionName} (${config.versionCode})")
        row(getString(R.string.label_sdk),
            "target=${config.targetApiVersion}  min=${config.minApiVersion}")

        if (config.requestPermissions.isNotEmpty()) {
            sb.append("\n").append(getString(R.string.label_permissions))
                .append(" (").append(config.requestPermissions.size).append("):\n")
            config.requestPermissions.forEach { sb.append("  • ").append(it).append("\n") }
        }

        if (config.abilities.isNotEmpty()) {
            sb.append("\n").append(getString(R.string.label_abilities))
                .append(" (").append(config.abilities.size).append("):\n")
            config.abilities.forEach { a ->
                sb.append("  • ").append(a.name).append(" (").append(a.type).append(")\n")
            }
        }

        val contentView = android.widget.TextView(this).apply {
            text = sb.toString().trimEnd()
            textSize = 14f
            @Suppress("DEPRECATION")
            setTextColor(getColor(android.R.color.primary_text_light))
            setPadding(40, 24, 40, 8)
            setLineSpacing(4f, 1f)
        }
        val scrollView = android.widget.ScrollView(this).apply {
            addView(contentView)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_install_preview_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.btn_install)) { _, _ ->
                doInstall(tmpFile)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                tmpFile.delete()
            }
            .setOnCancelListener { tmpFile.delete() }
            .show()
    }

    private fun doInstall(tmpFile: java.io.File) {
        installButton.isEnabled = false
        installButton.text = getString(R.string.btn_installing)

        Thread {
            try {
                val result = installer.install(tmpFile.absolutePath)
                runOnUiThread {
                    Toast.makeText(
                        this, getString(R.string.toast_installed_fmt, result.bundleName), Toast.LENGTH_SHORT
                    ).show()
                    refreshHapList()
                    installButton.isEnabled = true
                    installButton.text = getString(R.string.btn_install_hap)
                }
            } catch (e: Exception) {
                LogWriter.e(TAG, "HAP install failed", e)
                runOnUiThread {
                    Toast.makeText(
                        this, getString(R.string.toast_install_failed_fmt, e.message), Toast.LENGTH_LONG
                    ).show()
                    installButton.isEnabled = true
                    installButton.text = getString(R.string.btn_install_hap)
                }
            } finally {
                tmpFile.delete()
            }
        }.start()
    }

    private fun openDetailPage(hap: InstalledHap) {
        val intent = Intent(this, HapDetailActivity::class.java).apply {
            putExtra("BUNDLE_NAME", hap.bundleName)
            putExtra("MODULE_NAME", hap.moduleName)
            putExtra("ABILITY_NAME", hap.mainAbility)
        }
        startActivity(intent)
    }

    private fun launchHap(hap: InstalledHap) {
        val slot = ProcessSlotManager.allocateSlot(this)
        if (slot < 0) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_no_slots_title))
                .setMessage(getString(R.string.dialog_no_slots_msg, ProcessSlotManager.MAX_SLOTS))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            LogWriter.w(TAG, "All ${ProcessSlotManager.MAX_SLOTS} process slots occupied")
            return
        }
        LogWriter.e(TAG, "Launching HAP: ${hap.bundleName}/${hap.moduleName} ability=${hap.mainAbility} slot=$slot")
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

    private fun showLongPressMenu(anchor: View, hap: InstalledHap) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, MENU_APP_INFO, 0, getString(R.string.btn_app_info))
        popup.menu.add(0, MENU_ADD_TO_HOME, 1, getString(R.string.btn_add_to_home))
        popup.menu.add(0, MENU_UNINSTALL, 2, getString(R.string.btn_uninstall))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_APP_INFO -> openDetailPage(hap)
                MENU_ADD_TO_HOME -> pinShortcut(hap)
                MENU_UNINSTALL -> confirmUninstall(hap)
            }
            true
        }
        popup.show()
    }

    private fun confirmUninstall(hap: InstalledHap) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_uninstall_title))
            .setMessage(getString(R.string.dialog_uninstall_msg, hap.bundleName, hap.moduleName))
            .setPositiveButton(getString(R.string.btn_uninstall)) { _, _ ->
                installer.uninstall(hap.bundleName)
                refreshHapList()
                Toast.makeText(this, getString(R.string.toast_uninstalled_fmt, hap.bundleName), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pinShortcut(hap: InstalledHap) {
        val label = HapBundleLoader.resolveLabel(hap.contentDir, hap.moduleConfig)
        val shortcutId = "${hap.bundleName}.${hap.moduleName}.${hap.mainAbility}"

        Thread {
            val icon = loadHapIconBitmap(hap)
            runOnUiThread {
                val intent = Intent(this, HoaShortcutActivity::class.java).apply {
                    putExtra("BUNDLE_NAME", hap.bundleName)
                    putExtra("MODULE_NAME", hap.moduleName)
                    putExtra("ABILITY_NAME", hap.mainAbility)
                    action = Intent.ACTION_VIEW
                }

                val builder = ShortcutInfo.Builder(this, shortcutId)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIntent(intent)
                if (icon != null) {
                    builder.setIcon(Icon.createWithBitmap(icon))
                }
                val shortcut = builder.build()

                val manager = getSystemService(ShortcutManager::class.java)
                if (manager != null && manager.isRequestPinShortcutSupported) {
                    // requestPinShortcut requires the shortcut to be a dynamic
                    // shortcut first.  Once called, the system serialises the
                    // shortcut data into the pin-confirmation dialog — it won't
                    // re-query the dynamic list, so we can remove it immediately
                    // to keep the long-press menu clean.
                    manager.addDynamicShortcuts(listOf(shortcut))
                    manager.requestPinShortcut(shortcut, null)
                    manager.removeDynamicShortcuts(listOf(shortcutId))
                    Toast.makeText(
                        this, getString(R.string.toast_shortcut_pinned_fmt, label), Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.toast_shortcut_pin_failed), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // Loads the HAP icon bitmap for the shortcut.
    private fun loadHapIconBitmap(hap: InstalledHap): android.graphics.Bitmap? {
        return HapBundleLoader.loadHapIcon(hap.contentDir, hap.moduleConfig)
    }

    private inner class HapListAdapter : BaseAdapter() {
        // Cache decoded HAP icons to avoid re-reading module.json and
        // re-decoding bitmaps on every getView() call.  The cache is small
        // (< 10 entries) and cleared when refreshHapList() replaces this adapter.
        private val iconCache = mutableMapOf<String, android.graphics.Bitmap?>()
        // Cache resolved display labels to avoid re-scanning resources.index.
        private val labelCache = mutableMapOf<String, String>()

        override fun getCount() = installedHaps.size
        override fun getItem(position: Int) = installedHaps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_hap, parent, false)
            val hap = installedHaps[position]

            val cacheKey = "${hap.bundleName}.${hap.moduleName}"
            val displayLabel = labelCache.getOrPut(cacheKey) {
                HapBundleLoader.resolveLabel(hap.contentDir, hap.moduleConfig)
            }
            view.findViewById<TextView>(R.id.hap_bundle_name).text = displayLabel
            view.findViewById<TextView>(R.id.hap_module_info).text =
                "${hap.moduleName} | ${hap.moduleConfig.type} | v${hap.moduleConfig.versionName}"
            view.findViewById<TextView>(R.id.hap_ability_info).text =
                if (hap.mainAbility.isNotBlank()) getString(R.string.label_ability_fmt, hap.mainAbility) else getString(R.string.label_no_ability)

            val iconView = view.findViewById<android.widget.ImageView>(R.id.hap_icon)
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
            return HapBundleLoader.loadHapIcon(hap.contentDir, hap.moduleConfig)
        }
    }

    private fun showPermissionsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_permissions_title))
            .setMessage(getString(R.string.dialog_permissions_msg))
            .setPositiveButton(getString(R.string.dialog_permissions_go)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFeedbackDialog() {
        val items = arrayOf(
            getString(R.string.dialog_feedback_export_logs),
            getString(R.string.dialog_feedback_gitcode),
            getString(R.string.dialog_feedback_github)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_feedback_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, getString(R.string.toast_logs_exporting), Toast.LENGTH_SHORT).show()
                        LogCollector.export(
                            this,
                            onComplete = { path ->
                                runOnUiThread {
                                    AlertDialog.Builder(this)
                                        .setTitle(getString(R.string.toast_logs_exported))
                                        .setMessage(path)
                                        .setPositiveButton(getString(R.string.dialog_share)) { _, _ ->
                                            LogCollector.share(this, path)
                                        }
                                        .setNegativeButton(android.R.string.ok, null)
                                        .show()
                                }
                            },
                            onError = { error ->
                                runOnUiThread {
                                    Toast.makeText(this, getString(R.string.toast_logs_export_failed, error), Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                    1 -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://gitcode.com/harmony-on-android/HOA/issues")))
                    2 -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/harmony-on-android/HOA/issues")))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleHdc(item: android.view.MenuItem) {
        if (isHdcRunning()) {
            stopService(Intent(this, HdcService::class.java))
            item.setTitle(R.string.menu_hdc_start)
            Toast.makeText(this, R.string.toast_hdc_stopped, Toast.LENGTH_SHORT).show()
        } else {
            showHdcPortDialog { port ->
                val prefs = getSharedPreferences("hoa_prefs", MODE_PRIVATE)
                prefs.edit().putInt(HdcService.KEY_PORT, port).apply()
                val intent = Intent(this, HdcService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                item.setTitle(R.string.menu_hdc_stop)
                Toast.makeText(this, getString(R.string.toast_hdc_started, port), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showHdcPortDialog(onStart: (Int) -> Unit) {
        val prefs = getSharedPreferences("hoa_prefs", MODE_PRIVATE)
        val currentPort = prefs.getInt(HdcService.KEY_PORT, DEFAULT_PORT)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentPort.toString())
            setSelection(text.length)
        }
        val px16 = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(px16, 0, px16, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_hdc_port_title)
            .setMessage(R.string.dialog_hdc_port_msg)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port != null && port in 1024..65535) {
                    onStart(port)
                } else {
                    Toast.makeText(this, R.string.toast_hdc_invalid_port, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isHdcRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (HdcService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "HOA.Main"
        private const val REQUEST_PICK_HAP = 1001
        private const val MENU_APP_INFO = 1
        private const val MENU_ADD_TO_HOME = 2
        private const val MENU_UNINSTALL = 3
    }
}
