package app.hackeris.hoa.hdc

import java.io.ByteArrayOutputStream
import java.io.InputStream

class HdcShellHandler(private val daemon: HdcDaemon) {

    fun handleShellCommand(data: ByteArray, session: HdcSession) {
        val command = String(data, Charsets.UTF_8).trim()
        daemon.log("CMD_UNITY_EXECUTE: command=$command")

        if (command.isEmpty()) {
            session.sendPacket(CMD_KERNEL_ECHO_RAW, ByteArray(0))
            finishCommand(session)
            return
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = readStream(process.inputStream)
            val stderr = readStream(process.errorStream)
            val exitCode = process.waitFor()

            val output = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(stderr)
                }
            }

            daemon.log("CMD_UNITY_EXECUTE: exit=$exitCode outputLen=${output.length}")
            if (output.isNotEmpty()) {
                session.sendPacket(CMD_KERNEL_ECHO_RAW, output.toByteArray(Charsets.UTF_8))
            } else {
                session.sendPacket(CMD_KERNEL_ECHO_RAW, ByteArray(0))
            }
        } catch (e: Exception) {
            daemon.log("CMD_UNITY_EXECUTE error: ${e.message}")
            session.sendPacket(CMD_KERNEL_ECHO_RAW, "Error: ${e.message}".toByteArray(Charsets.UTF_8))
        }

        finishCommand(session)
    }

    // Send CHANNEL_CLOSE from daemon side (matching OHOS TaskFinish behavior).
    // Keep TCP connection alive — the host will reuse it for the next command.
    private fun finishCommand(session: HdcSession) {
        session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
        session.resetChannel()
    }

    private fun readStream(inputStream: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n: Int
        try {
            while (inputStream.read(buf).also { n = it } != -1) {
                buffer.write(buf, 0, n)
            }
        } catch (_: Exception) {}
        try { inputStream.close() } catch (_: Exception) {}
        return buffer.toString("UTF-8")
    }
}
