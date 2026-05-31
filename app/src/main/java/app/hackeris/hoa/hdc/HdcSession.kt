package app.hackeris.hoa.hdc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * One HDC connection: holds channel + session state and the TCP socket.
 */
class HdcSession(
    val socket: Socket,
    private val daemon: HdcDaemon
) : Runnable {

    private val input = BufferedInputStream(socket.getInputStream(), 65536)
    private val output = BufferedOutputStream(socket.getOutputStream(), 65536)
    private val buffer = ByteArray(1048576) // 1 MB ring buffer
    private var bufLen = 0

    @Volatile var running = true
    @Volatile var channelId: Int = 0
    @Volatile var sessionId: Int = 0
    @Volatile var sessionEstablished = false
    @Volatile var connectedKey: String = ""
    @Volatile var peerChannelId: Int = 0

    // Install state
    private val installHandler = HdcInstallHandler(daemon)

    override fun run() {
        try {
            // Main read loop: accumulate bytes and parse packets
            while (running) {
                val n = input.read(buffer, bufLen, buffer.size - bufLen)
                if (n < 0) {
                    daemon.log("Connection closed by peer channelId=$channelId")
                    break
                }
                bufLen += n
                daemon.log("Read $n bytes, buffer total=$bufLen")

                // Parse all complete packets
                var offset = 0
                var pktCount = 0
                while (offset < bufLen) {
                    val pkt = parsePacket(buffer, offset, bufLen - offset)
                    if (pkt == null) {
                        // Incomplete packet; move remainder to front
                        if (offset > 0) {
                            System.arraycopy(buffer, offset, buffer, 0, bufLen - offset)
                            bufLen -= offset
                        }
                        break
                    }
                    val (head, protect, data) = pkt
                    val consumed = packetTotalSize(head)
                    offset += consumed
                    pktCount++
                    daemon.log("Parsed pkt #$pktCount: cmd=${protect.commandFlag}(${cmdName(protect.commandFlag)}) channelId=${protect.channelId} headSize=${head.headSize.toInt() and 0xFFFF} dataSize=${head.dataSize}")
                    handleCommand(protect, data)
                }
                // Compact
                if (offset >= bufLen) {
                    bufLen = 0
                } else if (offset > 0) {
                    System.arraycopy(buffer, offset, buffer, 0, bufLen - offset)
                    bufLen -= offset
                }
            }
        } catch (e: Exception) {
            if (running) {
                daemon.log("Session error channelId=$channelId: ${e.message}")
            }
        } finally {
            close()
        }
    }

    private fun handleCommand(protect: PayloadProtect, data: ByteArray) {
        peerChannelId = protect.channelId
        val cmd = protect.commandFlag
        when (cmd) {
            CMD_KERNEL_HANDSHAKE -> handleSessionHandshake(data)
            CMD_KERNEL_ECHO -> {
                sendPacket(CMD_KERNEL_ECHO, data)
            }
            CMD_KERNEL_ECHO_RAW -> {
                // Flow control echo from host, no action needed
            }
            12 -> {
                // CMD_KERNEL_WAKEUP_SLAVETASK — internal, no action needed
            }
            CMD_APP_CHECK -> installHandler.handleAppCheck(data, this)
            CMD_APP_BEGIN -> installHandler.handleAppBegin(this)
            CMD_APP_DATA -> installHandler.handleAppData(data, this)
            CMD_APP_FINISH -> installHandler.handleAppFinish(this)
            CMD_APP_UNINSTALL -> installHandler.handleAppUninstall(data, this)
            CMD_KERNEL_CHANNEL_CLOSE -> {
                daemon.log("Channel close requested channelId=$channelId")
                running = false
            }
            else -> {
                daemon.log("Unknown command $cmd channelId=$channelId")
            }
        }
    }

    private fun handleSessionHandshake(data: ByteArray) {
        val hs = SessionHandShake.fromBytes(data)
        connectedKey = hs.connectKey
        sessionId = daemon.nextSessionId()
        if (channelId == 0) {
            channelId = daemon.nextChannelId()
        }

        daemon.log("Session handshake: banner=${hs.banner} authType=${hs.authType} connectKey=${hs.connectKey} version=${hs.version}")

        // Reply with our session info; set authType=5 (AUTH_OK) to bypass auth
        val reply = SessionHandShake(
            banner = BANNER,
            authType = 4,   // AUTH_OK
            sessionId = sessionId,
            connectKey = connectedKey,
            buf = "OK",
            version = daemon.version
        )
        sendPacket(CMD_KERNEL_HANDSHAKE, reply.toBytes())
        sessionEstablished = true
        daemon.log("Session established: sid=$sessionId channelId=$channelId")
    }

    fun sendPacket(commandFlag: Int, data: ByteArray) {
        try {
            val pkt = buildPacket(peerChannelId, commandFlag, data)
            output.write(pkt)
            output.flush()
        } catch (e: Exception) {
            daemon.log("Send error channelId=$channelId: ${e.message}")
            running = false
        }
    }

    fun sendResult(success: Boolean, msg: String) {
        // CMD_APP_FINISH format: [mode:u8][success:u8][message:string]
        val out = ByteArrayOutputStream()
        out.write(1) // mode = APPMOD_INSTALL
        out.write(if (success) 1 else 0)
        out.write(msg.toByteArray(Charsets.UTF_8))
        sendPacket(CMD_APP_FINISH, out.toByteArray())
    }

    fun sendUninstallResult(msg: String) {
        val out = ByteArrayOutputStream()
        out.write(2) // mode = APPMOD_UNINSTALL
        out.write(1) // success
        out.write(msg.toByteArray(Charsets.UTF_8))
        sendPacket(CMD_APP_FINISH, out.toByteArray())
    }

    private fun cmdName(cmd: Int): String = when (cmd) {
        CMD_KERNEL_HANDSHAKE -> "HANDSHAKE"
        CMD_KERNEL_CHANNEL_CLOSE -> "CHANNEL_CLOSE"
        CMD_KERNEL_ECHO -> "ECHO"
        CMD_KERNEL_ECHO_RAW -> "ECHO_RAW"
        CMD_APP_CHECK -> "APP_CHECK"
        CMD_APP_BEGIN -> "APP_BEGIN"
        CMD_APP_DATA -> "APP_DATA"
        CMD_APP_FINISH -> "APP_FINISH"
        CMD_APP_UNINSTALL -> "APP_UNINSTALL"
        else -> "UNKNOWN($cmd)"
    }

    fun close() {
        running = false
        try { socket.close() } catch (_: Exception) {}
        daemon.removeSession(this)
    }

    companion object {
        private const val TAG = "HOA.HDC.Session"
    }
}
