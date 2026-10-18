package org.androidgradletools.adbrelayandroid

import org.json.JSONObject

/**
 * DIAL / DIAL_RESULT mux payloads (server plan §4). Pure logic so it is unit-testable without
 * an Android device: the bridge sends DIAL, the phone answers DIAL_RESULT, and the six-digit
 * pairing code never appears in either frame — only `phase` and `port`.
 */
object DialProto {

    const val PHASE_PAIR = "pair"
    const val PHASE_CONNECT = "connect"

    const val ERROR_REFUSED = "refused"
    const val ERROR_TIMEOUT = "timeout"
    const val ERROR_BAD_PORT = "bad_port"
    const val ERROR_NO_ADDR = "no_addr"

    /** Server plan §4: DIAL JSON is UTF-8, no longer than 256 bytes. */
    const val MAX_PAYLOAD_BYTES = 256

    data class Dial(val phase: String, val port: Int)

    sealed class DialParse {
        data class Ok(val dial: Dial) : DialParse()
        /** [phase] is the raw (possibly invalid/absent) value, echoed in DIAL_RESULT. */
        data class Bad(val phase: String, val error: String) : DialParse()
    }

    /**
     * Parses a DIAL payload. Unknown JSON keys are ignored; missing op/phase/port or an
     * out-of-range port maps to [ERROR_BAD_PORT] (never a retry loop).
     */
    fun parseDial(payload: ByteArray): DialParse {
        if (payload.size > MAX_PAYLOAD_BYTES) return DialParse.Bad("", ERROR_BAD_PORT)
        val json = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return DialParse.Bad("", ERROR_BAD_PORT)
        }
        val rawPhase = json.optString("phase")
        if (json.optString("op") != "dial") return DialParse.Bad(rawPhase, ERROR_BAD_PORT)
        val phase = json.optString("phase")
        if (phase != PHASE_PAIR && phase != PHASE_CONNECT) return DialParse.Bad(rawPhase, ERROR_BAD_PORT)
        // Strict int check: org.json's optInt would coerce a numeric string; the bridge never sends one.
        val port = json.opt("port")
        if (port !is Int || port !in 1..65535) return DialParse.Bad(rawPhase, ERROR_BAD_PORT)
        return DialParse.Ok(Dial(phase, port))
    }

    /** `{"op":"dial","phase":"…","ok":true,"via":"link"}` / `…,"ok":false,"error":"refused"}`. */
    fun buildDialResult(phase: String, ok: Boolean, via: String?, error: String?): ByteArray {
        val json = JSONObject()
            .put("op", "dial")
            .put("phase", phase)
            .put("ok", ok)
        if (ok) {
            json.put("via", via ?: "")
        } else {
            json.put("error", error ?: ERROR_NO_ADDR)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
}