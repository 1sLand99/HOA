package app.hackeris.hoa

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Bridges @kit.ShareKit's systemShare to Android's Intent.ACTION_SEND.
 * Called from C++ NAPI via JNI (hms_share_stub.cpp → ShowSharePanel).
 * Initialize once in HoaApplication.onCreate().
 */
object ShareHelper {
    private const val TAG = "HOA.ShareHelper"
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "ShareHelper initialized")
    }

    @JvmStatic
    fun showShare(text: String, title: String, description: String) {
        Log.i(TAG, "showShare: title=\"$title\" text_len=${text.length}")

        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "ShareHelper not initialized, cannot share")
            return
        }

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (title.isNotEmpty()) {
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, title.ifEmpty { "Share" }))
            Log.i(TAG, "Share chooser launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch share chooser", e)
        }
    }
}
