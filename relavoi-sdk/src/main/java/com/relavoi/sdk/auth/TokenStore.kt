package com.relavoi.sdk.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.relavoi.sdk.internal.Logger

/**
 * Persistent JWT cache backed by [EncryptedSharedPreferences]. Used so that the SDK can
 * warm-start with a still-valid token after a process restart without immediately
 * hitting `/auth/token`.
 *
 * Storage file: `relavoi_auth_store` (AES256_GCM values, AES256_SIV keys).
 *
 * Marked `open` so unit tests can substitute an in-memory implementation without
 * touching Android's Keystore.
 */
internal open class TokenStore(context: Context?) {

    // Visible-for-testing: tests use the no-arg constructor and override the prefs-backed
    // methods.
    protected constructor() : this(null)

    private val prefs: SharedPreferences? = createPrefs(context)

    private fun createPrefs(context: Context?): SharedPreferences? {
        if (context == null) return null
        val appCtx = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            // Fall back to plain prefs if Keystore init fails (e.g. on broken OEM keystores).
            // We log loudly because this is a real security regression — host devs should know.
            Logger.e("EncryptedSharedPreferences init failed; falling back to plain prefs.", t)
            appCtx.getSharedPreferences(FILE_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    /** Persist a token and its absolute expiry (epoch millis). */
    open fun save(token: String, expiresAtEpochMs: Long) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
            .apply()
    }

    /** Returns the cached `(token, expiresAtEpochMs)` or null if nothing is saved. */
    open fun load(): Pair<String, Long>? {
        val p = prefs ?: return null
        val token = p.getString(KEY_TOKEN, null) ?: return null
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        if (token.isBlank() || expiresAt <= 0L) return null
        return token to expiresAt
    }

    /** Erase the cached token (e.g. after a hard logout). */
    open fun clear() {
        val p = prefs ?: return
        p.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES_AT).apply()
    }

    companion object {
        private const val FILE_NAME = "relavoi_auth_store"
        private const val KEY_TOKEN = "jwt"
        private const val KEY_EXPIRES_AT = "jwt_expires_at"
    }
}
