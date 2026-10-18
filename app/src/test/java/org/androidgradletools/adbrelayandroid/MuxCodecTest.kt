package org.androidgradletools.adbrelayandroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level parity with `scripts/mum-phone/voice_pipe_lib.py` — the §12 row-1 gate. A mismatch
 * here is a guaranteed failed pair before any handset is involved.
 */
class MuxCodecTest {

    private val dialJson = """{"op":"dial","phase":"pair","port":37123}"""

    @Test
    fun dialFrameMatchesServerPlanWorkedBytes() {
        val frame = MuxCodec.encode(MuxCodec.TYPE_DIAL, 0, dialJson.toByteArray(Charsets.UTF_8))
        assertEquals(16 + 41, frame.size)
        val expectedHeader = byteArrayOf(
            0x41, 0x44, 0x42, 0x4D, // ADBM
            0x01, // version
            0x03, // DIAL
            0x00, 0x00, // reserved
            0x00, 0x00, 0x00, 0x00, // channelId 0
            0x00, 0x00, 0x00, 0x29, // length 41 (0x29 — off-by-one erratum corrected 2026-10-18)
        )
        for (i in expectedHeader.indices) {
            assertEquals("byte $i", expectedHeader[i].toInt(), frame[i].toInt())
        }
        assertEquals(dialJson, String(frame, 16, 41, Charsets.UTF_8))
    }

    @Test
    fun openFrameChannelOneMatchesWorkedBytes() {
        val frame = MuxCodec.encode(MuxCodec.TYPE_OPEN, 1)
        assertArrayEquals(
            byteArrayOf(
                0x41, 0x44, 0x42, 0x4D, 0x01, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            ),
            frame,
        )
    }

    @Test
    fun dataFrameChannelOneMatchesWorkedBytes() {
        val frame = MuxCodec.encode(MuxCodec.TYPE_DATA, 1, byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(
            byteArrayOf(
                0x41, 0x44, 0x42, 0x4D, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 1, 2, 3, 4,
            ),
            frame,
        )
    }

    @Test
    fun dialResultRoundTrips() {
        val payload = DialProto.buildDialResult("pair", true, "link", null)
        val frame = MuxCodec.encode(MuxCodec.TYPE_DIAL_RESULT, 0, payload)
        val (consumed, frames) = MuxCodec.parse(frame, 0, frame.size)
        assertEquals(frame.size, consumed)
        assertEquals(1, frames.size)
        assertEquals(MuxCodec.TYPE_DIAL_RESULT, frames[0].type)
        assertEquals(0, frames[0].channelId)
        assertTrue(String(frames[0].payload, Charsets.UTF_8).contains("\"via\":\"link\""))
    }

    @Test
    fun parsesConcatenatedFramesAndKeepsRemainder() {
        val a = MuxCodec.encode(MuxCodec.TYPE_DIAL, 0, dialJson.toByteArray(Charsets.UTF_8))
        val b = MuxCodec.encode(MuxCodec.TYPE_OPEN, 1)
        val partial = a + b.copyOfRange(0, 8)
        val (consumed, frames) = MuxCodec.parse(partial, 0, partial.size)
        assertEquals(a.size, consumed)
        assertEquals(1, frames.size)
        assertEquals(MuxCodec.TYPE_DIAL, frames[0].type)
    }

    @Test
    fun types0to2RejectChannelIdZero() {
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.encode(MuxCodec.TYPE_DATA, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.encode(MuxCodec.TYPE_OPEN, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.encode(MuxCodec.TYPE_CLOSE, 0)
        }
        val bad = ByteArray(16)
        bad[0] = 0x41; bad[1] = 0x44; bad[2] = 0x42; bad[3] = 0x4D
        bad[4] = 1; bad[5] = MuxCodec.TYPE_DATA.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.parse(bad, 0, bad.size)
        }
    }

    @Test
    fun types3and4RequireChannelIdZero() {
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.encode(MuxCodec.TYPE_DIAL, 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.encode(MuxCodec.TYPE_DIAL_RESULT, 7)
        }
    }

    @Test
    fun unknownTypeIsRejectedBothDirections() {
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.encode(9, 0)
        }
        // parse-side: unknown type is a hard close (server plan §5), never a skipped byte.
        val unknown = ByteArray(16)
        unknown[0] = 0x41; unknown[1] = 0x44; unknown[2] = 0x42; unknown[3] = 0x4D
        unknown[4] = 1; unknown[5] = 9
        unknown[8] = 1 // non-zero channelId, so only the type check can trip
        assertThrows(IllegalArgumentException::class.java) {
            MuxCodec.parse(unknown, 0, unknown.size)
        }
    }
}