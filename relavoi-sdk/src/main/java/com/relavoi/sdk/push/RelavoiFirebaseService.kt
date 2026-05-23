package com.relavoi.sdk.push

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.internal.Logger
import com.relavoi.sdk.internal.PhoneUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM service that wires push token refresh into [PushTokenManager] and exposes a
 * hook ([onRelavoiNotification]) for host apps to render their own UI for Relavoi
 * push payloads.
 *
 * Host apps should either declare THIS service in their manifest OR subclass it and
 * declare the subclass (preferred so they can override [onMessageReceived] to call
 * `super.onMessageReceived(message)` for our logic plus their own).
 *
 * To opt in to automatic token-refresh registration, the host must call
 * [setUserForPushUpdates] after sign-in. Without it we don't know which userPhone to
 * register tokens against, so onNewToken just logs.
 */
open class RelavoiFirebaseService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val phone = loadUserPhone(applicationContext)
        if (phone == null) {
            Logger.d("FCM onNewToken fired but no userPhone is set; skipping registration")
            return
        }
        scope.launch {
            try {
                Relavoi.push.registerToken(phone, token)
            } catch (t: Throwable) {
                Logger.w("Failed to auto-register refreshed FCM token: ${t.message}", t)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            onRelavoiNotification(message)
        } catch (t: Throwable) {
            Logger.e("onRelavoiNotification handler threw", t)
        }
    }

    /**
     * Host-overridable hook. Default implementation logs the payload keys (never the
     * values, which may include phone-number fragments). Subclasses typically post a
     * system notification or update in-app UI here.
     */
    protected open fun onRelavoiNotification(message: RemoteMessage) {
        Logger.d("Received Relavoi notification: keys=${message.data.keys}")
    }

    companion object {
        private const val FILE_NAME = "relavoi_push_user"
        private const val KEY_PHONE = "user_phone"

        /**
         * Store [phone] in encrypted prefs so [onNewToken] knows which user to register
         * the refreshed token against. Call after sign-in.
         */
        @JvmStatic
        fun setUserForPushUpdates(context: Context, phone: String) {
            PhoneUtils.requireValidE164(phone, "phone")
            prefs(context).edit().putString(KEY_PHONE, phone).apply()
        }

        /** Clear the stored phone on logout. */
        @JvmStatic
        fun clearUserForPushUpdates(context: Context) {
            prefs(context).edit().remove(KEY_PHONE).apply()
        }

        private fun loadUserPhone(context: Context): String? =
            prefs(context).getString(KEY_PHONE, null)

        private fun prefs(context: Context) = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Logger.e("EncryptedSharedPreferences init failed for push user; falling back", t)
            context.applicationContext.getSharedPreferences(FILE_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }
}
