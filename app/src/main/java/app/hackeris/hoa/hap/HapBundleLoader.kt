package app.hackeris.hoa.hap

import org.json.JSONObject
import java.util.zip.ZipFile

class HapBundleLoader {

    // Lightweight parse — reads only module.json from the zip, skips
    // bytecode, resources, and native libs.  Suitable for preview dialogs.
    fun previewConfig(hapPath: String): ModuleConfig {
        val zip = ZipFile(hapPath)
        return try {
            parseModuleConfig(zip)
        } finally {
            zip.close()
        }
    }

    // Returns true if the file is a HarmonyOS .app package (a zip with pack.info).
    fun isAppPackage(filePath: String): Boolean {
        return try {
            ZipFile(filePath).use { zip -> zip.getEntry("pack.info") != null }
        } catch (_: Exception) {
            false
        }
    }

    // Extracts the single .hap from a .app package.  Throws if the .app
    // contains zero or more than one .hap entries.
    fun unwrapSingleHap(appPath: String): String {
        val hapTmp = java.io.File.createTempFile("hoa_app_hap_", ".hap")
        ZipFile(appPath).use { zip ->
            val hapEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".hap") }
                .toList()

            when (hapEntries.size) {
                0 -> throw HapParseException("No HAP found in .app package")
                1 -> {
                    zip.getInputStream(hapEntries[0]).use { input ->
                        hapTmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    return hapTmp.absolutePath
                }
                else -> throw HapParseException(
                    ".app contains ${hapEntries.size} HAPs — multi-HAP packages not supported yet"
                )
            }
        }
    }

    fun parse(hapPath: String): HapBundle {
        val zip = try {
            ZipFile(hapPath)
        } catch (e: Exception) {
            throw HapParseException("Cannot open HAP file: $hapPath", e)
        }

        try {
            val moduleConfig = parseModuleConfig(zip)
            val packInfo = extractPackInfo(zip)

            return HapBundle(
                hapFile = hapPath,
                moduleConfig = moduleConfig,
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
        val label = appObj?.optString("label", "") ?: ""
        val vendor = appObj?.optString("vendor", "") ?: ""
        val versionCode = appObj?.optInt("versionCode", 1) ?: 1
        val versionName = appObj?.optString("versionName", "1.0.0") ?: "1.0.0"
        val apiVersion = appObj?.optInt("apiReleaseType", 0)
            ?: appObj?.optInt("apiVersion", 0) ?: 0
        val compileSdkVersion = appObj?.optString("compileSdkVersion", "") ?: ""
        val targetApiVersion = appObj?.optInt("targetAPIVersion", 0) ?: 0
        val minApiVersion = appObj?.optInt("minAPIVersion", 0) ?: 0
        val appIconId = appObj?.optInt("iconId", 0) ?: 0

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
            label = label,
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
            rawJson = rawJson,
            iconId = appIconId
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
            skills = skills,
            iconId = obj.optInt("iconId", 0)
        )
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

    companion object {

        /**
         * Resolves the app display label for an installed HAP.
         *
         * If moduleConfig.label is a $string:xxx reference, reads resources.index
         * from the installed module directory to resolve it.  Falls back to
         * bundleName on failure.
         */
        fun resolveLabel(moduleDir: java.io.File, config: ModuleConfig): String {
            val rawLabel = config.label
            if (!rawLabel.startsWith("\$string:")) {
                return rawLabel.ifEmpty { config.bundleName }
            }
            val key = rawLabel.removePrefix("\$string:")
            val resolved = parseStringFromIndex(moduleDir, key)
            val result = resolved ?: config.bundleName
            return result
        }

        internal fun parseStringFromIndex(moduleDir: java.io.File, targetKey: String): String? {
            val indexFile = java.io.File(moduleDir, "resources.index")
            if (!indexFile.exists()) return null
            val data = try { indexFile.readBytes() } catch (_: Exception) { return null }

            // Determine version from the version string prefix.
            // Old format: "Restool X.Y.Z" → use V1 (KEYS+IDSS structure inline)
            // New format: "RestoolV2 X.Y.Z" → use V2 parser
            val versionPrefix = if (data.size >= 10) {
                String(data, 0, minOf(10, data.size), Charsets.UTF_8)
            } else ""
            return if (versionPrefix.startsWith("RestoolV2")) {
                parseV2(data, targetKey)
            } else {
                parseV1(data, targetKey)
            }
        }

        // ---- V2 parser (RestoolV2 6.x, ArkUI-X) ----

        /**
         * Parses the V2 resources.index format based on the authoritative
         * ArkUI-X reader at developtools/global_resource_tool/src/resource_table.cpp.
         *
         * Layout:
         *   IndexHeaderV2 (140B): version[128] length[4] keyCount[4] dataBlockOffset[4]
         *   keyCount x KeyConfig (12B + keyParams):
         *     tag"KEYS"[4] configId[4] keyCount[4] keyParam(type[4]value[4])...
         *   IdSetHeader (16B):
         *     tag"IDSS"[4] length[4] typeCount[4] idCount[4]
         *     typeCount x ResTypeHeader (12B):
         *       resType[4] length[4] count[4]
         *       count x ResIndex (12B + nameLen):
         *         resId[4] offset[4] nameLen[4] name[nameLen]
         *   DataHeader (at dataBlockOffset, 12B):
         *     tag"DATA"[4] length[4] idCount[4]
         *     idCount x ResInfo (12B + valueCount*8B):
         *       resId[4] length[4] valueCount[4]
         *       valueCount x (configId[4] dataOffset[4])
         *   Data pool (after all ResInfo):
         *     dataLen[2] data[dataLen] ...
         */
        private fun parseV2(data: ByteArray, targetKey: String): String? {
            if (data.size < 140) return null

            val keyCount = readInt32LE(data, 132)
            val dataBlockOffset = readInt32LE(data, 136)
            if (keyCount <= 0 || keyCount > 100) return null
            if (dataBlockOffset <= 0 || dataBlockOffset >= data.size) return null

            // Skip KeyConfigs to find IdSetHeader
            var pos = 140
            for (i in 0 until keyCount) {
                if (pos + 12 > data.size) return null
                val tag = String(data, pos, 4, Charsets.UTF_8)
                if (tag != "KEYS") return null
                val kpCount = readInt32LE(data, pos + 8)
                if (kpCount > 20) return null
                pos += 12 + kpCount * 8
            }

            // Parse IdSetHeader
            if (pos + 16 > data.size) return null
            val idssTag = String(data, pos, 4, Charsets.UTF_8)
            if (idssTag != "IDSS") return null
            val typeCount = readInt32LE(data, pos + 8)
            pos += 16

            // Search through ResTypeHeaders and ResIndex entries for target name
            for (t in 0 until typeCount) {
                if (pos + 12 > dataBlockOffset) return null
                // val resType = readInt32LE(data, pos + 0)
                // val typeLen  = readInt32LE(data, pos + 4)
                val count = readInt32LE(data, pos + 8)
                pos += 12

                for (i in 0 until count) {
                    if (pos + 12 > dataBlockOffset) return null
                    // val resId = readInt32LE(data, pos)
                    val resInfoOffset = readInt32LE(data, pos + 4)
                    val nameLen = readInt32LE(data, pos + 8)
                    if (nameLen <= 0 || nameLen > 200) return null
                    if (pos + 12 + nameLen > data.size) return null

                    val name = String(data, pos + 12, nameLen, Charsets.UTF_8)
                    if (name == targetKey) {
                        // Found! Read the value from data pool via ResInfo
                        val result = readV2Value(data, resInfoOffset)
                        return result
                    }
                    pos += 12 + nameLen
                }
            }
            return null
        }

        private fun readV2Value(data: ByteArray, resInfoOffset: Int): String? {
            // ResInfo at resInfoOffset: resId[4] length[4] valueCount[4]
            // then valueCount x (configId[4] dataOffset[4])
            if (resInfoOffset + 12 > data.size) return null

            val valueCount = readInt32LE(data, resInfoOffset + 8)
            if (valueCount <= 0 || valueCount > 50) return null

            // Use the first variant's dataOffset
            val pairPos = resInfoOffset + 12
            if (pairPos + 8 > data.size) return null
            // val configId = readInt32LE(data, pairPos)  // not needed
            val dataOffset = readInt32LE(data, pairPos + 4)
            if (dataOffset <= 0 || dataOffset + 2 > data.size) return null

            val dataLen = readUInt16LE(data, dataOffset)
            if (dataLen <= 0 || dataLen > 5000) return null
            if (dataOffset + 2 + dataLen > data.size) return null

            return String(data, dataOffset + 2, dataLen, Charsets.UTF_8)
        }

        // ---- V1 parser (Restool 5.x / 6.x, classic format) ----
        //
        // Based on OHOS 5.0.1 hap_parser.cpp:
        //   ResHeader (136B): version[128] length[4] keyCount[4]
        //   For each key:  KEYS[4] offset[4] keyParamsCount[4]  + keyParams×8B
        //   At key.offset: IDSS[4] count[4]  + count×IdParam(8B: id+offset)
        //   At idParam.offset: IdItem(12B: size+resType+id) + valueLen[2]+value[valueLen] + nameLen[2]+name[nameLen]

        private fun parseV1(data: ByteArray, targetKey: String): String? {
            if (data.size < 136) return null
            val keyCount = readInt32LE(data, 132)
            if (keyCount <= 0 || keyCount > 100) return null

            var pos = 136
            for (ki in 0 until keyCount) {
                if (pos + 12 > data.size) return null
                val tag = String(data, pos, 4, Charsets.UTF_8)
                if (tag != "KEYS") return null
                val idssOffset = readInt32LE(data, pos + 4)
                val kpCount = readInt32LE(data, pos + 8)
                if (kpCount > 20) return null
                pos += 12 + kpCount * 8

                if (idssOffset + 8 > data.size) continue
                val idssTag = String(data, idssOffset, 4, Charsets.UTF_8)
                if (idssTag != "IDSS") continue
                val idCount = readInt32LE(data, idssOffset + 4)
                if (idCount <= 0 || idCount > 500) continue

                var ipPos = idssOffset + 8
                for (ii in 0 until idCount) {
                    if (ipPos + 8 > data.size) break
                    // val ipId = readInt32LE(data, ipPos)     // resource id — unused for key lookup
                    val itemOffset = readInt32LE(data, ipPos + 4)
                    ipPos += 8

                    if (itemOffset + 12 > data.size) continue
                    val itemSize = readInt32LE(data, itemOffset)
                    val resType = readInt32LE(data, itemOffset + 4)
                    if (itemSize < 14 || itemSize > 5000) continue

                    // itemPos starts after the 12-byte IdItem header
                    var itemPos = itemOffset + 12

                    // Skip value / array data to reach the name field.
                    // STRING = 9, INTEGER = 0, BOOLEAN = 12, COLOR = 14, FLOAT = 18,
                    // MEDIA = 7, PROF = 8
                    val isArray = (resType == 10 || resType == 11 || resType == 16 ||
                                   resType == 17 || resType == 22)  // STRINGARRAY|INTARRAY|THEME|PLURALS|PATTERN
                    if (isArray) {
                        // arrayLen[2] + N strings (each: strLen[2]+value[strLen+1]) + trailing '\0'
                        if (itemPos + 2 > data.size) continue
                        val arrLen = readUInt16LE(data, itemPos)
                        if (arrLen < 1 || arrLen > 5000) continue
                        // Skip past the entire packed array (arrLen bytes of string data)
                        // plus the trailing '\0' after the last element
                        itemPos += 2 + arrLen + 1
                    } else {
                        // valueLen[2] + value[valueLen] (valueLen includes '\0')
                        if (itemPos + 2 > data.size) continue
                        val valLen = readUInt16LE(data, itemPos)
                        if (valLen < 1 || valLen > 5000) continue
                        if (itemPos + 2 + valLen > data.size) continue
                        val value = String(data, itemPos + 2, valLen - 1, Charsets.UTF_8)
                        itemPos += 2 + valLen

                        // Read name
                        if (itemPos + 2 > data.size) continue
                        val nameLen = readUInt16LE(data, itemPos)
                        if (nameLen < 1 || nameLen > 200) continue
                        if (itemPos + 2 + nameLen > data.size) continue
                        val name = String(data, itemPos + 2, nameLen - 1, Charsets.UTF_8)

                        if (name == targetKey) return value
                    }
                }
            }
            return null
        }

        // ---- Media path resolution by resource ID ----

        /**
         * Resolves a numeric iconId to its file path using resources.index.
         *
         * Returns the path relative to the HAP root, e.g.
         * "entry/resources/base/media/icon.png" or null if not found.
         */
        fun resolveMediaPathById(moduleDir: java.io.File, iconId: Int): String? {
            if (iconId == 0) return null
            val indexFile = java.io.File(moduleDir, "resources.index")
            if (!indexFile.exists()) return null
            val data = try { indexFile.readBytes() } catch (_: Exception) { return null }

            val versionPrefix = if (data.size >= 10) {
                String(data, 0, minOf(10, data.size), Charsets.UTF_8)
            } else ""
            return if (versionPrefix.startsWith("RestoolV2")) {
                resolveMediaPathByIdV2(data, iconId)
            } else {
                resolveMediaPathByIdV1(data, iconId)
            }
        }

        private fun resolveMediaPathByIdV2(data: ByteArray, iconId: Int): String? {
            if (data.size < 140) return null
            val keyCount = readInt32LE(data, 132)
            val dataBlockOffset = readInt32LE(data, 136)
            if (keyCount <= 0 || keyCount > 100) return null
            if (dataBlockOffset <= 0 || dataBlockOffset >= data.size) return null

            var pos = 140
            for (i in 0 until keyCount) {
                if (pos + 12 > data.size) return null
                val kpCount = readInt32LE(data, pos + 8)
                if (kpCount > 20) return null
                pos += 12 + kpCount * 8
            }

            if (pos + 16 > data.size) return null
            val typeCount = readInt32LE(data, pos + 8)
            pos += 16

            for (t in 0 until typeCount) {
                if (pos + 12 > dataBlockOffset) return null
                val count = readInt32LE(data, pos + 8)
                pos += 12

                for (i in 0 until count) {
                    if (pos + 12 > dataBlockOffset) return null
                    val resId = readInt32LE(data, pos)
                    val resInfoOffset = readInt32LE(data, pos + 4)
                    val nameLen = readInt32LE(data, pos + 8)
                    if (nameLen <= 0 || nameLen > 200) return null
                    if (pos + 12 + nameLen > data.size) return null

                    if (resId == iconId) {
                        return readV2Value(data, resInfoOffset)
                    }
                    pos += 12 + nameLen
                }
            }
            return null
        }

        private fun resolveMediaPathByIdV1(data: ByteArray, iconId: Int): String? {
            if (data.size < 136) return null
            val keyCount = readInt32LE(data, 132)
            if (keyCount <= 0 || keyCount > 100) return null

            var pos = 136
            for (ki in 0 until keyCount) {
                if (pos + 12 > data.size) return null
                val tag = String(data, pos, 4, Charsets.UTF_8)
                if (tag != "KEYS") return null
                val idssOffset = readInt32LE(data, pos + 4)
                val kpCount = readInt32LE(data, pos + 8)
                if (kpCount > 20) return null
                pos += 12 + kpCount * 8

                if (idssOffset + 8 > data.size) continue
                val idssTag = String(data, idssOffset, 4, Charsets.UTF_8)
                if (idssTag != "IDSS") continue
                val idCount = readInt32LE(data, idssOffset + 4)
                if (idCount <= 0 || idCount > 500) continue

                var ipPos = idssOffset + 8
                for (ii in 0 until idCount) {
                    if (ipPos + 8 > data.size) break
                    val ipId = readInt32LE(data, ipPos)
                    val itemOffset = readInt32LE(data, ipPos + 4)
                    ipPos += 8

                    if (ipId != iconId) continue
                    if (itemOffset + 12 > data.size) continue
                    val itemSize = readInt32LE(data, itemOffset)
                    val resType = readInt32LE(data, itemOffset + 4)
                    if (itemSize < 14 || itemSize > 5000) continue

                    var itemPos = itemOffset + 12
                    val isArray = (resType == 10 || resType == 11 || resType == 16 ||
                                   resType == 17 || resType == 22)
                    if (isArray) {
                        if (itemPos + 2 > data.size) continue
                        val arrLen = readUInt16LE(data, itemPos)
                        if (arrLen < 1 || arrLen > 5000) continue
                        itemPos += 2 + arrLen + 1
                    } else {
                        if (itemPos + 2 > data.size) continue
                        val valLen = readUInt16LE(data, itemPos)
                        if (valLen < 1 || valLen > 5000) continue
                        if (itemPos + 2 + valLen > data.size) continue
                        return String(data, itemPos + 2, valLen - 1, Charsets.UTF_8)
                    }
                }
            }
            return null
        }

        // ---- Consolidated HAP icon loading ----

        /**
         * Loads the icon for an installed HAP given only its module directory.
         * Reads module.json from the directory to obtain the config.
         */
        fun loadHapIcon(moduleDir: java.io.File): android.graphics.Bitmap? {
            val moduleJsonFile = java.io.File(moduleDir, "module.json")
            if (!moduleJsonFile.exists()) return null
            return try {
                val rawJson = moduleJsonFile.readText()
                val config = HapBundleLoader().parseModuleJson(rawJson, null)
                loadHapIcon(moduleDir, config)
            } catch (_: Exception) { null }
        }

        /**
         * Loads the best icon for an installed HAP.
         *
         * Algorithm:
         * 1. Reads module.json from the installed module directory.
         * 2. Finds the ability matching module.mainElement, uses its iconId.
         *    Falls back to app.iconId if the ability has none.
         * 3. Resolves iconId → file path via resources.index.
         * 4. Reads the file from disk.  Handles .json layered icons by
         *    compositing foreground + background into a single PNG.
         */
        fun loadHapIcon(moduleDir: java.io.File, config: ModuleConfig): android.graphics.Bitmap? {
            // Step 1-2: determine iconId
            var iconId = 0
            val mainAbility = config.abilities.find { it.name == config.mainElement }
            if (mainAbility != null && mainAbility.iconId != 0) {
                iconId = mainAbility.iconId
            }
            if (iconId == 0) {
                iconId = config.iconId
            }
            if (iconId == 0) return null

            // Step 3: resolve iconId → file path via resources.index
            val iconPath = resolveMediaPathById(moduleDir, iconId) ?: return null

            val iconFile = java.io.File(moduleDir, stripModulePrefix(iconPath))
            if (!iconFile.exists()) return null

            // Step 4: read the file
            return when {
                iconFile.extension.equals("json", ignoreCase = true) ->
                    loadLayeredIcon(moduleDir, iconFile)
                iconFile.extension.equals("svg", ignoreCase = true) ->
                    loadSvgAsPng(iconFile)
                else ->
                    android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath)
            }
        }

        private fun loadLayeredIcon(moduleDir: java.io.File, jsonFile: java.io.File): android.graphics.Bitmap? {
            return try {
                val json = org.json.JSONObject(jsonFile.readText())
                val layered = json.optJSONObject("layered-image") ?: return null
                val bgRef = layered.optString("background", "")
                val fgRef = layered.optString("foreground", "")

                val bgBitmap = resolveMediaRef(moduleDir, bgRef)
                val fgBitmap = resolveMediaRef(moduleDir, fgRef)

                if (bgBitmap == null && fgBitmap == null) return null
                val bg = bgBitmap ?: fgBitmap!!
                val fg = fgBitmap ?: return bg

                val width = maxOf(bg.width, fg.width)
                val height = maxOf(bg.height, fg.height)
                val result = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(result)
                canvas.drawBitmap(bg, 0f, 0f, null)
                canvas.drawBitmap(fg, 0f, 0f, null)
                result
            } catch (_: Exception) { null }
        }

        private fun resolveMediaRef(moduleDir: java.io.File, ref: String): android.graphics.Bitmap? {
            if (!ref.startsWith("\$media:")) return null
            val mediaKey = ref.removePrefix("\$media:")

            // $media:123456 — numeric resource ID, resolve via resources.index
            val numericId = mediaKey.toIntOrNull()
            if (numericId != null && numericId > 0) {
                val path = resolveMediaPathById(moduleDir, numericId)
                if (path != null) {
                    val normalizedPath = stripModulePrefix(path)
                    val file = java.io.File(moduleDir, normalizedPath)
                    if (file.exists()) {
                        return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    }
                }
                return null
            }

            // $media:name — named resource, resolve via resources.index name lookup
            val mediaPath = parseStringFromIndex(moduleDir, mediaKey)
            if (mediaPath != null) {
                val normalizedPath = stripModulePrefix(mediaPath)
                val file = java.io.File(moduleDir, normalizedPath)
                if (file.exists()) {
                    return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                }
            }

            // Fallback: search resources/base/media/ by filename
            val mediaDir = java.io.File(moduleDir, "resources/base/media")
            if (mediaDir.isDirectory) {
                for (ext in listOf("png", "jpg", "jpeg", "webp")) {
                    val file = java.io.File(mediaDir, "$mediaKey.$ext")
                    if (file.exists()) {
                        return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    }
                }
            }
            return null
        }

        private fun stripModulePrefix(path: String): String {
            val slashIdx = path.indexOf('/')
            return if (slashIdx > 0 && slashIdx < path.length - 1) {
                path.substring(slashIdx + 1)
            } else path
        }

        private fun loadSvgAsPng(svgFile: java.io.File): android.graphics.Bitmap? {
            // SVG rendering requires an external library — not implemented.
            // Return null so callers fall back to the default icon.
            return null
        }

        private fun readInt32LE(data: ByteArray, offset: Int): Int {
            var result = data[offset].toInt() and 0xFF
            result = result or ((data[offset + 1].toInt() and 0xFF) shl 8)
            result = result or ((data[offset + 2].toInt() and 0xFF) shl 16)
            result = result or ((data[offset + 3].toInt() and 0xFF) shl 24)
            return result
        }

        private fun readUInt16LE(data: ByteArray, offset: Int): Int {
            val low = data[offset].toInt() and 0xFF
            val high = (data[offset + 1].toInt() and 0xFF) shl 8
            return low or high
        }
    }
}
