package app.hackeris.hoa.hdc

import java.io.ByteArrayOutputStream
import java.io.InputStream

class HdcShellHandler(private val daemon: HdcDaemon) {

    fun handleShellCommand(data: ByteArray, session: HdcSession) {
        var command = String(data, Charsets.UTF_8).trim()
        // hdc client wraps the shell command in quotes; strip them
        if (command.startsWith("\"") && command.endsWith("\"")) {
            command = command.substring(1, command.length - 1)
        }
        daemon.log("CMD_UNITY_EXECUTE: command=$command")

        if (command.isEmpty()) {
            session.sendPacket(CMD_KERNEL_ECHO_RAW, ByteArray(0))
            finishCommand(session)
            return
        }

        // Mock OHOS-specific commands that don't exist on Android
        val mockResult = mockOhosCommand(command)
        if (mockResult != null) {
            daemon.log("CMD_UNITY_EXECUTE: mocked OHOS command, result=${mockResult}")
            if (mockResult.isNotEmpty()) {
                session.sendPacket(CMD_KERNEL_ECHO_RAW, mockResult.toByteArray(Charsets.UTF_8))
            } else {
                session.sendPacket(CMD_KERNEL_ECHO_RAW, ByteArray(0))
            }
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
    /**
     * Mock OHOS-specific commands that don't exist on Android.
     * Returns null if the command should be executed normally.
     */
    private fun mockOhosCommand(command: String): String? {
        // param get — system parameter queries
        val paramGet = Regex("^param get (\\S+)")
        paramGet.find(command)?.let { match ->
            val key = match.groupValues[1]
            return when (key) {
                "const.ohos.apiversion" -> "26"
                "const.ohos.apiminorversion" -> "0"
                "const.ohos.apipatchversion" -> "0"
                "const.ohos.fullname" -> "OpenHarmony-7.0.0"
                "const.ohos.releasetype" -> "Release"
                "const.ohos.sdkapiversion" -> "26"
                "const.ohos.majorversion" -> "7"
                "const.product.model" -> "HOA-01"
                "const.product.name" -> "HOA"
                "const.product.brand" -> "HOA"
                "const.product.manufacturer" -> "HOA"
                "const.product.devicetype" -> "phone"
                "const.product.software.version" -> "7.0.0"
                "const.product.hardware.version" -> "1.0.0"
                "const.build.product" -> "HOA"
                "const.os.distributedsupport" -> "false"
                "const.telephony.enabled" -> "false"
                "const.wifi.direct.network.type" -> "0"
                "const.debuggable" -> "1"
                "const.hdc.version" -> "Ver: 3.2.0c-HOA"
                "const.hdc.secure" -> "0"
                "const.boot.oemmode" -> "rd"
                "const.pc_security.fileguard_force_enable" -> "false"
                "persist.hdc.root" -> "0"
                "persist.hdc.port" -> "8710"
                "persist.hdc.mode" -> "tcp"
                "persist.hdc.auth_bypass" -> "1"
                "persist.hdc.control.shell" -> "true"
                "persist.hdc.control.file" -> "true"
                "persist.hdc.control.fport" -> "true"
                "persist.hdc.version" -> "3.2.0c-HOA"
                "persist.hdc.control.enterprise_connect_validation" -> "false"
                "persist.hdc.daemon.auth_result" -> "SUCCESS"
                "persist.hdc.jdwp" -> ""
                "ohos.qemu.hvd.name" -> ""
                "persist.sys.hilog.fullname" -> "HarmonyOS-7.0.0"
                "persist.sys.hilog.version" -> "7.0.0"
                "persist.sys.hilog.debug.on" -> "true"
                "ro.build.version.sdk" -> "26"
                else -> {
                    daemon.log("Unmocked param get: $key, returning empty")
                    ""  // unknown param keys: return empty, don't let shell fail
                }
            }
        }

        // param ls / param dump etc.
        if (command.startsWith("param ")) return ""

        // hidumper — fault logger query (DevEco Studio device validation)
        if (command.startsWith("hidumper")) {
            return when {
                command.contains("Faultlogger") -> "[]"
                command.contains("-s 1201") -> "{}"
                command.contains("-s") -> "{}"
                else -> ""
            }
        }

        // hilog — OHOS logging command
        if (command.startsWith("hilog")) return ""

        // mediatool — OHOS media query
        if (command.startsWith("mediatool")) return "{}"

        // snapshot_display — OHOS screenshot
        if (command.startsWith("snapshot_display")) return ""

        // bm install — redirect to HapInstaller (remap /data/local/tmp/ paths)
        val bmInstall = Regex("^bm install -p (.+)")
        bmInstall.find(command)?.let { match ->
            val rawPath = match.groupValues[1].trim()
            val remappedDir = if (rawPath.startsWith("/data/local/tmp/") || rawPath.startsWith("data/local/tmp/")) {
                val subPath = rawPath.removePrefix("/").removePrefix("data/local/tmp/")
                val cacheDir = daemon.getApplicationContext()?.cacheDir?.absolutePath ?: return "[Fail] no cache dir"
                "$cacheDir/hdc/$subPath"
            } else {
                rawPath
            }
            // bm install -p points to a directory, find the .hap inside
            val dir = java.io.File(remappedDir)
            val hapFile = if (dir.isDirectory) dir.listFiles()?.firstOrNull { it.name.endsWith(".hap") } else dir
            val hapPath = hapFile?.absolutePath ?: return "[Fail] no .hap found in $remappedDir"
            daemon.log("bm install: raw=$rawPath remapped=$hapPath")
            return try {
                val ctx = daemon.getApplicationContext()
                if (ctx != null) {
                    val installed = app.hackeris.hoa.hap.HapInstaller(ctx).install(hapPath)
                    // OHOS uses MSG_OK (no prefix) for success and MSG_FAIL ("[Fail]") for errors.
                    // DevEco Studio parses these prefixes to determine install outcome.
                    ctx.sendBroadcast(android.content.Intent("app.hackeris.hoa.HAP_INSTALLED"))
                    "install bundle successfully."
                } else {
                    "[Fail] no application context"
                }
            } catch (e: Exception) {
                "[Fail] ${e.message}"
            }
        }

        // bm dump — OHOS bundle manager dump
        if (command.startsWith("bm dump")) return "{}"

        // bm uninstall
        val bmUninstall = Regex("^bm uninstall -n (.+)")
        bmUninstall.find(command)?.let { match ->
            val packageName = match.groupValues[1].trim()
            daemon.log("bm uninstall redirected: $packageName")
            return try {
                val ctx = daemon.getApplicationContext()
                if (ctx != null) {
                    app.hackeris.hoa.hap.HapInstaller(ctx).uninstall(packageName)
                    ctx.sendBroadcast(android.content.Intent("app.hackeris.hoa.HAP_INSTALLED"))
                    "uninstall bundle successfully."
                } else {
                    "[Fail] no application context"
                }
            } catch (e: Exception) {
                "[Fail] ${e.message}"
            }
        }

        // aa commands — map to Android am commands
        val aaForceStop = Regex("^aa force-stop (\\S+)")
        aaForceStop.find(command)?.let { match ->
            val pkg = match.groupValues[1]
            daemon.log("aa force-stop -> am force-stop $pkg")
            execAndroid(arrayOf("am", "force-stop", pkg))
            return ""
        }
        val aaStart = Regex("^aa start\\s+-a\\s+(\\S+)\\s+-b\\s+(\\S+)\\s+-m\\s+(\\S+)")
        aaStart.find(command)?.let { match ->
            val ability = match.groupValues[1]
            val bundle = match.groupValues[2]
            val module = match.groupValues[3]
            daemon.log("aa start: ability=$ability bundle=$bundle module=$module")
            return try {
                val ctx = daemon.getApplicationContext() ?: return "[Fail] no application context"
                val slot = app.hackeris.hoa.ProcessSlotManager.allocateSlot(ctx)
                if (slot < 0) return "[Fail] no available process slots"
                val intent = android.content.Intent().apply {
                    setClassName(ctx.packageName, "${ctx.packageName}.HoaAbilityActivity$slot")
                    putExtra("BUNDLE_NAME", bundle)
                    putExtra("MODULE_NAME", module)
                    putExtra("ABILITY_NAME", ability)
                    putExtra("PROCESS_SLOT", slot)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                             android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                             android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                ctx.startActivity(intent)
                daemon.log("aa start: launched slot=$slot")
                "start ability successfully."
            } catch (e: Exception) {
                daemon.log("aa start failed: ${e.message}")
                "[Fail] ${e.message}"
            }
        }

        // mkdir /data/local/tmp/... → remap to app cache (with or without -p)
        val mkdirCmd = Regex("^mkdir(?:\\s+-p)?\\s+[/]*data/local/tmp/(.+)")
        mkdirCmd.find(command)?.let { match ->
            val subPath = match.groupValues[1]
            val cacheDir = daemon.getApplicationContext()?.cacheDir?.absolutePath ?: return "[Fail] no cache dir"
            val remapped = "$cacheDir/hdc/$subPath"
            daemon.log("mkdir remap: $command -> $remapped")
            val dir = java.io.File(remapped)
            if (!dir.exists()) dir.mkdirs()
            return if (dir.exists()) "" else "[Fail] cannot create $remapped"
        }

        // rm -rf /data/local/tmp/... → remap to app cache
        val rmCmd = Regex("^rm\\s+-rf\\s+[/]*data/local/tmp/(.+)")
        rmCmd.find(command)?.let { match ->
            val subPath = match.groupValues[1]
            val cacheDir = daemon.getApplicationContext()?.cacheDir?.absolutePath ?: return "[Fail] no cache dir"
            val remapped = "$cacheDir/hdc/$subPath"
            daemon.log("rm remap: $command -> $remapped")
            java.io.File(remapped).deleteRecursively()
            return ""
        }

        // pgrep — not available on Android, mock for DevEco Studio
        if (command.startsWith("pgrep ")) return ""
        // pidof — not available on Android, mock for DevEco Studio
        if (command.startsWith("pidof ")) return ""

        return null  // not mocked, execute normally
    }

    private fun finishCommand(session: HdcSession) {
        // OHOS TaskFinish sends [1], host decrements to [0] and sends back as ack
        session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
        session.resetChannel()
    }

    private fun execAndroid(cmd: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val stdout = readStream(process.inputStream)
            val stderr = readStream(process.errorStream)
            process.waitFor()
            if (stderr.isNotEmpty()) stderr else stdout
        } catch (e: Exception) {
            daemon.log("execAndroid error: ${e.message}")
            ""
        }
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
