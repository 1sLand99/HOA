package app.hackeris.hoa.hap

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream

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

        // Write .abc bytecode
        bundle.bytecodeEntries.forEach { (path, data) ->
            val file = File(targetDir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(data)
        }

        // Write resources.index
        bundle.resourceIndex?.let { data ->
            File(targetDir, "resources.index").writeBytes(data)
        }

        // Write resource files
        bundle.rawResources.forEach { (path, data) ->
            val file = File(targetDir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(data)
        }

        // Write native libs
        bundle.nativeLibs.forEach { (path, data) ->
            val file = File(targetDir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(data)
        }

        // Write pkgContextInfo.json (required by StageAssetProvider.GetPkgJsonBuffer)
        writePkgContextInfo(targetDir, fullModuleName, bundle.moduleConfig.type)

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

    private fun writePkgContextInfo(targetDir: File, fullModuleName: String, moduleType: String) {
        // OHOS-format pkgContextInfo.json expected by js_runtime.cpp::ParsePkgContextInfoJson.
        // Top-level key = module short name, value = {packageName, bundleName, moduleName, version, entryPath, isSO, dependencyAlias}
        val lastDot = fullModuleName.lastIndexOf('.')
        val shortName = if (lastDot >= 0) fullModuleName.substring(lastDot + 1) else fullModuleName
        val bundleName = if (lastDot >= 0) fullModuleName.substring(0, lastDot) else ""
        val json = JSONObject().apply {
            put(shortName, JSONObject().apply {
                put("packageName", shortName)
                put("bundleName", bundleName)
                put("moduleName", fullModuleName)
                put("version", "")
                put("entryPath", "src/main/")
                put("isSO", false)
                put("dependencyAlias", "")
            })
        }
        File(targetDir, "pkgContextInfo.json").writeText(json.toString())
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
