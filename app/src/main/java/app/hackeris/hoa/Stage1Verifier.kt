package app.hackeris.hoa

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Stage 1 verification utility. Logs a structured checklist for
 * Ark VM initialization and .abc bytecode verification.
 * Run on device via: adb logcat -s HOA.Verify:V
 */
object Stage1Verifier {

    private const val TAG = "HOA.Verify"

    data class CheckResult(val name: String, val passed: Boolean, val detail: String)

    fun runAll(context: Context): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        Log.e(TAG, "===== Stage 1 Verification START =====")

        results += checkNativeLibs(context)
        results += checkNativeLibSizes(context)
        results += checkAssetFiles(context)
        results += checkAbcBytecode(context)
        results += checkModuleJson(context)
        results += checkResourcesIndex(context)
        results += checkInstalledHaps(context)
        results += checkRuntimeClassLoading()

        // Summary
        val passed = results.count { it.passed }
        val total = results.size
        val failed = results.filter { !it.passed }

        Log.e(TAG, "===== Stage 1 Verification: $passed/$total PASSED =====")
        if (failed.isNotEmpty()) {
            Log.w(TAG, "Failed checks:")
            failed.forEach { Log.w(TAG, "  FAIL: ${it.name} — ${it.detail}") }
        }

        return results
    }

    private fun checkNativeLibs(context: Context): List<CheckResult> {
        val requiredLibs = listOf(
            "libarkui_android.so",
            "libhilog.so",
            "libarkui_componentsnapshot.so",
            "libarkui_focuscontroller.so"
        )
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        Log.e(TAG, "nativeLibraryDir: $nativeLibDir")

        return requiredLibs.map { lib ->
            val file = File(nativeLibDir, lib)
            val exists = file.exists()
            CheckResult(
                name = "NativeLib/$lib",
                passed = exists,
                detail = if (exists) "found at ${file.path}" else "MISSING in $nativeLibDir"
            )
        }
    }

    private fun checkNativeLibSizes(context: Context): List<CheckResult> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val mainLib = File(nativeLibDir, "libarkui_android.so")
        if (!mainLib.exists()) {
            return listOf(CheckResult("NativeLibSize/libarkui_android.so", false, "file not found"))
        }
        val sizeMb = mainLib.length() / (1024.0 * 1024.0)
        // libarkui_android.so should be >50MB (typically ~90MB)
        val reasonable = sizeMb > 50.0
        return listOf(
            CheckResult(
                name = "NativeLibSize/libarkui_android.so",
                passed = reasonable,
                detail = String.format("%.1f MB %s", sizeMb, if (reasonable) "(OK)" else "(SUSPICIOUS: too small)")
            )
        )
    }

    private fun checkAssetFiles(context: Context): List<CheckResult> {
        val requiredAssets = listOf(
            "arkui-x/dynamicHap/ets/modules.abc",
            "arkui-x/dynamicHap/module.json",
            "arkui-x/dynamicHap/resources.index"
        )
        return requiredAssets.map { path ->
            val exists = try {
                context.assets.open(path).close()
                true
            } catch (_: Exception) {
                false
            }
            CheckResult(
                name = "Asset/$path",
                passed = exists,
                detail = if (exists) "OK" else "MISSING"
            )
        }
    }

    private fun checkAbcBytecode(context: Context): CheckResult {
        try {
            context.assets.open("arkui-x/dynamicHap/ets/modules.abc").use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read < 16) {
                    return CheckResult("AbcHeader", false, "file too short: $read bytes")
                }

                // PANDA magic: bytes 0-4 = 'P','A','N','D','A'
                val magicOk = header[0] == 'P'.code.toByte() &&
                        header[1] == 'A'.code.toByte() &&
                        header[2] == 'N'.code.toByte() &&
                        header[3] == 'D'.code.toByte() &&
                        header[4] == 'A'.code.toByte()

                // Version at offset 0x0C: [major, minor, feature, build]
                val major = header[0x0C].toInt() and 0xFF
                val minor = header[0x0D].toInt() and 0xFF
                val feature = header[0x0E].toInt() and 0xFF
                val build = header[0x0F].toInt() and 0xFF
                val versionStr = "$major.$minor.$feature.$build"

                // ArkUI-X runtime v13 should run v12 bytecode
                val versionOk = major in 10..13

                return CheckResult(
                    name = "AbcBytecode",
                    passed = magicOk && versionOk,
                    detail = "magic=${if (magicOk) "PANDA OK" else "INVALID"}, version=$versionStr ${if (versionOk) "(compatible)" else "(MAY BE INCOMPATIBLE)"}"
                )
            }
        } catch (e: Exception) {
            return CheckResult("AbcBytecode", false, "error: ${e.message}")
        }
    }

    private fun checkModuleJson(context: Context): CheckResult {
        try {
            context.assets.open("arkui-x/dynamicHap/module.json").bufferedReader().use { reader ->
                val json = reader.readText()
                val root = org.json.JSONObject(json)
                val moduleObj = root.optJSONObject("module")
                    ?: return CheckResult("ModuleJson", false, "no 'module' key in JSON")

                val name = moduleObj.optString("name", "")
                val type = moduleObj.optString("type", "")
                val vm = moduleObj.optString("virtualMachine", "")
                val mainElement = moduleObj.optString("mainElement", "")

                val hasRequiredFields = name.isNotBlank() && type.isNotBlank()

                return CheckResult(
                    name = "ModuleJson",
                    passed = hasRequiredFields,
                    detail = "name=$name, type=$type, vm=$vm, mainElement=$mainElement"
                )
            }
        } catch (e: Exception) {
            return CheckResult("ModuleJson", false, "parse error: ${e.message}")
        }
    }

    private fun checkResourcesIndex(context: Context): CheckResult {
        try {
            context.assets.open("arkui-x/dynamicHap/resources.index").use { input ->
                val size = input.available()
                val reasonable = size > 1000
                return CheckResult(
                    name = "ResourcesIndex",
                    passed = reasonable,
                    detail = "${size} bytes ${if (reasonable) "(OK)" else "(too small?)"}"
                )
            }
        } catch (e: Exception) {
            return CheckResult("ResourcesIndex", false, "error: ${e.message}")
        }
    }

    private fun checkInstalledHaps(context: Context): CheckResult {
        val arkuiDir = File(context.filesDir, "arkui-x")
        val modules = arkuiDir.listFiles()
            ?.filter { it.isDirectory && it.name.contains(".") }
            ?: emptyList()
        val hasInstalled = modules.isNotEmpty()
        return CheckResult(
            name = "InstalledHaps",
            passed = true, // not a blocking check
            detail = if (hasInstalled) "${modules.size} HAP(s) in ${arkuiDir.path}: ${modules.map { it.name }.joinToString()}" else "No HAPs installed yet (use Install HAP button)"
        )
    }

    private fun checkRuntimeClassLoading(): CheckResult {
        val classes = listOf(
            "ohos.stage.ability.adapter.StageApplication",
            "ohos.stage.ability.adapter.StageActivity",
            "ohos.ace.adapter.capability.bridge.BridgeManager"
        )
        val missing = classes.filter { className ->
            try {
                Class.forName(className)
                false
            } catch (_: ClassNotFoundException) {
                true
            }
        }

        return CheckResult(
            name = "RuntimeClasses",
            passed = missing.isEmpty(),
            detail = if (missing.isEmpty()) "all ${classes.size} classes found" else "missing: ${missing.joinToString()}"
        )
    }
}
