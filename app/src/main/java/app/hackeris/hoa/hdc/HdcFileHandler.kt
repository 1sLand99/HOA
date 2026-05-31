package app.hackeris.hoa.hdc

import java.io.File
import java.io.FileOutputStream

class HdcFileHandler(private val daemon: HdcDaemon) {

    private data class FileTransferState(
        val dstPath: String = "",
        val fileSize: Long = 0,
        val received: Long = 0,
        val outStream: FileOutputStream? = null
    )

    private val states = mutableMapOf<Int, FileTransferState>()

    private fun remapPath(rawPath: String): String {
        val trimmed = rawPath.trimStart('/')
        val cacheDir = daemon.getApplicationContext()?.cacheDir?.absolutePath
            ?: return rawPath
        return when {
            trimmed.startsWith("data/local/tmp/") ->
                "$cacheDir/hdc/${trimmed.removePrefix("data/local/tmp/")}"
            rawPath.startsWith("/data/local/tmp/") ->
                "$cacheDir/hdc/${rawPath.removePrefix("/data/local/tmp/")}"
            else -> rawPath
        }
    }

    fun handleFileInit(data: ByteArray, session: HdcSession) {
        val cmdLine = String(data, Charsets.UTF_8).trim()
        daemon.log("CMD_FILE_INIT: cmdLine=$cmdLine")

        val parts = cmdLine.split(Regex("\\s+"))
        val rawPath = if (parts.size >= 3) parts[2] else cmdLine
        val dstPath = remapPath(rawPath)
        daemon.log("CMD_FILE_INIT: raw=$rawPath remapped=$dstPath")

        val parentDir = File(dstPath).parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
            daemon.log("Created parent dir: ${parentDir.absolutePath}")
        }

        states[session.channelId] = FileTransferState(dstPath = dstPath)
        daemon.log("CMD_FILE_INIT: dstPath=$dstPath channelId=${session.channelId}")
    }

    fun handleFileCheck(data: ByteArray, session: HdcSession) {
        val config = TransferConfig.fromBytes(data)
        daemon.log("CMD_FILE_CHECK: fileSize=${config.fileSize} path=${config.path} " +
            "optionalName=${config.optionalName}")

        val state = states[session.channelId]
        val (outPath, existingState) = if (state != null) {
            Pair(if (config.path.isNotEmpty()) remapPath(config.path) else state.dstPath, state)
        } else {
            daemon.log("CMD_FILE_CHECK: no prior INIT, using config path")
            val raw = if (config.path.isNotEmpty()) config.path
                else "/data/local/tmp/${config.optionalName}"
            val path = remapPath(raw)
            daemon.log("CMD_FILE_CHECK: raw=$raw remapped=$path")
            Pair(path, FileTransferState(dstPath = path))
        }

        val finalPath = if (outPath.isNotEmpty()) outPath else {
            val tempDir = daemon.getApplicationContext()?.cacheDir?.absolutePath
                ?: "/data/local/tmp"
            "$tempDir/${config.optionalName}"
        }

        val parentDir = File(finalPath).parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        try {
            val fos = FileOutputStream(finalPath, false)
            states[session.channelId] = existingState.copy(
                dstPath = finalPath,
                fileSize = config.fileSize,
                outStream = fos,
                received = 0
            )
            daemon.log("Opened file for writing: $finalPath size=${config.fileSize}")

            session.sendPacket(CMD_FILE_BEGIN, CMD_FILE_BEGIN_FLAGS)
            daemon.log("Sent CMD_FILE_BEGIN for channelId=${session.channelId}")
        } catch (e: Exception) {
            daemon.log("CMD_FILE_CHECK failed to open $finalPath: ${e.message}")
            states.remove(session.channelId)
            finishFileChannel(session)
        }
    }

    fun handleFileData(data: ByteArray, session: HdcSession) {
        val state = states[session.channelId]
        if (state == null) {
            daemon.log("CMD_FILE_DATA: no state for channelId=${session.channelId}")
            return
        }
        val fos = state.outStream
        if (fos == null) {
            daemon.log("CMD_FILE_DATA: file not open for channelId=${session.channelId}")
            return
        }

        try {
            val headerBytes = data.copyOfRange(0, PAYLOAD_PREFIX_RESERVE)
            val payload = TransferPayload.fromBytes(headerBytes)
            val fileData = data.copyOfRange(PAYLOAD_PREFIX_RESERVE, data.size)
            val pct = if (state.fileSize > 0) (state.received + fileData.size) * 100 / state.fileSize else 0
            daemon.log("CMD_FILE_DATA: idx=${payload.index} compType=${payload.compressType} " +
                "fileDataSize=${fileData.size} progress=$pct%")

            fos.write(fileData)
            val newReceived = state.received + fileData.size
            states[session.channelId] = state.copy(received = newReceived)

            if (newReceived >= state.fileSize && state.fileSize > 0) {
                daemon.log("All file data received ($newReceived/${state.fileSize})")
                closeState(session)
                // Send FILE_FINISH(0) to signal completion, then CHANNEL_CLOSE
                session.sendPacket(CMD_FILE_FINISH, byteArrayOf(0))
                daemon.log("Sent CMD_FILE_FINISH for channelId=${session.channelId}")
                session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
                session.resetChannel()
                return
            }
        } catch (e: Exception) {
            daemon.log("CMD_FILE_DATA write error: ${e.message}")
            closeState(session)
            finishFileChannel(session)
            return
        }
    }

    fun handleFileFinish(data: ByteArray, session: HdcSession) {
        daemon.log("CMD_FILE_FINISH: host sent finish for channelId=${session.channelId}")
        val state = states.remove(session.channelId)
        if (state != null) {
            try { state.outStream?.close() } catch (_: Exception) {}
            daemon.log("File transfer done: dst=${state.dstPath} " +
                "received=${state.received}/${state.fileSize}")
        } else {
            daemon.log("CMD_FILE_FINISH: no state, sending ack")
        }
        session.sendPacket(CMD_FILE_FINISH, byteArrayOf(0))
        session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
        session.resetChannel()
    }

    private fun closeState(session: HdcSession) {
        val state = states.remove(session.channelId)
        try { state?.outStream?.close() } catch (_: Exception) {}
        if (state != null) {
            daemon.log("File transfer done: dst=${state.dstPath} " +
                "received=${state.received}/${state.fileSize}")
        }
    }

    private fun finishFileChannel(session: HdcSession) {
        session.sendPacket(CMD_FILE_FINISH, byteArrayOf(0))
        session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
        session.resetChannel()
    }
}
