package app.hackeris.hoa.hap

import android.content.Context
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

    private val baseDir = File(context.filesDir, "haps")

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
        val targetDir = File(baseDir, "$bundleName/$moduleName")

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
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

    fun getInstalledHaps(): List<InstalledHap> {
        if (!baseDir.exists()) return emptyList()

        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { bundleDir ->
                bundleDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.mapNotNull { moduleDir ->
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
                            bundleName = bundleDir.name,
                            moduleName = moduleDir.name,
                            contentDir = moduleDir,
                            moduleConfig = config,
                            mainAbility = mainAbility
                        )
                    } ?: emptyList()
            } ?: emptyList()
    }

    fun uninstall(bundleName: String) {
        val dir = File(baseDir, bundleName)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
