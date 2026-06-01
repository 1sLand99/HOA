package app.hackeris.hoa

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.hackeris.hoa.logging.LogWriter
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

object UpdateChecker {

    private const val TAG = "HOA.UpdateChecker"
    private const val RELEASES_URL = "https://api.gitcode.com/api/v5/repos/harmony-on-android/HOA/releases"
    private const val DOWNLOAD_URL = "https://gitcode.com/harmony-on-android/HOA/releases/"

    sealed class Result {
        data class UpdateAvailable(val date: String) : Result()
        object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    fun check(context: Context, onResult: (Result) -> Unit) {
        Thread {
            val result: Result = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val localCode = info.versionCode
                val localName = info.versionName

                val latest = fetchLatestRelease()
                if (latest == null) {
                    Result.Error("Cannot fetch release info")
                } else {
                    LogWriter.i(TAG, "Local: $localName (code=$localCode), latest: ${latest.name} (code=${latest.versionCode})")

                    val hasUpdate = if (latest.versionCode != null) {
                        latest.versionCode > localCode
                    } else {
                        val currentDate = parseVersionDate(localName)
                        currentDate != null && latest.date != null && latest.date.after(currentDate)
                    }

                    if (hasUpdate) {
                        val dateStr = latest.date?.let {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it)
                        } ?: latest.name
                        Result.UpdateAvailable(dateStr)
                    } else {
                        Result.UpToDate
                    }
                }
            } catch (e: Exception) {
                LogWriter.w(TAG, "Update check failed", e)
                Result.Error(e.message ?: "Unknown error")
            }

            android.os.Handler(context.mainLooper).post {
                onResult(result)
            }
        }.start()
    }

    fun showResult(context: Context, result: Result) {
        when (result) {
            is Result.UpdateAvailable -> {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.update_dialog_title))
                    .setMessage(context.getString(R.string.update_dialog_msg, result.date))
                    .setPositiveButton(context.getString(R.string.update_dialog_go)) { _, _ ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            is Result.UpToDate -> {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.update_dialog_title))
                    .setMessage(context.getString(R.string.update_dialog_uptodate))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            is Result.Error -> {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.update_check_failed, result.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class ReleaseInfo(
        val name: String,
        val versionCode: Int?,
        val date: java.util.Date?
    )

    private fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val dateParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode != 200) {
                LogWriter.w(TAG, "API returned ${conn.responseCode}")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val releases = JSONArray(body)
            if (releases.length() == 0) return null

            val list = mutableListOf<ReleaseInfo>()
            for (i in 0 until releases.length()) {
                val obj = releases.getJSONObject(i)
                val name = obj.optString("name").ifEmpty {
                    obj.optString("tag_name")
                }
                if (name.isEmpty()) continue
                val dateStr = (obj.optString("published_at").ifEmpty {
                    obj.optString("created_at")
                }).take(19)
                val date = dateParser.parse(dateStr) ?: continue
                val code = parseVersionCode(name)
                list.add(ReleaseInfo(name = name, versionCode = code, date = date))
            }

            if (list.isEmpty()) return null

            list.sortByDescending { it.date }
            list[0]
        } catch (_: Exception) {
            null
        }
    }

    // Parse versionName "YY.M.D.B" → versionCode (YYMMDDBB).
    // Returns null if the string doesn't match the expected format.
    private fun parseVersionCode(versionName: String): Int? {
        return try {
            val parts = versionName.split(".")
            if (parts.size < 4) return null
            val yy = parts[0].toInt()
            val mm = parts[1].toInt()
            val dd = parts[2].toInt()
            val bb = parts[3].toInt()
            yy * 10000000 + mm * 100000 + dd * 1000 + bb
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVersionDate(versionName: String): java.util.Date? {
        return try {
            val parts = versionName.split(".")
            if (parts.size < 3) return null
            val year = 2000 + parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(
                "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
            )
        } catch (_: Exception) {
            null
        }
    }
}
