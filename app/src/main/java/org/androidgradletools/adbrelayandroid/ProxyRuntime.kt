package org.androidgradletools.adbrelayandroid

object ProxyRuntime {
    @Volatile
    var connected: Boolean = false

    @Volatile
    var lastError: String? = null

    /** WebSocket handshake / tunnel not ready yet. */
    @Volatile
    var connecting: Boolean = false

    /** Stop requested; service tearing down. */
    @Volatile
    var disconnecting: Boolean = false

    /** Last DIAL phase seen (pair / connect); drives the one-line status under the checklist. */
    @Volatile
    var phase: String = PHASE_IDLE

    const val PHASE_IDLE = "idle"
    const val PHASE_PAIR = "pair"
    const val PHASE_CONNECT = "connect"
}