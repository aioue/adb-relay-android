package org.androidgradletools.adbrelayandroid

/**
 * Family Help bakes the relay URL and token into the APK (BuildConfig) — mum never pastes
 * either. No EncryptedSharedPreferences needed: there are no user-editable connection fields,
 * and the token ships inside the signed APK regardless.
 */
object Prefs {

    fun load(): Config = Config(
        relayUrl = BuildConfig.RELAY_URL,
        token = BuildConfig.RELAY_TOKEN,
    )

    data class Config(
        val relayUrl: String,
        val token: String,
    )
}