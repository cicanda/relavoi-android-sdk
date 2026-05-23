package com.relavoi.sdk.verification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.internal.Logger
import com.relavoi.sdk.session.VerificationResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [CallVerificationManager] implementation. The observer is installed by
 * [initializeObserver] (called from [Relavoi.initialize]) so it starts listening
 * before the host has a chance to grant `READ_PHONE_STATE`. The observer self-recovers
 * if it's started without the permission — it just reports "always not in call".
 */
class CallVerificationManagerImpl private constructor() : CallVerificationManager {

    override suspend fun verify(userPhone: String): VerificationResult {
        if (!hasPhoneStatePermission(Relavoi.requireContext())) {
            Logger.w(
                "verify() called without READ_PHONE_STATE — verification will run but " +
                    "isCallActive() will always be false. Request the permission for full UX."
            )
        }
        return Relavoi.sessions.verify(userPhone)
    }

    override fun isCallActive(): Boolean = isOnCall.get()

    override fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

    /** Updated by [CallStateObserver] whenever the telephony state changes. */
    internal val isOnCall = AtomicBoolean(false)

    companion object {
        val instance: CallVerificationManagerImpl by lazy { CallVerificationManagerImpl() }

        private var observer: CallStateObserver? = null

        /**
         * Install the platform-specific telephony listener. Idempotent — safe to call
         * multiple times.
         */
        fun initializeObserver(context: Context) {
            synchronized(this) {
                if (observer != null) return
                observer = CallStateObserver(context.applicationContext) { active ->
                    instance.isOnCall.set(active)
                    Logger.d("Call state observer: isCallActive=$active")
                }.also { it.start() }
            }
        }
    }
}
