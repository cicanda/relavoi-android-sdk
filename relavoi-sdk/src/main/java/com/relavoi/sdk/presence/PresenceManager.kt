package com.relavoi.sdk.presence

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.internal.Logger
import com.relavoi.sdk.internal.PhoneUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reports the app's foreground/background state to the backend so the Push Service
 * knows whether to prefer in-app delivery vs. a system notification.
 *
 * Auto-registers with [ProcessLifecycleOwner] on first access. Until the host calls
 * [setUserPhone], all transitions are no-ops because we don't yet know which user
 * to associate them with.
 */
class PresenceManager private constructor() : DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var userPhone: String? = null

    init {
        // Register on the main thread — ProcessLifecycleOwner is thread-confined.
        try {
            // Posting to a Handler is overkill; ProcessLifecycleOwner.get() is thread-safe.
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        } catch (t: Throwable) {
            Logger.w("PresenceManager could not attach to ProcessLifecycleOwner: ${t.message}", t)
        }
    }

    /**
     * Identify which user this device belongs to. Until called, presence reporting is
     * disabled. Call again with a new value on user switch.
     */
    fun setUserPhone(phone: String) {
        PhoneUtils.requireValidE164(phone, "phone")
        userPhone = phone
    }

    override fun onStart(owner: LifecycleOwner) {
        updatePresenceAsync("online")
    }

    override fun onStop(owner: LifecycleOwner) {
        updatePresenceAsync("background")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Process death: best-effort. The launched coroutine may not complete.
        updatePresenceAsync("offline")
    }

    private fun updatePresenceAsync(status: String) {
        val phone = userPhone ?: return
        scope.launch {
            try {
                updatePresence(phone, status)
            } catch (t: Throwable) {
                Logger.w("Presence update '$status' failed: ${t.message}")
            }
        }
    }

    private suspend fun updatePresence(phone: String, status: String) {
        val body = json.encodeToString(
            PresenceRequest.serializer(),
            PresenceRequest(userPhone = phone, status = status, platform = "android"),
        )
        val api = Relavoi.requireApiClient()
        api.post("/devices/presence", body)
        Logger.d("Presence → $status for ${PhoneUtils.maskPhone(phone)}")
    }

    @Serializable
    private data class PresenceRequest(
        val userPhone: String,
        val status: String,
        val platform: String,
    )

    companion object {
        val instance: PresenceManager by lazy { PresenceManager() }
    }
}
