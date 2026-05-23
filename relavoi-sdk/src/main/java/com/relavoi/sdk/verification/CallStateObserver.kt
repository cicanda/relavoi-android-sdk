package com.relavoi.sdk.verification

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.relavoi.sdk.internal.Logger
import java.util.concurrent.Executors

/**
 * Watches [TelephonyManager] call-state changes and pushes a boolean to [onChange]:
 * `true` when the device is off-hook, `false` otherwise.
 *
 * Uses the modern [TelephonyCallback] API on Android 12+ and falls back to
 * [PhoneStateListener] on older devices. Both paths require `READ_PHONE_STATE`; if the
 * grant is missing we log a warning and stay silent (callers see `isCallActive=false`).
 */
internal class CallStateObserver(
    private val context: Context,
    private val onChange: (Boolean) -> Unit,
) {

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @RequiresApi(Build.VERSION_CODES.S)
    private var modernCallback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val tm = telephony ?: run {
            Logger.w("TelephonyManager unavailable — call state observer disabled.")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModern(tm)
            } else {
                startLegacy(tm)
            }
        } catch (se: SecurityException) {
            // READ_PHONE_STATE not granted. Degrade silently — isCallActive stays false.
            Logger.w("READ_PHONE_STATE missing — call state observer degraded.", se)
        } catch (t: Throwable) {
            Logger.w("Call state observer failed to start: ${t.message}", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startModern(tm: TelephonyManager) {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "relavoi-call-state").apply { isDaemon = true }
        }
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                onChange(state == TelephonyManager.CALL_STATE_OFFHOOK)
            }
        }
        tm.registerTelephonyCallback(executor, cb)
        modernCallback = cb
    }

    @Suppress("DEPRECATION")
    private fun startLegacy(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                // Deliberately ignore phoneNumber even if delivered — we never log it.
                onChange(state == TelephonyManager.CALL_STATE_OFFHOOK)
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = listener
    }

    @Suppress("DEPRECATION")
    fun stop() {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let { tm.unregisterTelephonyCallback(it) }
                modernCallback = null
            } else {
                legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                legacyListener = null
            }
        } catch (t: Throwable) {
            Logger.w("Failed to stop call state observer: ${t.message}", t)
        }
    }
}
