package com.thoitiettxl.eta.bridge

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

internal class BrowserPairingStore(
    context: Context,
) : BrowserPairingCredentials {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun token(): String? = synchronized(lock) {
        preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun pair(): String = synchronized(lock) {
        preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: replaceToken()
    }

    fun install(token: String): String = synchronized(lock) {
        val normalized = token.trim()
        require(normalized.length in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS) {
            "Paired browser credential has an invalid length"
        }
        persistToken(normalized)
    }

    override fun rotate(): String = synchronized(lock) {
        check(preferences.getString(KEY_TOKEN, null)?.isNotBlank() == true) {
            "Pair this device before rotating the browser credential"
        }
        replaceToken()
    }

    override fun revoke() = synchronized(lock) {
        check(preferences.edit().remove(KEY_TOKEN).commit()) {
            "Unable to revoke the paired browser credential"
        }
    }

    private fun replaceToken(): String {
        val token = randomToken()
        return persistToken(token)
    }

    private fun persistToken(token: String): String {
        check(preferences.edit().putString(KEY_TOKEN, token).commit()) {
            "Unable to persist the paired browser credential"
        }
        return token
    }

    private companion object {
        val lock = Any()
        const val PREFERENCES_NAME = "eta_browser_pairing"
        const val KEY_TOKEN = "paired_token_v1"
        const val MIN_TOKEN_CHARS = 32
        const val MAX_TOKEN_CHARS = 128

        fun randomToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        }
    }
}
