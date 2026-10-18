package org.androidgradletools.adbrelayandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class SessionControllerTest {

    /** Frame sink recording what the controller would put on the WebSocket. */
    private class FrameSink {
        val frames = mutableListOf<Triple<Int, Int, ByteArray>>()

        fun dialResults() = frames.filter { it.first == MuxCodec.TYPE_DIAL_RESULT }

        fun lastResultError(): String? {
            val last = dialResults().lastOrNull() ?: return null
            val json = String(last.third, Charsets.UTF_8)
            val match = Regex("\"error\":\"([a-z_]+)\"").find(json) ?: return null
            return match.groupValues[1]
        }
    }

    /**
     * Real loopback dial: the controller "dials" the client side (this is the socket it parks),
     * and [accepted] collects the adbd-side of each pair for inspection. A pure `server.accept()`
     * fake deadlocks — handleDial blocks waiting for a client that never comes.
     */
    private class FakeAdbd(val server: ServerSocket = ServerSocket(0)) {
        val accepted = mutableListOf<Socket>()
        val clients = mutableListOf<Socket>()

        fun connect(): Socket {
            val client = Socket()
            client.tcpNoDelay = true
            client.connect(InetSocketAddress("127.0.0.1", server.localPort), 2000)
            clients.add(client)
            accepted.add(server.accept())
            return client
        }
    }

    private fun controller(
        sink: FrameSink,
        candidates: List<Candidate>,
        connectFn: (String, Int, Int) -> Socket,
    ): SessionController = SessionController(
        selector = { candidates },
        sendFrame = { type, channelId, payload -> sink.frames.add(Triple(type, channelId, payload)) },
        connectFn = connectFn,
    )

    private fun dialPayload(phase: String, port: Int): ByteArray =
        """{"op":"dial","phase":"$phase","port":$port}""".toByteArray(Charsets.UTF_8)

    @Test
    fun refusedLoopbackReportsRefused() {
        // Bind then close: connecting to a closed port on loopback gives ConnectException.
        val port = ServerSocket(0).also { it.close() }.localPort
        val sink = FrameSink()
        val c = controller(
            sink,
            listOf(Candidate("127.0.0.1", Candidate.VIA_LOOPBACK)),
        ) { _, _, _ -> throw ConnectException("refused") }
        c.handleDial(dialPayload("pair", port))
        assertEquals(1, sink.dialResults().size)
        assertEquals(DialProto.ERROR_REFUSED, sink.lastResultError())
        assertFalse((sink.dialResults()[0].third).decodeToString().contains("\"ok\":true"))
    }

    @Test
    fun timeoutThenSuccessUsesFirstWorkingCandidateVia() {
        val fake = FakeAdbd()
        var calls = 0
        val sink = FrameSink()
        val c = controller(
            sink,
            listOf(
                Candidate("203.0.113.9", Candidate.VIA_LINK), // TEST-NET-3, fake dial times out
                Candidate("127.0.0.1", Candidate.VIA_LOOPBACK),
            ),
        ) { host, _, timeout ->
            calls++
            if (host == "203.0.113.9") throw SocketTimeoutException("t")
            if (timeout <= 0) throw SocketTimeoutException("t")
            fake.connect()
        }
        c.handleDial(dialPayload("connect", fake.server.localPort))
        assertEquals(2, calls)
        val result = sink.dialResults().single().third.decodeToString()
        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"via\":\"loopback\""))
        // Parked socket attaches on the next OPEN.
        assertTrue(c.attach(1))
        val sock = c.socketFor(1) ?: throw AssertionError("channel 1 socket missing after attach")
        assertTrue(!sock.isClosed)
        c.closeChannel(1)
        assertTrue(sock.isClosed)
        fake.server.close()
    }

    @Test
    fun budgetExpiryReportsTimeout() {
        val sink = FrameSink()
        val c = SessionController(
            selector = {
                listOf(Candidate("10.255.255.1", Candidate.VIA_LINK), Candidate.LOOPBACK)
            },
            sendFrame = { type, channelId, payload -> sink.frames.add(Triple(type, channelId, payload)) },
            connectFn = { _, _, timeout ->
                // Per-attempt timeout is capped by the remaining budget; simulate the dial
                // sleeping out its timeout so the overall budget expires after two attempts.
                Thread.sleep(timeout.toLong())
                throw SocketTimeoutException("t")
            },
            perAttemptTimeoutMs = 100,
            budgetMs = 150,
        )
        c.handleDial(dialPayload("pair", 5555))
        assertEquals(DialProto.ERROR_TIMEOUT, sink.lastResultError())
    }

    @Test
    fun noCandidatesReportsNoAddr() {
        val sink = FrameSink()
        val c = controller(sink, emptyList()) { _, _, _ -> error("must not dial") }
        c.handleDial(dialPayload("pair", 5555))
        assertEquals(DialProto.ERROR_NO_ADDR, sink.lastResultError())
    }

    @Test
    fun redialClosesPreviousParkedSocketForPhase() {
        val fake = FakeAdbd()
        val sink = FrameSink()
        val c = controller(sink, listOf(Candidate.LOOPBACK)) { _, _, _ -> fake.connect() }
        c.handleDial(dialPayload("pair", fake.server.localPort))
        val first = fake.clients[0]
        assertFalse(first.isClosed)
        // Re-arm: a new DIAL for the same phase must close the parked socket, not stack them.
        c.handleDial(dialPayload("pair", fake.server.localPort))
        assertTrue(first.isClosed)
        assertEquals(2, fake.clients.size)
        assertTrue(c.attach(5))
        assertEquals(fake.clients[1], c.socketFor(5))
        c.closeChannel(5)
        fake.server.close()
    }

    @Test
    fun attachQueueIsFifoAcrossPhases() {
        val fake = FakeAdbd()
        val sink = FrameSink()
        val c = controller(sink, listOf(Candidate.LOOPBACK)) { _, _, _ -> fake.connect() }
        c.handleDial(dialPayload("pair", fake.server.localPort))
        c.handleDial(dialPayload("connect", fake.server.localPort))
        assertTrue(c.attach(1))
        assertTrue(c.attach(2))
        assertEquals("pair", c.phaseOf(1))
        assertEquals("connect", c.phaseOf(2))
        assertFalse(c.attach(3)) // nothing left parked
        c.closeAll()
        fake.server.close()
    }

    @Test
    fun closeByPhaseClosesItsChannelOnly() {
        val fake = FakeAdbd()
        val sink = FrameSink()
        val c = controller(sink, listOf(Candidate.LOOPBACK)) { _, _, _ -> fake.connect() }
        c.handleDial(dialPayload("pair", fake.server.localPort))
        c.handleDial(dialPayload("connect", fake.server.localPort))
        c.attach(1)
        c.attach(2)
        val sinkCountBefore = sink.frames.size
        c.closeByPhase("pair")
        assertTrue(fake.clients[0].isClosed)
        assertFalse(fake.clients[1].isClosed)
        assertNull(c.socketFor(1))
        // CLOSE frame went out for channel 1 only.
        val closes = sink.frames.drop(sinkCountBefore).filter { it.first == MuxCodec.TYPE_CLOSE }
        assertEquals(listOf(1), closes.map { it.second })
        c.closeAll()
        fake.server.close()
    }
}