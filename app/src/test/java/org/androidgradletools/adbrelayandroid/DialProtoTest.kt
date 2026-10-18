package org.androidgradletools.adbrelayandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialProtoTest {

    @Test
    fun parsesBridgeDialFrame() {
        val payload = """{"op":"dial","phase":"pair","port":37123}""".toByteArray(Charsets.UTF_8)
        val ok = DialProto.parseDial(payload) as DialProto.DialParse.Ok
        assertEquals("pair", ok.dial.phase)
        assertEquals(37123, ok.dial.port)
    }

    @Test
    fun ignoresUnknownJsonKeys() {
        val payload = """{"op":"dial","phase":"connect","port":1,"extra":"x"}"""
            .toByteArray(Charsets.UTF_8)
        val ok = DialProto.parseDial(payload) as DialProto.DialParse.Ok
        assertEquals("connect", ok.dial.phase)
    }

    @Test
    fun missingFieldsMapToBadPort() {
        val cases = listOf(
            """{"phase":"pair","port":37123}""", // no op
            """{"op":"dial","port":37123}""", // no phase
            """{"op":"dial","phase":"pair"}""", // no port
            """{"op":"dial","phase":"sideload","port":37123}""", // bad phase
            """{"op":"dial","phase":"pair","port":0}""",
            """{"op":"dial","phase":"pair","port":65536}""",
            """{"op":"dial","phase":"pair","port":"37123"}""", // string port
            """not json""",
            ByteArray(0),
            ByteArray(257), // over the 256-byte payload cap
        )
        for (c in cases) {
            val payload = when (c) {
                is String -> c.toByteArray(Charsets.UTF_8)
                is ByteArray -> c
                else -> error("unreachable")
            }
            val bad = DialProto.parseDial(payload) as DialProto.DialParse.Bad
            assertEquals("case $c", DialProto.ERROR_BAD_PORT, bad.error)
        }
    }

    @Test
    fun dialResultOkCarriesViaOnly() {
        val bytes = DialProto.buildDialResult("pair", true, "link", null)
        val json = String(bytes, Charsets.UTF_8)
        assertTrue(json.contains("\"ok\":true"))
        assertTrue(json.contains("\"via\":\"link\""))
        assertTrue(!json.contains("error"))
    }

    @Test
    fun dialResultFailureCarriesErrorOnly() {
        val bytes = DialProto.buildDialResult("connect", false, null, DialProto.ERROR_REFUSED)
        val json = String(bytes, Charsets.UTF_8)
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"error\":\"refused\""))
        assertTrue(!json.contains("via"))
        // No address, no code, no token — ever.
        assertTrue(!json.contains("192.168"))
    }

    @Test
    fun bridgeJsonMatchesPhoneJson() {
        // The UK bridge encodes dial JSON as: {"op":"dial","phase":"<p>","port":<n>}
        // (see scripts/mum-phone/voice_pipe_lib.py encode_dial); key order is fixed by
        // JSONObject insertion here — same three keys, parseable by both ends.
        val bridge = """{"op":"dial","phase":"connect","port":39567}"""
        val ok = DialProto.parseDial(bridge.toByteArray(Charsets.UTF_8)) as DialProto.DialParse.Ok
        assertEquals(39567, ok.dial.port)
    }
}