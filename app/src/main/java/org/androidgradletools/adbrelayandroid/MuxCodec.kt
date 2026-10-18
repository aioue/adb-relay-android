package org.androidgradletools.adbrelayandroid

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MuxCodec {
    private val MAGIC = byteArrayOf(0x41, 0x44, 0x42, 0x4d) // ADBM
    const val HEADER_SIZE = 16

    const val TYPE_DATA: Int = 0
    const val TYPE_OPEN: Int = 1
    const val TYPE_CLOSE: Int = 2
    const val TYPE_DIAL: Int = 3
    const val TYPE_DIAL_RESULT: Int = 4

    data class Frame(val type: Int, val channelId: Int, val payload: ByteArray)

    fun encode(type: Int, channelId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        validate(type, channelId)
        val n = payload.size
        val out = ByteBuffer.allocate(HEADER_SIZE + n).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.put(1.toByte())
        out.put((type and 0xff).toByte())
        out.putShort(0)
        out.putInt(channelId)
        out.putInt(n)
        out.put(payload)
        return out.array()
    }

    /**
     * channelId 0 is reserved for DIAL / DIAL_RESULT only; types 0–2 with channelId 0 are a
     * protocol error (server plan §5) and must fail loudly, not skip the frame. Unknown types
     * are a hard close too (§5: unknown mux types → close WebSocket), never skipped bytes.
     */
    private fun validate(type: Int, channelId: Int) {
        require(type in TYPE_DATA..TYPE_DIAL_RESULT) { "mux: unknown type $type" }
        if (type == TYPE_DIAL || type == TYPE_DIAL_RESULT) {
            require(channelId == 0) { "mux: type $type requires channelId 0" }
        } else {
            require(channelId != 0) { "mux: type $type requires non-zero channelId" }
        }
    }

    /**
     * Parses complete mux frames from [buf]. Returns consumed byte count and list of frames.
     * Throws on invalid magic or version.
     */
    fun parse(buf: ByteArray, offset: Int, length: Int): Pair<Int, List<Frame>> {
        val frames = ArrayList<Frame>()
        var pos = offset
        val end = offset + length
        while (end - pos >= HEADER_SIZE) {
            if (buf[pos] != MAGIC[0] || buf[pos + 1] != MAGIC[1] ||
                buf[pos + 2] != MAGIC[2] || buf[pos + 3] != MAGIC[3]
            ) {
                throw IllegalArgumentException("mux: invalid magic")
            }
            val version = buf[pos + 4].toInt() and 0xff
            if (version != 1) {
                throw IllegalArgumentException("mux: bad version $version")
            }
            val type = buf[pos + 5].toInt() and 0xff
            val bb = ByteBuffer.wrap(buf, pos + 8, 8).order(ByteOrder.BIG_ENDIAN)
            val channelId = bb.int
            validate(type, channelId)
            val payloadLength = bb.int
            val total = HEADER_SIZE + payloadLength
            if (end - pos < total) {
                break
            }
            val payload = if (payloadLength > 0) {
                buf.copyOfRange(pos + HEADER_SIZE, pos + total)
            } else {
                ByteArray(0)
            }
            frames.add(Frame(type, channelId, payload))
            pos += total
        }
        return Pair(pos - offset, frames)
    }
}
