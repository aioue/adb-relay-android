package org.androidgradletools.adbrelayandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pins the §4 guard: OPEN with channelId 0 is a protocol error (hard-close 1011),
 * not a silent drop that leaves the dev side hung. */
class MuxProtocolErrorTest {

    @Test
    fun openChannelIdZeroIsAProtocolError() {
        try {
            requireRoutableOpen(0)
            fail("OPEN with channelId 0 must throw MuxProtocolError")
        } catch (e: MuxProtocolError) {
            assertEquals("open channelId=0", e.message)
        }
    }

    @Test
    fun openRealChannelIdsPass() {
        requireRoutableOpen(1)
        requireRoutableOpen(0xFFFF)
    }
}