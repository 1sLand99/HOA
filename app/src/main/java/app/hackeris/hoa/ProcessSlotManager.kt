package app.hackeris.hoa

import android.content.Context
import android.os.Process
import java.io.File

object ProcessSlotManager {

    const val MAX_SLOTS = 10
    private const val SLOT_PREFIX = "hap_slot_"
    private const val RESERVED_PREFIX = "RESERVED|"
    private const val RESERVATION_TIMEOUT_MS = 30_000L
    private const val SEPARATOR = "||"

    fun allocateSlot(context: Context, contentDir: String? = null): Int {
        val dir = slotDir(context)
        dir.mkdirs()

        for (i in 0 until MAX_SLOTS) {
            val lockFile = File(dir, "$SLOT_PREFIX$i")
            val content = lockFile.takeIf { it.exists() }?.readText() ?: ""
            if (content.isEmpty()) {
                val data = RESERVED_PREFIX + System.currentTimeMillis() +
                    if (contentDir != null) SEPARATOR + contentDir else ""
                lockFile.writeText(data)
                return i
            }
            if (isStale(content)) {
                val data = RESERVED_PREFIX + System.currentTimeMillis() +
                    if (contentDir != null) SEPARATOR + contentDir else ""
                lockFile.writeText(data)
                return i
            }
        }
        return -1
    }

    fun claimSlot(context: Context, slot: Int, contentDir: String? = null) {
        val lockFile = File(slotDir(context), "$SLOT_PREFIX$slot")
        val pid = Process.myPid().toString()
        val data = if (contentDir != null) "$pid$SEPARATOR$contentDir" else pid
        lockFile.writeText(data)
    }

    fun releaseSlot(context: Context, slot: Int) {
        File(slotDir(context), "$SLOT_PREFIX$slot").delete()
    }

    /**
     * Returns the contentDir (HAP module directory) for the given slot,
     * or null if the slot is not allocated / has no contentDir info.
     * Works for both RESERVED (pre-claim) and claimed slots.
     */
    fun getContentDir(context: Context, slot: Int): String? {
        val lockFile = File(slotDir(context), "$SLOT_PREFIX$slot")
        if (!lockFile.exists()) return null
        val content = lockFile.readText()
        if (content.isEmpty()) return null
        val sepIdx = content.indexOf(SEPARATOR)
        if (sepIdx < 0) return null
        return content.substring(sepIdx + SEPARATOR.length)
    }

    private fun isStale(content: String): Boolean {
        if (content.startsWith(RESERVED_PREFIX)) {
            val ts = content.removePrefix(RESERVED_PREFIX).takeWhile { it != '|' }.toLongOrNull() ?: 0
            return System.currentTimeMillis() - ts > RESERVATION_TIMEOUT_MS
        }
        val pidPart = content.substringBefore(SEPARATOR)
        val pid = pidPart.toIntOrNull() ?: 0
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
