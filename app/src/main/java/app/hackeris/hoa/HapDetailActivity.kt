package app.hackeris.hoa

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hackeris.hoa.hap.HapBundleLoader
import java.io.File

class HapDetailActivity : AppCompatActivity() {

    private lateinit var bundleName: String
    private lateinit var moduleName: String
    private lateinit var mainAbility: String
    private lateinit var contentDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hap_detail)

        bundleName = intent.getStringExtra("BUNDLE_NAME") ?: run { finish(); return }
        moduleName = intent.getStringExtra("MODULE_NAME") ?: run { finish(); return }
        mainAbility = intent.getStringExtra("ABILITY_NAME") ?: ""

        val fullName = "$bundleName.$moduleName"
        contentDir = File(filesDir, "hap/$fullName")
        if (!contentDir.isDirectory) {
            Toast.makeText(this, getString(R.string.toast_module_not_found, bundleName, moduleName), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title = getString(R.string.dialog_app_info_title)

        val config = loadConfig()
        val label = if (config != null) HapBundleLoader.resolveLabel(contentDir, config) else bundleName

        val container = findViewById<LinearLayout>(R.id.detail_content)
        buildContent(container, label, config)

        findViewById<Button>(R.id.detail_launch_button).setOnClickListener {
            launchHap()
        }

        Log.e(TAG, "Detail page: $bundleName/$moduleName")
    }

    private fun loadConfig(): app.hackeris.hoa.hap.ModuleConfig? {
        val jsonFile = File(contentDir, "module.json")
        return try {
            HapBundleLoader().parseModuleJson(jsonFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load module.json", e)
            null
        }
    }

    private fun buildContent(
        container: LinearLayout,
        label: String,
        config: app.hackeris.hoa.hap.ModuleConfig?
    ) {
        // --- App label ---
        addText(container, label, 22f, bold = true)
        addText(container, bundleName, 13f)

        if (config != null) {
            // --- Module ---
            addSection(container, getString(R.string.label_module))
            addRow(container, getString(R.string.label_module), config.name)
            addRow(container, "Type", config.type)
            if (config.vendor.isNotBlank()) {
                addRow(container, getString(R.string.label_vendor), config.vendor)
            }

            // --- Version ---
            addSection(container, getString(R.string.label_version))
            addRow(container, getString(R.string.label_version), "${config.versionName} (${config.versionCode})")
            if (config.compileSdkVersion.isNotBlank()) {
                addRow(container, "Compile SDK", config.compileSdkVersion)
            }
            addRow(container, "Target API", config.targetApiVersion.toString())
            addRow(container, "Min API", config.minApiVersion.toString())
            if (config.virtualMachine.isNotBlank()) {
                addRow(container, "VM", config.virtualMachine)
            }

            // --- Size ---
            addSection(container, getString(R.string.label_size))
            addRow(container, getString(R.string.label_size), formatSize(contentDir))

            // --- Permissions ---
            if (config.requestPermissions.isNotEmpty()) {
                addSection(container, getString(R.string.label_permissions) + " (${config.requestPermissions.size})")
                config.requestPermissions.forEach { addBullet(container, it) }
            }

            // --- Abilities ---
            if (config.abilities.isNotEmpty()) {
                addSection(container, getString(R.string.label_abilities) + " (${config.abilities.size})")
                config.abilities.forEach { a ->
                    val details = buildString {
                        append(a.name)
                        append("  [").append(a.type).append("]")
                        if (a.launchType != "singleton") append("  launch=").append(a.launchType)
                        if (a.exported) append("  exported")
                    }
                    addBullet(container, details)
                }
            }

            // --- Pages ---
            if (config.pages.isNotEmpty()) {
                addSection(container, getString(R.string.label_pages) + " (${config.pages.size})")
                config.pages.forEach { addBullet(container, it) }
            }
        }

        // --- Native libs ---
        val libs = scanNativeLibs()
        if (libs.isNotEmpty()) {
            var totalSize = 0L
            addSection(container, "Native Libraries (${libs.size})")
            libs.forEach { (name, size) ->
                totalSize += size
                addBullet(container, "$name  —  ${formatBytes(size)}")
            }
            addRow(container, "Total", formatBytes(totalSize))
        }
    }

    private fun scanNativeLibs(): List<Pair<String, Long>> {
        val libsDir = File(contentDir, "libs/arm64-v8a")
        if (!libsDir.isDirectory) return emptyList()
        return libsDir.listFiles()
            ?.filter { it.name.endsWith(".so") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.length() }
            ?: emptyList()
    }

    private fun launchHap() {
        val slot = ProcessSlotManager.allocateSlot(this)
        if (slot < 0) {
            Toast.makeText(this, getString(R.string.toast_slots_full_fmt, ProcessSlotManager.MAX_SLOTS), Toast.LENGTH_LONG).show()
            return
        }
        Log.e(TAG, "Launching HAP slot=$slot bundle=$bundleName/$moduleName/$mainAbility")
        val intent = Intent().apply {
            setClassName(packageName, "${packageName}.HoaAbilityActivity$slot")
            putExtra("BUNDLE_NAME", bundleName)
            putExtra("MODULE_NAME", moduleName)
            putExtra("ABILITY_NAME", mainAbility)
            putExtra("PROCESS_SLOT", slot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    // ---- UI helpers ----

    private val textColorPrimary by lazy {
        @Suppress("DEPRECATION") getColor(android.R.color.primary_text_light)
    }
    private val sectionColor by lazy {
        @Suppress("DEPRECATION") getColor(android.R.color.holo_blue_dark)
    }

    private fun addText(container: LinearLayout, text: String, size: Float, bold: Boolean = false, color: Int? = null) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (color != null) color else textColorPrimary)
            setPadding(0, dp(2), 0, dp(2))
        }
        container.addView(tv)
    }

    private fun addSpacer(container: LinearLayout, heightDp: Int) {
        val v = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
            )
        }
        container.addView(v)
    }

    private fun addSection(container: LinearLayout, title: String) {
        addSpacer(container, 20)
        val tv = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(sectionColor)
            setPadding(0, dp(6), 0, dp(6))
        }
        container.addView(tv)
        // thin divider
        val div = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(sectionColor and 0x00FFFFFF or 0x33000000)
        }
        container.addView(div)
    }

    private fun addRow(container: LinearLayout, label: String, value: String) {
        val tv = TextView(this).apply {
            text = "$label:  $value"
            textSize = 14f
            setTextColor(textColorPrimary)
            setPadding(dp(8), dp(5), 0, dp(5))
        }
        container.addView(tv)
    }

    private fun addBullet(container: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = "•  $text"
            textSize = 14f
            setTextColor(textColorPrimary)
            setPadding(dp(16), dp(4), 0, dp(4))
        }
        container.addView(tv)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun formatSize(dir: File): String = formatBytes(dirSize(dir))

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) dirSize(f) else f.length()
        }
        return size
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "HOA.Detail"
    }
}
