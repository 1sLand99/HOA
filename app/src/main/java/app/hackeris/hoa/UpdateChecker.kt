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
                val currentDate = parseVersionDate(context)
                if (currentDate == null) {
                    Result.Error("Cannot parse version date")
                } else {
                    val latestDate = fetchLatestReleaseDate()
                    if (latestDate == null) {
                        Result.Error("Cannot fetch release info")
                    } else {
                        LogWriter.i(TAG, "Current: $currentDate, latest: $latestDate")
                        if (latestDate.after(currentDate)) {
                            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(latestDate)
                            Result.UpdateAvailable(dateStr)
                        } else {
                            Result.UpToDate
                        }
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

    private fun parseVersionDate(context: Context): java.util.Date? {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val parts = info.versionName.split(".")
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

    private fun fetchLatestReleaseDate(): java.util.Date? {
        return try {
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

            val latest = releases.getJSONObject(0)
            val dateStr = latest.optString("published_at")
                .ifEmpty { latest.optString("created_at").ifEmpty { return null } }
                .take(19)

            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateStr)
        } catch (_: Exception) {
            null
        }
    }
}
