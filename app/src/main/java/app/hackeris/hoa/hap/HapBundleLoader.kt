package app.hackeris.hoa.hap

import org.json.JSONObject
import java.util.zip.ZipFile

class HapBundleLoader {

    fun parse(hapPath: String): HapBundle {
        val zip = try {
            ZipFile(hapPath)
        } catch (e: Exception) {
            throw HapParseException("Cannot open HAP file: $hapPath", e)
        }

        try {
            val moduleConfig = parseModuleConfig(zip)
            val bytecodeEntries = extractBytecode(zip)
            val resourceIndex = extractResourceIndex(zip)
            val rawResources = extractResources(zip)
            val nativeLibs = extractNativeLibs(zip)
            val packInfo = extractPackInfo(zip)

            return HapBundle(
                hapFile = hapPath,
                moduleConfig = moduleConfig,
                bytecodeEntries = bytecodeEntries,
                resourceIndex = resourceIndex,
                rawResources = rawResources,
                nativeLibs = nativeLibs,
                packInfo = packInfo
            )
        } finally {
            zip.close()
        }
    }

    private fun parseModuleConfig(zip: ZipFile): ModuleConfig {
        val entry = zip.getEntry("module.json")
            ?: zip.getEntry("module.json5")
            ?: throw HapParseException("module.json not found in HAP")

        val rawJson = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        return parseModuleJson(rawJson, zip)
    }

    internal fun parseModuleJson(rawJson: String, zip: ZipFile? = null): ModuleConfig {
        val root = JSONObject(rawJson)

        // module.json has two possible structures:
        // 1. { "app": {...}, "module": {...} }  — full config with bundleName at top
        // 2. { "module": {...} }                 — module-only, bundleName from pack.info
        val appObj = root.optJSONObject("app")
        val moduleObj = root.getJSONObject("module")

        val bundleName = appObj?.optString("bundleName", "") ?: ""
        val vendor = appObj?.optString("vendor", "") ?: ""
        val versionCode = appObj?.optInt("versionCode", 1) ?: 1
        val versionName = appObj?.optString("versionName", "1.0.0") ?: "1.0.0"
        val apiVersion = appObj?.optInt("apiReleaseType", 0)
            ?: appObj?.optInt("apiVersion", 0) ?: 0
        val compileSdkVersion = appObj?.optString("compileSdkVersion", "") ?: ""
        val targetApiVersion = appObj?.optInt("targetAPIVersion", 0) ?: 0
        val minApiVersion = appObj?.optInt("minAPIVersion", 0) ?: 0

        val name = moduleObj.getString("name")
        val type = moduleObj.getString("type")
        val mainElement = moduleObj.optString("mainElement", "")
        val virtualMachine = moduleObj.optString("virtualMachine", "")
        val deviceTypes = moduleObj.optJSONArray("deviceTypes")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val pages = resolvePages(moduleObj, zip)

        val abilities = moduleObj.optJSONArray("abilities")?.let { arr ->
            (0 until arr.length()).map { parseAbility(arr.getJSONObject(it)) }
        } ?: emptyList()

        val requestPermissions = moduleObj.optJSONArray("requestPermissions")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        } ?: emptyList()

        return ModuleConfig(
            bundleName = bundleName,
            vendor = vendor,
            versionCode = versionCode,
            versionName = versionName,
            apiVersion = apiVersion,
            compileSdkVersion = compileSdkVersion,
            targetApiVersion = targetApiVersion,
            minApiVersion = minApiVersion,
            virtualMachine = virtualMachine,
            name = name,
            type = type,
            mainElement = mainElement,
            deviceTypes = deviceTypes,
            pages = pages,
            abilities = abilities,
            requestPermissions = requestPermissions,
            rawJson = rawJson
        )
    }

    private fun resolvePages(moduleObj: JSONObject, zip: ZipFile?): List<String> {
        val pagesField = moduleObj.opt("pages") ?: return emptyList()

        // Direct array: "pages": ["pages/Index", "pages/Second"]
        if (pagesField is org.json.JSONArray) {
            return (0 until pagesField.length()).map { pagesField.getString(it) }
        }

        // Resource reference: "pages": "$profile:main_pages"
        val pagesRef = pagesField.toString()
        if (pagesRef.startsWith("\$profile:")) {
            val profileName = pagesRef.removePrefix("\$profile:")
            val profileEntry = zip?.getEntry("resources/base/profile/$profileName.json")
                ?: return emptyList()

            val profileJson = zip.getInputStream(profileEntry).bufferedReader().use { it.readText() }
            val profileObj = JSONObject(profileJson)
            val srcArr = profileObj.getJSONArray("src")
            return (0 until srcArr.length()).map { srcArr.getString(it) }
        }

        return emptyList()
    }

    private fun parseAbility(obj: JSONObject): AbilityConfig {
        val skills = obj.optJSONArray("skills")?.let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                SkillConfig(
                    entities = s.optJSONArray("entities")?.let { e ->
                        (0 until e.length()).map { e.getString(it) }
                    } ?: emptyList(),
                    actions = s.optJSONArray("actions")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList()
                )
            }
        } ?: emptyList()

        return AbilityConfig(
            name = obj.getString("name"),
            srcEntry = obj.optString("srcEntry", ""),
            type = obj.optString("type", "page"),
            launchType = obj.optString("launchType", "singleton"),
            exported = obj.optBoolean("exported", false),
            label = obj.optString("label", ""),
            icon = obj.optString("icon", ""),
            skills = skills
        )
    }

    private fun extractBytecode(zip: ZipFile): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        for (path in listOf("ets/modules.abc", "ets/modules_static.abc")) {
            zip.getEntry(path)?.let { entry ->
                result[path] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    private fun extractResourceIndex(zip: ZipFile): ByteArray? {
        return zip.getEntry("resources.index")?.let { entry ->
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun extractResources(zip: ZipFile): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (name.startsWith("resources/") && !entry.isDirectory) {
                result[name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    private fun extractNativeLibs(zip: ZipFile): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (name.startsWith("libs/") && name.endsWith(".so") && !entry.isDirectory) {
                result[name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    private fun extractPackInfo(zip: ZipFile): PackInfo? {
        return zip.getEntry("pack.info")?.let { entry ->
            try {
                val rawJson = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val root = JSONObject(rawJson)
                val summary = root.optJSONObject("summary") ?: return null
                val app = summary.optJSONObject("app") ?: return null
                PackInfo(
                    bundleName = app.optString("bundleName", ""),
                    versionCode = app.optInt("versionCode", 0),
                    versionName = app.optString("versionName", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
