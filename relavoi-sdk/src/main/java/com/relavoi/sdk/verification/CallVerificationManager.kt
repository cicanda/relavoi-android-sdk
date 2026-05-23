package com.relavoi.sdk.verification

import android.content.Context
import com.relavoi.sdk.session.VerificationResult

/**
 * Subsystem that detects whether the device is currently on a phone call and lets the
 * host app ask the backend whether that call originates from a Relavoi proxy number.
 *
 * The "Revolut-style" verification banner flow lives here.
 */
interface CallVerificationManager {

    /**
     * Verify whether the user's currently active call is backed by a Relavoi session.
     * Delegates to [com.relavoi.sdk.session.SessionManager.verify] but additionally
     * sanity-checks that the host has been granted the `READ_PHONE_STATE` runtime
     * permission — without it, [isCallActive] can never become true and verification
     * would be useless.
     */
    suspend fun verify(userPhone: String): VerificationResult

    /**
     * True if the device's [android.telephony.TelephonyManager] currently reports an
     * off-hook call state. Reflects the latest sample from [CallStateObserver].
     */
    fun isCallActive(): Boolean

    /** Helper for host apps to check the runtime permission without importing AndroidX. */
    fun hasPhoneStatePermission(context: Context): Boolean
}
