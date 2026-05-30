package app.hackeris.hoa.logging

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogWriter {

    private var logDir: File? = null
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(filesDir: File) {
        synchronized(lock) {
            logDir = File(filesDir, "logs").also { it.mkdirs() }
            cleanOldLogs()
        }
        i("HOA.LogWriter", "LogWriter initialized: ${logDir?.absolutePath}")
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        write("E", tag, msg, tr)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        write("W", tag, msg, tr)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg, null)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg, null)
    }

    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getLogDir(): File? = logDir

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val dir = logDir ?: return
        val timestamp = timeFormat.format(Date())
        val line = buildString {
            append(timestamp)
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(msg)
            if (tr != null) {
                append('\n')
                append(throwableToString(tr))
            }
            append('\n')
        }

        synchronized(lock) {
            try {
                val today = dateFormat.format(Date())
                val file = File(dir, "hoa_$today.log")
                FileWriter(file, true).use { it.write(line) }
            } catch (_: Exception) {
                // Don't fail if we can't write to file
            }
        }
    }

    private fun throwableToString(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun cleanOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
