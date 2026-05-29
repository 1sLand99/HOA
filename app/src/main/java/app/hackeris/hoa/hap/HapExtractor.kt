package app.hackeris.hoa.hap

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object HapExtractor {

    private const val TAG = "HOA.HapExtractor"

    fun extractHapToFilesDir(
        context: Context,
        hapAssetPath: String,
        bundleName: String,
        moduleName: String
    ): Boolean {
        val filesDir = context.filesDir
        val fullName = "$bundleName.$moduleName"
        val targetDir = File(filesDir, "hap/$fullName")

        try {
            val hapInputStream = context.assets.open(hapAssetPath)
            val tempHap = File(context.cacheDir, "temp_${moduleName}.hap")
            tempHap.outputStream().use { out -> hapInputStream.copyTo(out) }

            val (extracted, hasNativeLibs) = extractHap(tempHap, targetDir)
            tempHap.delete()

            if (extracted) {
                generatePkgContextInfo(targetDir, fullName, hasNativeLibs)
                Log.e(TAG, "HAP extracted to $targetDir")
                listExtractedFiles(targetDir)
            }
            return extracted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract HAP: ${e.message}", e)
            return false
        }
    }

    private fun extractHap(hapFile: File, targetDir: File): Pair<Boolean, Boolean> {
        val zipFile = ZipFile(hapFile)
        val entries = zipFile.entries()

        var count = 0
        var hasNativeLibs = false
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue

            if (!hasNativeLibs && entry.name.startsWith("libs/") && entry.name.endsWith(".so")) {
                hasNativeLibs = true
            }

            val outFile = File(targetDir, entry.name)
            outFile.parentFile?.mkdirs()

            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            count++
        }
        zipFile.close()

        Log.e(TAG, "Extracted $count files from HAP to $targetDir, hasNativeLibs=$hasNativeLibs")
        return Pair(count > 0, hasNativeLibs)
    }

    private fun listExtractedFiles(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relPath = file.relativeTo(dir)
                Log.e(TAG, "  $relPath (${file.length()} bytes)")
            }
        }
    }

    private fun generatePkgContextInfo(targetDir: File, fullName: String, hasNativeLibs: Boolean) {
        val pkgFile = File(targetDir, "pkgContextInfo.json")
        if (pkgFile.exists()) return
        // OHOS-format pkgContextInfo.json expected by js_runtime.cpp::ParsePkgContextInfoJson.
        // Top-level key = module short name, value = {packageName, bundleName, moduleName, version, entryPath, isSO, dependencyAlias}
        val lastDot = fullName.lastIndexOf('.')
        val shortName = if (lastDot >= 0) fullName.substring(lastDot + 1) else fullName
        val bundleName = if (lastDot >= 0) fullName.substring(0, lastDot) else ""
        val json = """{"$shortName":{"packageName":"$shortName","bundleName":"$bundleName","moduleName":"$fullName","version":"","entryPath":"src/main/","isSO":$hasNativeLibs,"dependencyAlias":""}}"""
        pkgFile.writeText(json)
        Log.e(TAG, "Generated pkgContextInfo.json: $json")
    }
}
