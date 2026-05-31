package app.hackeris.hoa.hdc

import app.hackeris.hoa.hap.HapInstaller
import java.io.File
import java.io.FileOutputStream

/**
 * Handles HDC install/uninstall commands.
 *
 * Install flow:
 *   CMD_APP_CHECK  → parse TransferConfig, open temp file
 *   CMD_APP_BEGIN  → (re)open file for writing
 *   CMD_APP_DATA   → write chunk to file
 *   CMD_APP_FINISH → close file, call HapInstaller, return result
 */
class HdcInstallHandler(private val daemon: HdcDaemon) {

    // Per-channel state
    private data class InstallState(
        val localPath: String = "",
        val options: String = "",
        val optionalName: String = "",
        val fileSize: Long = 0,
        val received: Long = 0,
        val chunks: Int = 0,
        val outStream: FileOutputStream? = null
    )

    private val states = mutableMapOf<Int, InstallState>()

    private fun getTempDir(): String {
        return daemon.getApplicationContext()?.cacheDir?.absolutePath ?: "/data/local/tmp"
    }

    fun handleAppCheck(data: ByteArray, session: HdcSession) {
        val config = TransferConfig.fromBytes(data)
        daemon.log("CMD_APP_CHECK: fileSize=${config.fileSize} options=${config.options} " +
            "optionalName=${config.optionalName} functionName=${config.functionName}")

        val tempDir = getTempDir()
        val localPath = "$tempDir/${config.optionalName}"

        // Open file for writing
        val fos = FileOutputStream(localPath, false)
        val state = InstallState(
            localPath = localPath,
            options = config.options,
            optionalName = config.optionalName,
            fileSize = config.fileSize,
            outStream = fos
        )
        states[session.channelId] = state
        daemon.log("Opened temp file: $localPath")

        // Send CMD_APP_BEGIN to tell host we're ready for data
        session.sendPacket(CMD_APP_BEGIN, ByteArray(0))
        daemon.log("Sent CMD_APP_BEGIN for channelId=${session.channelId}")
    }

    fun handleAppBegin(session: HdcSession) {
        // Re-open if needed (on retry)
        val state = states[session.channelId]
        if (state != null && state.outStream == null) {
            val fos = FileOutputStream(state.localPath, false)
            states[session.channelId] = state.copy(outStream = fos, received = 0)
        }
        daemon.log("CMD_APP_BEGIN: channelId=${session.channelId}")
    }

    fun handleAppData(data: ByteArray, session: HdcSession) {
        val state = states[session.channelId]
        if (state == null) {
            daemon.log("CMD_APP_DATA: no state for channelId=${session.channelId}")
            return
        }
        val fos = state.outStream
        if (fos == null) {
            daemon.log("CMD_APP_DATA: file not open for channelId=${session.channelId}")
            return
        }
        try {
            val headerBytes = data.copyOfRange(0, PAYLOAD_PREFIX_RESERVE)
            val payload = TransferPayload.fromBytes(headerBytes)
            val fileData = data.copyOfRange(PAYLOAD_PREFIX_RESERVE, data.size)
            val pct = if (state.fileSize > 0) (state.received + fileData.size) * 100 / state.fileSize else 0
            daemon.log("CMD_APP_DATA: chunk#${state.chunks + 1} idx=${payload.index} compType=${payload.compressType} compSize=${payload.compressSize} unCompSize=${payload.uncompressSize} fileDataSize=${fileData.size} progress=$pct%")
            fos.write(fileData)
            val newReceived = state.received + fileData.size
            val updatedState = state.copy(received = newReceived, chunks = state.chunks + 1)
            states[session.channelId] = updatedState

            if (newReceived >= state.fileSize) {
                daemon.log("All data received ($newReceived/${state.fileSize}), triggering install")
                states.remove(session.channelId)
                state.outStream?.close()
                doInstall(updatedState, session)
            }
        } catch (e: Exception) {
            daemon.log("CMD_APP_DATA write error: ${e.message}")
        }
    }

    fun handleAppFinish(session: HdcSession) {
        val state = states.remove(session.channelId)
        if (state == null) {
            daemon.log("CMD_APP_FINISH: no state for channelId=${session.channelId}")
            session.sendResult(false, "No install state")
            return
        }

        try {
            state.outStream?.close()
        } catch (_: Exception) {}

        doInstall(state, session)
    }

    private fun doInstall(state: InstallState, session: HdcSession) {
        val localPath = state.localPath
        daemon.log("Installing: received=${state.received}/fileSize=${state.fileSize} path=$localPath")

        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) {
            daemon.log("Install file not found or empty: $localPath")
            session.sendResult(false, "[Fail] file not found")
            cleanupFile(localPath)
            return
        }

        try {
            val ctx = daemon.getApplicationContext()
                ?: run {
                    session.sendResult(false, "[Fail] no application context")
                    cleanupFile(localPath)
                    return
                }

            val installed = HapInstaller(ctx).install(localPath)
            daemon.log("Installed: bundle=${installed.bundleName} module=${installed.moduleName} ability=${installed.mainAbility}")
            session.sendResult(true, "[Success] installed ${installed.bundleName}/${installed.mainAbility}")
        } catch (e: Exception) {
            daemon.log("Install failed: ${e.message}")
            session.sendResult(false, "[Fail] ${e.message}")
        } finally {
            cleanupFile(localPath)
        }
    }

    fun handleAppUninstall(data: ByteArray, session: HdcSession) {
        val payload = String(data, Charsets.UTF_8).trim()
        daemon.log("CMD_APP_UNINSTALL: payload=$payload")

        // Parse: options + package name
        // Example: "-s com.example.app" or just "com.example.app"
        val parts = payload.split(Regex("\\s+"))
        val packageName = parts.lastOrNull { !it.startsWith("-") } ?: payload

        try {
            val ctx = daemon.getApplicationContext()
                ?: run {
                    session.sendUninstallResult("[Fail] no application context")
                    return
                }
            HapInstaller(ctx).uninstall(packageName)
            daemon.log("Uninstalled: $packageName")
            session.sendUninstallResult("[Success] uninstalled $packageName")
        } catch (e: Exception) {
            daemon.log("Uninstall failed: ${e.message}")
            session.sendUninstallResult("[Fail] ${e.message}")
        }
    }

    private fun cleanupFile(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {}
        // Also try to clean up .tar extraction dir
        if (path.endsWith(".tar")) {
            val dir = path.substring(0, path.length - 4)
            try {
                File(dir).deleteRecursively()
            } catch (_: Exception) {}
        }
    }
}
