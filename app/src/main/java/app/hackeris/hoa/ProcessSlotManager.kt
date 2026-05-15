package app.hackeris.hoa

import android.content.Context
import android.os.Process
import java.io.File

object ProcessSlotManager {

    const val MAX_SLOTS = 5
    private const val SLOT_PREFIX = "hap_slot_"
    private const val RESERVED_PREFIX = "RESERVED|"
    private const val RESERVATION_TIMEOUT_MS = 30_000L

    fun allocateSlot(context: Context): Int {
        val dir = slotDir(context)
        dir.mkdirs()

        for (i in 0 until MAX_SLOTS) {
            val lockFile = File(dir, "$SLOT_PREFIX$i")
            val content = lockFile.takeIf { it.exists() }?.readText() ?: ""
            if (content.isEmpty()) {
                lockFile.writeText("$RESERVED_PREFIX${System.currentTimeMillis()}")
                return i
            }
            if (isStale(content)) {
                lockFile.writeText("$RESERVED_PREFIX${System.currentTimeMillis()}")
                return i
            }
        }
        return -1
    }

    fun claimSlot(context: Context, slot: Int) {
        val lockFile = File(slotDir(context), "$SLOT_PREFIX$slot")
        lockFile.writeText(Process.myPid().toString())
    }

    fun releaseSlot(context: Context, slot: Int) {
        File(slotDir(context), "$SLOT_PREFIX$slot").delete()
    }

    private fun isStale(content: String): Boolean {
        if (content.startsWith(RESERVED_PREFIX)) {
            val ts = content.removePrefix(RESERVED_PREFIX).toLongOrNull() ?: 0
            return System.currentTimeMillis() - ts > RESERVATION_TIMEOUT_MS
        }
        val pid = content.toIntOrNull() ?: 0
        return !isProcessAlive(pid)
    }

    private fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            File("/proc/$pid").exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun slotDir(context: Context): File {
        return File(context.filesDir, "process_slots")
    }
}
