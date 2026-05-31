package app.hackeris.hoa.hdc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.hackeris.hoa.MainActivity
import app.hackeris.hoa.R

/**
 * Android Foreground Service that wraps the HDC daemon lifecycle.
 * The daemon runs as long as the service is active.
 */
class HdcService : Service() {

    private var daemon: HdcDaemon? = null
    private var currentPort: Int = DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        if (port !in 1024..65535) {
            android.util.Log.w(TAG, "Invalid port $port, using default $DEFAULT_PORT")
            currentPort = DEFAULT_PORT
        } else {
            currentPort = port
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (daemon == null || daemon?.isRunning() == false) {
            daemon = HdcDaemon(applicationContext, currentPort)
            daemon?.start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        daemon?.stop()
        daemon = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HDC Debug Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for HDC debug daemon"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HDC Debug")
                .setContentText("Port $currentPort — ${daemon?.sessionCount() ?: 0} connections")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("HDC Debug")
                .setContentText("Port ${DEFAULT_PORT} — listening")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        const val PREFS_NAME = "hoa_prefs"
        const val KEY_PORT = "hdc_port"
        private const val CHANNEL_ID = "hoa_hdc"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "HOA.HDC.Service"
    }
}
