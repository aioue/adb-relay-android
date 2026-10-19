package org.androidgradletools.adbrelayandroid

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** OkHttp [toHttpUrlOrNull] does not accept ws/wss; validate via an https stand-in. */
object RelayUrl {
    /**
     * §3 bakes WSS to the origin root with no query string. `ws://` is a local-test
     * scheme for the UK bridge only; `usesCleartextTraffic=false` would block it at
     * connect time anyway, but a bad bake should fail here instead of wasting a
     * supervised rehearsal.
     */
    fun isValid(trimmed: String): Boolean {
        val t = trimmed.trim()
        if (!t.regionMatches(0, "wss://", 0, 6, ignoreCase = true)) return false
        if (t.indexOf('?') >= 0) return false
        return ("https://" + t.substring(6)).toHttpUrlOrNull() != null
    }
}