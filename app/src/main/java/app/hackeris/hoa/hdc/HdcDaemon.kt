package app.hackeris.hoa.hdc

import android.content.Context
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HDC daemon: accepts TCP connections and dispatches commands.
 *
 * One instance runs in a background thread. Start/stop via [start] and [stop].
 */
class HdcDaemon(
    private val appContext: Context,
    val port: Int = DEFAULT_PORT
) : Runnable {

    private val tag = "HOA.HDC.Daemon"
    private val running = AtomicBoolean(false)
    private val sessions = CopyOnWriteArrayList<HdcSession>()
    private val channelIdGen = AtomicInteger(1)
    private val sessionIdGen = AtomicInteger(1)
    private var serverSocket: ServerSocket? = null

    val version = "3.2.0c-HOA"

    fun log(msg: String) {
        Log.i(tag, msg)
    }

    fun nextChannelId(): Int = channelIdGen.getAndIncrement()
    fun nextSessionId(): Int = sessionIdGen.getAndIncrement()

    fun removeSession(session: HdcSession) {
        sessions.remove(session)
        log("Session removed: channelId=${session.channelId} remaining=${sessions.size}")
    }

    fun getApplicationContext(): Context? = appContext

    override fun run() {
        try {
            serverSocket = ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"))
            serverSocket?.reuseAddress = true
            log("HDC daemon listening on 0.0.0.0:$port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to bind port $port: ${e.message}", e)
            return
        }

        running.set(true)
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                log("Accepted connection: ${client.inetAddress}:${client.port}")
                val session = HdcSession(client, this)
                sessions.add(session)
                Thread(session, "hdc-session-${session.hashCode()}").start()
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(tag, "Accept error: ${e.message}")
                }
            }
        }
        log("Daemon accept loop exited")
    }

    fun start() {
        if (running.get()) {
            log("Daemon already running")
            return
        }
        Thread(this, "hdc-daemon").start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        // Close all active sessions
        for (s in sessions) {
            s.running = false
            try { s.socket.close() } catch (_: Exception) {}
        }
        sessions.clear()
        log("Daemon stopped")
    }

    fun isRunning(): Boolean = running.get()
    fun sessionCount(): Int = sessions.size
}
