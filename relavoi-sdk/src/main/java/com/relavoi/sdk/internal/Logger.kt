package com.relavoi.sdk.internal

import android.util.Log
import com.relavoi.sdk.Relavoi

/**
 * Thin wrapper around [android.util.Log] gated by [com.relavoi.sdk.RelavoiConfig.enableLogging].
 * All log lines are prefixed with `Relavoi`.
 *
 * NEVER pass a plaintext phone number through this logger — use
 * [PhoneUtils.maskPhone] first.
 */
internal object Logger {

    private const val TAG = "Relavoi"

    private fun enabled(): Boolean = try {
        Relavoi.config.enableLogging
    } catch (_: Throwable) {
        false
    }

    fun d(message: String) {
        if (enabled()) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (enabled()) Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (enabled()) {
            if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        // Errors always log regardless of flag — they're rare and useful in crash reports.
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
