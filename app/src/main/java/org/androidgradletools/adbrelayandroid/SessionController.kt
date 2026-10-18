package org.androidgradletools.adbrelayandroid

import android.util.Log
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Owns the pair-then-connect state on the phone (server plan §4):
 *
 *  - `DIAL` (mux type 3) → dial adbd's listener locally (Wi-Fi link addrs, then loopback),
 *    park the TCP socket, answer `DIAL_RESULT` (mux type 4).
 *  - `OPEN` (type 1) does **not** dial — it attaches the oldest parked socket to `channelId`.
 *  - `DATA` / `CLOSE` (types 0/2) route by `channelId`.
 *
 * The pairing code never appears in any frame here; DIAL carries only `phase` and `port`.
 * All entry points are called from the service's single mux thread; parked-reader threads
 * only append to their own bounded buffer.
 */
class SessionController(
    private val selector: LocalTargetSelector,
    private val sendFrame: (type: Int, channelId: Int, payload: ByteArray) -> Unit,
    /** Injected so unit tests can fake TCP without a device. */
    private val connectFn: (host: String, port: Int, timeoutMs: Int) -> Socket = ::defaultConnect,
    private val perAttemptTimeoutMs: Int = PER_ATTEMPT_TIMEOUT_MS,
    private val budgetMs: Long = BUDGET_MS,
    private val parkedBufferCap: Int = PARKED_BUFFER_CAP,
) {

    private class Parked(val phase: String, val socket: Socket, val via: String) {
        /** Inbound bytes adbd sent before OPEN arrived (pairing usually has client speak first). */
        val inbound = ArrayDeque<ByteArray>()
        var inboundBytes = 0
    }

    private class LiveChannel(val phase: String, val socket: Socket)

    private val lock = Any()
    private val parked = HashMap<String, Parked>()
    /** FIFO of phases with a parked socket awaiting OPEN (bridge serialises OPEN right after DIAL_RESULT). */
    private val attachQueue = ArrayDeque<String>()
    private val channels = HashMap<Int, LiveChannel>()

    /** Handle a DIAL frame (payload = DIAL JSON). */
    fun handleDial(payload: ByteArray) {
        val parsed = DialProto.parseDial(payload)
        val dial = (parsed as? DialProto.DialParse.Ok)?.dial
        if (dial == null) {
            val bad = parsed as DialProto.DialParse.Bad
            Log.w(LOG_TAG, "dial phase=${bad.phase} ok=false error=${bad.error}")
            sendFrame(MuxCodec.TYPE_DIAL_RESULT, 0, DialProto.buildDialResult(bad.phase, false, null, bad.error))
            return
        }
        val phase = dial.phase

        // A new DIAL for a phase closes that phase's parked socket and open channel first.
        closeByPhase(phase)

        val start = System.currentTimeMillis()
        var lastError: String? = null
        val candidates = selector.candidates()
        if (candidates.isEmpty()) {
            Log.w(LOG_TAG, "dial phase=$phase ok=false error=no_addr")
            sendFrame(MuxCodec.TYPE_DIAL_RESULT, 0, DialProto.buildDialResult(phase, false, null, DialProto.ERROR_NO_ADDR))
            return
        }
        for (candidate in candidates) {
            val remaining = budgetMs - (System.currentTimeMillis() - start)
            if (remaining <= 0) {
                lastError = lastError ?: DialProto.ERROR_TIMEOUT
                break
            }
            val timeoutMs = minOf(perAttemptTimeoutMs, remaining.toInt())
            val sock = try {
                connectFn(candidate.host, dial.port, timeoutMs)
            } catch (e: ConnectException) {
                lastError = DialProto.ERROR_REFUSED
                continue
            } catch (e: SocketTimeoutException) {
                lastError = DialProto.ERROR_TIMEOUT
                continue
            } catch (e: Exception) {
                lastError = DialProto.ERROR_REFUSED
                continue
            }
            synchronized(lock) {
                parked[phase] = Parked(phase, sock, candidate.via)
                attachQueue.addLast(phase)
            }
            startParkedReader(sock)
            Log.i(LOG_TAG, "dial phase=$phase ok=true via=${candidate.via}")
            sendFrame(MuxCodec.TYPE_DIAL_RESULT, 0, DialProto.buildDialResult(phase, true, candidate.via, null))
            return
        }
        Log.w(LOG_TAG, "dial phase=$phase ok=false error=$lastError")
        sendFrame(MuxCodec.TYPE_DIAL_RESULT, 0, DialProto.buildDialResult(phase, false, null, lastError ?: DialProto.ERROR_NO_ADDR))
    }

    /**
     * Attach the oldest parked socket to `channelId`. Returns false when nothing is parked
     * (unsolicited OPEN — the caller then sends CLOSE and, as no DIAL_RESULT went out, `no_addr`).
     */
    fun attach(channelId: Int): Boolean {
        val parkedPhase = synchronized(lock) { attachQueue.removeFirstOrNull() } ?: return false
        val p = synchronized(lock) { parked.remove(parkedPhase) } ?: return false
        synchronized(lock) { channels[channelId] = LiveChannel(parkedPhase, p.socket) }
        try {
            synchronized(p.socket) {
                val out = p.socket.getOutputStream()
                while (true) {
                    val chunk = synchronized(lock) { p.inbound.pollFirst() } ?: break
                    out.write(chunk)
                }
                out.flush()
            }
        } catch (_: Exception) {
            closeChannel(channelId)
            return false
        }
        return true
    }

    /** Socket to write DATA to, or null when the channel is unknown (pre-OPEN DATA is dropped). */
    fun socketFor(channelId: Int): Socket? = synchronized(lock) { channels[channelId]?.socket }

    /** Phase a live channel belongs to, for logs/UI; null when unknown. */
    fun phaseOf(channelId: Int): String? = synchronized(lock) { channels[channelId]?.phase }

    fun closeChannel(channelId: Int) {
        val live = synchronized(lock) { channels.remove(channelId) } ?: return
        try {
            live.socket.close()
        } catch (_: Exception) {
        }
    }

    /** Close any parked socket and open channel for `phase` (re-DIAL or teardown). */
    fun closeByPhase(phase: String) {
        synchronized(lock) {
            attachQueue.removeAll { it == phase }
            parked.remove(phase)?.let { p ->
                try {
                    p.socket.close()
                } catch (_: Exception) {
                }
            }
        }
        for ((id, live) in synchronized(lock) { channels.entries.toList() }) {
            if (live.phase == phase) {
                closeChannel(id)
                sendFrame(MuxCodec.TYPE_CLOSE, id)
            }
        }
    }

    /** WSS death / service stop: everything goes. */
    fun closeAll() {
        val ids = synchronized(lock) { channels.keys.toList() }
        for (id in ids) {
            closeChannel(id)
        }
        synchronized(lock) {
            attachQueue.clear()
            for (p in parked.values) {
                try {
                    p.socket.close()
                } catch (_: Exception) {
                }
            }
            parked.clear()
        }
    }

    /**
     * Reader for a parked socket: accumulates adbd's early bytes (cap 64 KiB, then stop reading
     * and rely on attach-time flush), so an OPEN arriving late loses nothing.
     */
    private fun startParkedReader(socket: Socket) {
        Thread {
            val buf = ByteArray(16 * 1024)
            try {
                val input = socket.getInputStream()
                outer@ while (!socket.isClosed) {
                    var available = synchronized(lock) { parkedBufferCap - currentParkedBytes() }
                    if (available <= 0) break
                    val n = input.read(buf, 0, minOf(buf.size, available))
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(lock) {
                        val p = parked.values.firstOrNull { it.socket === socket } ?: break@outer
                        p.inbound.addLast(buf.copyOf(n))
                        p.inboundBytes += n
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            name = "voicepipe-parked-reader"
            start()
        }
    }

    private fun currentParkedBytes(): Int = parked.values.sumOf { it.inboundBytes }

    companion object {
        private const val LOG_TAG = "VoicePipe"

        /** Server plan §4 dial timings: 1.5 s per attempt inside a 5 s overall budget. */
        const val PER_ATTEMPT_TIMEOUT_MS = 1_500
        const val BUDGET_MS = 5_000L
        const val PARKED_BUFFER_CAP = 64 * 1024

        fun defaultConnect(host: String, port: Int, timeoutMs: Int): Socket =
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), timeoutMs)
            }
    }
}