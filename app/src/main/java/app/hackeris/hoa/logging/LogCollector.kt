package app.hackeris.hoa.logging

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogCollector {

    private const val TAG = "HOA.LogCollector"

    fun exportAndShare(context: Context, onError: (String) -> Unit) {
        Thread {
            try {
                val zipFile = createExportZip(context)
                shareZip(context, zipFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export logs", e)
                onError(e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun createExportZip(context: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(context.cacheDir, "hoa_logs_$ts.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // App log files
            for (logFile in LogWriter.getLogFiles()) {
                zos.putNextEntry(ZipEntry("app/${logFile.name}"))
                logFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // logcat snapshot
            zos.putNextEntry(ZipEntry("logcat.txt"))
            val logcat = captureLogcat()
            zos.write(logcat.toByteArray())
            zos.closeEntry()
        }

        LogWriter.i(TAG, "Log export zip created: ${zipFile.name} (${zipFile.length()} bytes)")
        return zipFile
    }

    private fun captureLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "*:V")
            )
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (out.isNotEmpty()) out else "(logcat empty; stderr: $err)"
        } catch (e: Exception) {
            "(logcat failed: ${e.message})"
        }
    }

    private fun shareZip(context: Context, zipFile: File) {
        // Write to a stable location so FileProvider can serve it.
        // cacheDir is cleaned aggressively on some devices, so copy to filesDir.
        val shareDir = File(context.filesDir, "shared_logs")
        shareDir.mkdirs()
        val sharedFile = File(shareDir, zipFile.name)
        zipFile.copyTo(sharedFile, overwrite = true)

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.logprovider",
                sharedFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share HOA Logs")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: IllegalArgumentException) {
            // FileProvider not configured — fall back to a basic intent
            LogWriter.w(TAG, "FileProvider failed, trying without", e)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_TEXT, "Logs saved to: ${sharedFile.absolutePath}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share HOA Logs"))
        }
    }
}
