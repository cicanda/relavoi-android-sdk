package com.relavoi.sdk.push

import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.internal.Logger
import com.relavoi.sdk.internal.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers and deactivates FCM device tokens with the Relavoi backend so the Push
 * Notification Service can deliver branded "incoming call" notifications to the right
 * device.
 *
 * Per-user dedup: we cache the last-registered `(userPhone -> token)` pair in memory and
 * skip the network round-trip if FCM hands us the same token for the same user. FCM
 * already tries hard to keep `getToken()` stable across app launches, so this saves a
 * lot of unnecessary writes.
 */
class PushTokenManager private constructor() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Keyed by userPhone — but we never log the key itself. */
    private val lastRegistered = ConcurrentHashMap<String, String>()

    /**
     * Register an FCM token for [userPhone]. No-ops if the same token was already
     * registered for the same user.
     *
     * @param userPhone E.164 phone number identifying the user this device belongs to.
     * @param fcmToken Token returned by `FirebaseMessaging.getInstance().token`.
     * @param platform Defaults to `"android"` — left as a parameter for forward-compat
     *  in case we ship a unified iOS-like API later.
     * @param appBundleId Optional package id of the host app, useful for tenant
     *  multi-app deployments.
     */
    @JvmOverloads
    suspend fun registerToken(
        userPhone: String,
        fcmToken: String,
        platform: String = "android",
        appBundleId: String? = null,
    ) {
        PhoneUtils.requireValidE164(userPhone, "userPhone")
        require(fcmToken.isNotBlank()) { "fcmToken must not be blank" }

        if (lastRegistered[userPhone] == fcmToken) {
            Logger.d("Skipping push token re-registration for ${PhoneUtils.maskPhone(userPhone)}")
            return
        }

        val body = json.encodeToString(
            RegisterTokenRequest.serializer(),
            RegisterTokenRequest(
                userPhone = userPhone,
                token = fcmToken,
                platform = platform,
                appBundleId = appBundleId,
            ),
        )
        val api = Relavoi.requireApiClient()
        withContext(Dispatchers.IO) { api.post("/devices/token", body) }
        lastRegistered[userPhone] = fcmToken
        Logger.d("Registered FCM token for ${PhoneUtils.maskPhone(userPhone)}")
    }

    /** Deactivate a token — call on logout. */
    suspend fun deactivateToken(fcmToken: String) {
        require(fcmToken.isNotBlank()) { "fcmToken must not be blank" }
        val api = Relavoi.requireApiClient()
        // Backend reads the token from the DELETE request body: { "token": "..." }.
        val body = json.encodeToString(
            DeactivateTokenRequest.serializer(),
            DeactivateTokenRequest(token = fcmToken),
        )
        withContext(Dispatchers.IO) { api.delete("/devices/token", body) }
        // Drop any cache entries that pointed at this token.
        lastRegistered.entries.removeAll { it.value == fcmToken }
        Logger.d("Deactivated FCM token")
    }

    @Serializable
    private data class RegisterTokenRequest(
        val userPhone: String,
        val token: String,
        val platform: String,
        val appBundleId: String? = null,
    )

    @Serializable
    private data class DeactivateTokenRequest(
        val token: String,
    )

    companion object {
        val instance: PushTokenManager by lazy { PushTokenManager() }
    }
}
