package org.androidgradletools.adbrelayandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the bake-time URL gate the service now enforces at start (§3 WSS-only, no query). */
class RelayUrlTest {

    @Test
    fun acceptsOriginRootWss() {
        assertTrue(RelayUrl.isValid("wss://relay.example.com/"))
    }

    @Test
    fun acceptsBareHostWss() {
        assertTrue(RelayUrl.isValid("wss://relay.example.com"))
    }

    @Test
    fun rejectsPlainWs() {
        // Bridge-only scheme; a ws:// bake must fail validation, not at connect.
        assertFalse(RelayUrl.isValid("ws://relay.example.com/"))
    }

    @Test
    fun rejectsQueryString() {
        // Parity with voice_pipe_lib.py, which rejects `?` too.
        assertFalse(RelayUrl.isValid("wss://relay.example.com/?token=x"))
    }

    @Test
    fun rejectsNonWssSchemes() {
        assertFalse(RelayUrl.isValid("https://relay.example.com/"))
        assertFalse(RelayUrl.isValid("relay.example.com/"))
        assertFalse(RelayUrl.isValid(""))
    }
}