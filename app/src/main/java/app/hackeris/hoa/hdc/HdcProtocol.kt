package app.hackeris.hoa.hdc

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HDC wire-protocol codec.
 *
 * The HDC protocol uses a Protobuf-like TLV format for structured messages
 * and a fixed packed-struct format for the Channel handshake.
 */

// ── HDC constants ──────────────────────────────────────────────────────────
const val PACKET_FLAG_H: Byte = 'H'.code.toByte()
const val PACKET_FLAG_W: Byte = 'W'.code.toByte()
const val VER_PROTOCOL: Byte = 1
const val PAYLOAD_HEAD_SIZE: Int = 11
const val VCODE: Byte = 0x09
const val DEFAULT_PORT: Int = 8710
const val BANNER: String = "OHOS HDC"
const val CHANNEL_HANDSHAKE_SIZE: Int = 108  // banner[12] + connectKey[32] + version[64]
const val MAX_CONNECTKEY_SIZE: Int = 32
const val BUF_SIZE_TINY: Int = 64

// ── HDC command constants (from HdcCommand enum) ────────────────────────────
const val CMD_KERNEL_HANDSHAKE: Int = 1
const val CMD_KERNEL_CHANNEL_CLOSE: Int = 2
const val CMD_KERNEL_ECHO: Int = 9
const val CMD_KERNEL_ECHO_RAW: Int = 10
const val CMD_APP_CHECK: Int = 3501
const val CMD_APP_BEGIN: Int = 3502
const val CMD_APP_DATA: Int = 3503
const val CMD_APP_FINISH: Int = 3504
const val CMD_APP_UNINSTALL: Int = 3505

// ── Protobuf wire types ────────────────────────────────────────────────────
private const val WIRE_VARINT = 0
private const val WIRE_LENGTH_DELIMITED = 2

private fun makeTag(fieldNumber: Int, wireType: Int): Int =
    (fieldNumber shl 3) or wireType

// ── Varint codec ───────────────────────────────────────────────────────────

fun writeVarint(value: Long, out: OutputStream) {
    var v = value
    while (v >= 0x80) {
        out.write((v.toInt() and 0x7F) or 0x80)
        v = v ushr 7
    }
    out.write(v.toInt() and 0x7F)
}

fun readVarint(input: InputStream): Long {
    var result: Long = 0
    var shift = 0
    while (true) {
        val b = input.read()
        if (b < 0) throw java.io.EOFException("Unexpected end of varint stream")
        result = result or ((b.toLong() and 0x7F) shl shift)
        if ((b and 0x80) == 0) break
        shift += 7
    }
    return result
}

// ── Protobuf-style field writers ────────────────────────────────────────────

fun writeTag(fieldNumber: Int, wireType: Int, out: OutputStream) {
    writeVarint(makeTag(fieldNumber, wireType).toLong(), out)
}

fun writeUint32Field(fieldNumber: Int, value: Int, out: OutputStream) {
    writeTag(fieldNumber, WIRE_VARINT, out)
    writeVarint(value.toLong() and 0xFFFFFFFFL, out)
}

fun writeUint64Field(fieldNumber: Int, value: Long, out: OutputStream) {
    writeTag(fieldNumber, WIRE_VARINT, out)
    writeVarint(value, out)
}

fun writeBoolField(fieldNumber: Int, value: Boolean, out: OutputStream) {
    writeTag(fieldNumber, WIRE_VARINT, out)
    writeVarint(if (value) 1L else 0L, out)
}

fun writeStringField(fieldNumber: Int, value: String, out: OutputStream) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeTag(fieldNumber, WIRE_LENGTH_DELIMITED, out)
    writeVarint(bytes.size.toLong(), out)
    out.write(bytes)
}

// ── Protobuf-style field reader ────────────────────────────────────────────

class ProtoReader(private val input: InputStream) {
    /** Read next field: returns Pair(tag, wireType) or null at end of stream. */
    fun readTag(): Pair<Int, Int>? {
        val v = try {
            readVarintInternal()
        } catch (_: java.io.EOFException) {
            return null
        }
        if (v == 0L) return null
        val tag = v.toInt()
        val fieldNumber = tag ushr 3
        val wireType = tag and 0x07
        return Pair(fieldNumber, wireType)
    }

    fun readVarint(): Long = readVarintInternal()

    private fun readVarintInternal(): Long {
        var result: Long = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("Unexpected end of varint stream")
            result = result or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    fun readString(): String {
        val len = readVarint().toInt()
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw java.io.EOFException("Truncated string field")
            offset += n
        }
        return String(buf, Charsets.UTF_8)
    }

    fun readBytes(len: Int): ByteArray {
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw java.io.EOFException("Truncated bytes")
            offset += n
        }
        return buf
    }
}

// ── PayloadHead (8 bytes, big-endian integers) ─────────────────────────────

data class PayloadHead(
    val flagH: Byte = PACKET_FLAG_H,
    val flagW: Byte = PACKET_FLAG_W,
    val reserve0: Byte = 0,
    val reserve1: Byte = 0,
    val protocolVer: Byte = VER_PROTOCOL,
    val headSize: Short = 0,   // network order
    val dataSize: Int = 0      // network order
) {
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(PAYLOAD_HEAD_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(flagH)
        buf.put(flagW)
        buf.put(reserve0)
        buf.put(reserve1)
        buf.put(protocolVer)
        buf.putShort(headSize)
        buf.putInt(dataSize)
        return buf.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): PayloadHead? {
            if (bytes.size < PAYLOAD_HEAD_SIZE) return null
            val buf = ByteBuffer.wrap(bytes, 0, PAYLOAD_HEAD_SIZE).order(ByteOrder.BIG_ENDIAN)
            val fh = buf.get()
            val fw = buf.get()
            if (fh != PACKET_FLAG_H || fw != PACKET_FLAG_W) return null
            val r0 = buf.get()
            val r1 = buf.get()
            val pv = buf.get()
            val hs = buf.short
            val ds = buf.int
            return PayloadHead(fh, fw, r0, r1, pv, hs, ds)
        }
    }
}

// ── PayloadProtect (serialized via protobuf) ───────────────────────────────

data class PayloadProtect(
    val channelId: Int = 0,
    val commandFlag: Int = 0,
    val checkSum: Int = 0,  // uint8
    val vCode: Int = VCODE.toInt() and 0xFF
) {
    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        if (channelId != 0)
            writeUint32Field(1, channelId, out)
        if (commandFlag != 0)
            writeUint32Field(2, commandFlag, out)
        if (checkSum != 0)
            writeUint32Field(3, checkSum, out)
        writeUint32Field(4, vCode, out)
        return out.toByteArray()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): PayloadProtect {
            val reader = ProtoReader(bytes.inputStream())
            var channelId = 0
            var commandFlag = 0
            var checkSum = 0
            var vCode = VCODE.toInt() and 0xFF
            while (true) {
                val pair = reader.readTag() ?: break
                when (pair.first) {
                    1 -> channelId = reader.readVarint().toInt()
                    2 -> commandFlag = reader.readVarint().toInt()
                    3 -> checkSum = reader.readVarint().toInt()
                    4 -> vCode = reader.readVarint().toInt()
                    else -> skipField(pair.second, reader)
                }
            }
            return PayloadProtect(channelId, commandFlag, checkSum, vCode)
        }
    }
}

// ── ChannelHandShake (108 bytes packed struct) ─────────────────────────────

data class ChannelHandShake(
    val banner: String = BANNER,
    val channelId: Int = 0,
    val connectKey: String = "",
    val version: String = ""
) {
    fun toBytes(): ByteArray {
        val buf = ByteArray(CHANNEL_HANDSHAKE_SIZE)
        // banner[12]
        val bannerBytes = banner.toByteArray(Charsets.UTF_8)
        System.arraycopy(bannerBytes, 0, buf, 0, minOf(bannerBytes.size, 12))
        // union: first 4 bytes are channelId (little-endian), total 32 bytes
        // When responding, write channelId into bytes 12..15
        val bb = ByteBuffer.wrap(buf, 12, 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(channelId)
        if (connectKey.isNotEmpty()) {
            val keyBytes = connectKey.toByteArray(Charsets.UTF_8)
            System.arraycopy(keyBytes, 0, buf, 12, minOf(keyBytes.size, MAX_CONNECTKEY_SIZE))
        }
        // version[64] at offset 44
        val verBytes = version.toByteArray(Charsets.UTF_8)
        System.arraycopy(verBytes, 0, buf, 44, minOf(verBytes.size, BUF_SIZE_TINY))
        return buf
    }

    companion object {
        fun fromBytes(bytes: ByteArray): ChannelHandShake? {
            if (bytes.size < CHANNEL_HANDSHAKE_SIZE) return null
            // banner[12]
            val banner = String(bytes, 0, 12, Charsets.UTF_8).trimEnd(' ')
            if (!banner.startsWith("OHOS HDC")) return null
            // union @ offset 12
            val channelId = ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val connectKey = String(bytes, 12, MAX_CONNECTKEY_SIZE, Charsets.UTF_8).trimEnd(' ')
            // version @ offset 44
            val version = String(bytes, 44, BUF_SIZE_TINY, Charsets.UTF_8).trimEnd(' ')
            return ChannelHandShake(banner, channelId, connectKey, version)
        }
    }
}

// ── SessionHandShake fields ────────────────────────────────────────────────

data class SessionHandShake(
    val banner: String = BANNER,
    val authType: Int = 0,
    val sessionId: Int = 0,
    val connectKey: String = "",
    val buf: String = "",
    val version: String = ""
) {
    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        writeStringField(1, banner, out)
        writeUint32Field(2, authType, out)
        writeUint32Field(3, sessionId, out)
        if (connectKey.isNotEmpty())
            writeStringField(4, connectKey, out)
        if (buf.isNotEmpty())
            writeStringField(5, buf, out)
        if (version.isNotEmpty())
            writeStringField(6, version, out)
        return out.toByteArray()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): SessionHandShake {
            val reader = ProtoReader(bytes.inputStream())
            var banner = BANNER
            var authType = 0
            var sessionId = 0
            var connectKey = ""
            var buf = ""
            var version = ""
            while (true) {
                val pair = reader.readTag() ?: break
                when (pair.first) {
                    1 -> banner = reader.readString()
                    2 -> authType = reader.readVarint().toInt()
                    3 -> sessionId = reader.readVarint().toInt()
                    4 -> connectKey = reader.readString()
                    5 -> buf = reader.readString()
                    6 -> version = reader.readString()
                    else -> skipField(pair.second, reader)
                }
            }
            return SessionHandShake(banner, authType, sessionId, connectKey, buf, version)
        }
    }
}

const val PAYLOAD_PREFIX_RESERVE: Int = 64

// ── TransferPayload (CMD_APP_DATA prefix) ──────────────────────────────────

data class TransferPayload(
    val index: Long = 0,
    val compressType: Int = 0,
    val compressSize: Int = 0,
    val uncompressSize: Int = 0
) {
    companion object {
        fun fromBytes(bytes: ByteArray): TransferPayload {
            val reader = ProtoReader(bytes.inputStream())
            var index: Long = 0
            var compressType = 0
            var compressSize = 0
            var uncompressSize = 0
            while (true) {
                val pair = reader.readTag() ?: break
                when (pair.first) {
                    1 -> index = reader.readVarint()
                    2 -> compressType = reader.readVarint().toInt()
                    3 -> compressSize = reader.readVarint().toInt()
                    4 -> uncompressSize = reader.readVarint().toInt()
                    else -> skipField(pair.second, reader)
                }
            }
            return TransferPayload(index, compressType, compressSize, uncompressSize)
        }
    }
}

// ── TransferConfig (CMD_APP_CHECK payload) ────────────────────────────────

data class TransferConfig(
    val fileSize: Long = 0,
    val atime: Long = 0,
    val mtime: Long = 0,
    val options: String = "",
    val path: String = "",
    val optionalName: String = "",
    val updateIfNew: Boolean = false,
    val compressType: Int = 0,
    val holdTimestamp: Boolean = false,
    val functionName: String = "",
    val clientCwd: String = "",
    val reserve1: String = "",
    val reserve2: String = ""
) {
    companion object {
        fun fromBytes(bytes: ByteArray): TransferConfig {
            val reader = ProtoReader(bytes.inputStream())
            var fileSize: Long = 0
            var atime: Long = 0
            var mtime: Long = 0
            var options = ""
            var path = ""
            var optionalName = ""
            var updateIfNew = false
            var compressType = 0
            var holdTimestamp = false
            var functionName = ""
            var clientCwd = ""
            var reserve1 = ""
            var reserve2 = ""
            while (true) {
                val pair = reader.readTag() ?: break
                when (pair.first) {
                    1 -> fileSize = reader.readVarint()
                    2 -> atime = reader.readVarint()
                    3 -> mtime = reader.readVarint()
                    4 -> options = reader.readString()
                    5 -> path = reader.readString()
                    6 -> optionalName = reader.readString()
                    7 -> updateIfNew = reader.readVarint() != 0L
                    8 -> compressType = reader.readVarint().toInt()
                    9 -> holdTimestamp = reader.readVarint() != 0L
                    10 -> functionName = reader.readString()
                    11 -> clientCwd = reader.readString()
                    12 -> reserve1 = reader.readString()
                    13 -> reserve2 = reader.readString()
                    else -> skipField(pair.second, reader)
                }
            }
            return TransferConfig(fileSize, atime, mtime, options, path, optionalName,
                updateIfNew, compressType, holdTimestamp, functionName, clientCwd, reserve1, reserve2)
        }
    }
}

// ── Utility ────────────────────────────────────────────────────────────────

private fun skipField(wireType: Int, reader: ProtoReader) {
    when (wireType) {
        WIRE_VARINT -> reader.readVarint()
        WIRE_LENGTH_DELIMITED -> {
            val len = reader.readVarint().toInt()
            reader.readBytes(len)
        }
        1 -> reader.readBytes(8)  // FIXED64
        5 -> reader.readBytes(4)  // FIXED32
        else -> {} // unknown
    }
}

/** Build a complete HDC packet: PayloadHead + PayloadProtect + data */
fun buildPacket(channelId: Int, commandFlag: Int, data: ByteArray): ByteArray {
    val protect = PayloadProtect(channelId = channelId, commandFlag = commandFlag)
    val protectBytes = protect.toBytes()
    val head = PayloadHead(
        headSize = protectBytes.size.toShort(),
        dataSize = data.size
    )
    val headBytes = head.toBytes()
    val result = ByteArray(headBytes.size + protectBytes.size + data.size)
    System.arraycopy(headBytes, 0, result, 0, headBytes.size)
    System.arraycopy(protectBytes, 0, result, headBytes.size, protectBytes.size)
    System.arraycopy(data, 0, result, headBytes.size + protectBytes.size, data.size)
    return result
}

/** Parse one HDC packet from accumulated buffer. Returns Triple(head, protect, data) or null if incomplete. */
fun parsePacket(buf: ByteArray, offset: Int, length: Int): Triple<PayloadHead, PayloadProtect, ByteArray>? {
    if (length < PAYLOAD_HEAD_SIZE) return null
    val head = PayloadHead.fromBytes(buf.copyOfRange(offset, offset + PAYLOAD_HEAD_SIZE)) ?: return null
    val protectSize = head.headSize.toInt() and 0xFFFF
    val dataSize = head.dataSize
    val totalNeeded = PAYLOAD_HEAD_SIZE + protectSize + dataSize
    if (length < totalNeeded) return null
    val protectBytes = buf.copyOfRange(offset + PAYLOAD_HEAD_SIZE, offset + PAYLOAD_HEAD_SIZE + protectSize)
    val protect = PayloadProtect.fromBytes(protectBytes)
    val data = buf.copyOfRange(
        offset + PAYLOAD_HEAD_SIZE + protectSize,
        offset + PAYLOAD_HEAD_SIZE + protectSize + dataSize
    )
    return Triple(head, protect, data)
}

/** Size of a complete packet including header, protect, and payload */
fun packetTotalSize(head: PayloadHead): Int =
    PAYLOAD_HEAD_SIZE + (head.headSize.toInt() and 0xFFFF) + head.dataSize
