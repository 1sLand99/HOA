package app.hackeris.hoa.hap

import android.content.Context
import app.hackeris.hoa.logging.LogWriter
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class InstalledHap(
    val bundleName: String,
    val moduleName: String,
    val contentDir: File,
    val moduleConfig: ModuleConfig,
    val mainAbility: String
)

class HapInstaller(private val context: Context) {

    // Install to filesDir/hap/ so the ArkUI-X runtime's
    // StageAssetProvider.GetAppDataModuleDir() can discover them.
    private val baseDir = File(context.filesDir, "hap")
    private val sysDir = File(context.filesDir, "sys")

    fun install(hapPath: String): InstalledHap {
        val bundle = HapBundleLoader().parse(hapPath)
        return installFromBundle(bundle)
    }

    fun install(inputStream: InputStream): InstalledHap {
        val tmpFile = File(context.cacheDir, "hap_tmp_${System.currentTimeMillis()}")
        try {
            tmpFile.outputStream().use { out -> inputStream.copyTo(out) }
            return install(tmpFile.absolutePath)
        } finally {
            tmpFile.delete()
        }
    }

    private fun installFromBundle(bundle: HapBundle): InstalledHap {
        val bundleName = bundle.moduleConfig.bundleName.ifBlank { "unknown" }
        val moduleName = bundle.moduleConfig.name
        // Runtime expects dot-separated: bundleName.moduleName
        val fullModuleName = "$bundleName.$moduleName"
        val targetDir = File(baseDir, fullModuleName)

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        // Also clean the Preload copy under files/sys/ so the runtime re-copies
        // fresh resources on next launch. Otherwise stale resources.index from
        // a previous installation with the same bundleName would be used.
        val sysTargetDir = File(sysDir, fullModuleName)
        if (sysTargetDir.exists()) {
            sysTargetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        // Write module.json
        File(targetDir, "module.json").writeText(bundle.moduleConfig.rawJson)

        // Stream files directly from ZIP to disk to avoid OOM on large HAPs.
        ZipFile(bundle.hapFile).use { zip ->
            // .abc bytecode
            for (path in listOf("ets/modules.abc", "ets/modules_static.abc")) {
                zip.getEntry(path)?.let { entry ->
                    val file = File(targetDir, path)
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }

            // resources.index
            zip.getEntry("resources.index")?.let { entry ->
                val file = File(targetDir, "resources.index")
                zip.getInputStream(entry).use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                }
            }

            // Resource files under resources/
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if ((name.startsWith("resources/") || (name.startsWith("libs/") && name.endsWith(".so")))
                    && !entry.isDirectory) {
                    val file = File(targetDir, name)
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
        }

        // Extract pkgContextInfo.json from HAP (contains all npm dependency info)
        var pkgContextJson: String? = null
        ZipFile(bundle.hapFile).use { zip ->
            zip.getEntry("pkgContextInfo.json")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    pkgContextJson = input.reader().readText()
                }
            }
        }

        // Patch extracted .so files: replace DT_NEEDED "libc.so" with
        // "libb.so" in-place so the musl ABI bridge resolves first.
        val libsDir = File(targetDir, "libs")
        if (libsDir.isDirectory) {
            libsDir.walkTopDown()
                .filter { it.isFile && it.extension == "so" }
                .forEach { soFile ->
                    try {
                        if (!ElfPatcher.patchSo(soFile.absolutePath)) {
                            LogWriter.w(TAG, "ELF patch skipped or failed: ${soFile.name}")
                        }
                    } catch (e: UnsatisfiedLinkError) {
                        LogWriter.w(TAG, "ELF patch JNI not available, skipping: ${soFile.name}", e)
                    }
                }
        }

        // Detect if HAP has native .so files
        val hasNativeLibs = File(targetDir, "libs").isDirectory &&
            File(targetDir, "libs").walkTopDown().any { it.isFile && it.extension == "so" }

        // Write pkgContextInfo.json (required by StageAssetProvider.GetPkgJsonBuffer)
        writePkgContextInfo(targetDir, fullModuleName, bundle.moduleConfig.type, hasNativeLibs, pkgContextJson)

        val mainAbility = bundle.moduleConfig.mainElement.ifBlank {
            bundle.moduleConfig.abilities.firstOrNull()?.name ?: ""
        }

        return InstalledHap(
            bundleName = bundleName,
            moduleName = moduleName,
            contentDir = targetDir,
            moduleConfig = bundle.moduleConfig,
            mainAbility = mainAbility
        )
    }

    private fun writePkgContextInfo(targetDir: File, fullModuleName: String, moduleType: String, hasNativeLibs: Boolean, originalJson: String?) {
        // OHOS-format pkgContextInfo.json expected by js_runtime.cpp::ParsePkgContextInfoJson.
        // If the HAP ships its own pkgContextInfo.json, use it as the base and fill in
        // moduleName/bundleName for any entries that have them empty (npm dependencies).
        val lastDot = fullModuleName.lastIndexOf('.')
        val shortName = if (lastDot >= 0) fullModuleName.substring(lastDot + 1) else fullModuleName
        val bundleName = if (lastDot >= 0) fullModuleName.substring(0, lastDot) else ""

        val json = if (originalJson != null) {
            try {
                val orig = JSONObject(originalJson)
                for (key in orig.keys()) {
                    val item = orig.optJSONObject(key) ?: continue
                    if (item.optString("moduleName", "").isEmpty()) {
                        item.put("moduleName", fullModuleName)
                    }
                    if (item.optString("bundleName", "").isEmpty()) {
                        item.put("bundleName", bundleName)
                    }
                }
                orig
            } catch (e: Exception) {
                LogWriter.w(TAG, "Failed to parse HAP pkgContextInfo.json, generating fallback", e)
                null
            }
        } else null

        val finalJson = json ?: JSONObject().apply {
            put(shortName, JSONObject().apply {
                put("packageName", shortName)
                put("bundleName", bundleName)
                put("moduleName", fullModuleName)
                put("version", "")
                put("entryPath", "src/main/")
                put("isSO", hasNativeLibs)
                put("dependencyAlias", "")
            })
        }

        File(targetDir, "pkgContextInfo.json").writeText(finalJson.toString())
    }

    fun getInstalledHaps(): List<InstalledHap> {
        if (!baseDir.exists()) return emptyList()

        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { moduleDir ->
                // Directory name format: bundleName.moduleName
                val dirName = moduleDir.name
                val dotIndex = dirName.lastIndexOf('.')
                if (dotIndex < 0) return@mapNotNull null

                val bundleName = dirName.substring(0, dotIndex)
                val moduleName = dirName.substring(dotIndex + 1)

                val moduleJson = File(moduleDir, "module.json")
                if (!moduleJson.exists()) return@mapNotNull null

                val config = try {
                    HapBundleLoader().parseModuleJson(moduleJson.readText())
                } catch (_: Exception) {
                    return@mapNotNull null
                }

                val mainAbility = config.mainElement.ifBlank {
                    config.abilities.firstOrNull()?.name ?: ""
                }

                InstalledHap(
                    bundleName = bundleName,
                    moduleName = moduleName,
                    contentDir = moduleDir,
                    moduleConfig = config,
                    mainAbility = mainAbility
                )
            } ?: emptyList()
    }

    companion object {
        private const val TAG = "HOA.HapInstaller"
    }

    fun uninstall(bundleName: String) {
        // Remove all modules matching this bundleName from hap/ and sys/
        if (baseDir.exists()) {
            baseDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("$bundleName.") }
                ?.forEach { it.deleteRecursively() }
        }
        if (sysDir.exists()) {
            sysDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("$bundleName.") }
                ?.forEach { it.deleteRecursively() }
        }
    }
}
