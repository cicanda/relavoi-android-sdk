package com.relavoi.sdk.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Listener interface for real-time events. Implementations are invoked on the
 * WebSocket dispatcher thread — keep them fast and post to the main thread for UI work.
 */
fun interface EventListener {
    fun onEvent(event: RelavoiEvent)
}

/**
 * All real-time events delivered through the WebSocket stream. The wire format is a
 * JSON envelope discriminated on `"type"`. Use [unknown] for forward-compat: new event
 * types coming from the backend deserialize to [RelavoiEvent.Unknown] instead of
 * throwing, so old SDK versions don't crash when the backend evolves.
 */
@Serializable
sealed class RelavoiEvent {
    abstract val sessionId: String?

    @Serializable
    @SerialName("session.created")
    data class SessionCreated(
        override val sessionId: String,
        val proxyNumber: String,
        val ts: String,
    ) : RelavoiEvent()

    @Serializable
    @SerialName("session.activated")
    data class SessionActivated(
        override val sessionId: String,
        val ts: String,
    ) : RelavoiEvent()

    @Serializable
    @SerialName("session.expired")
    data class SessionExpired(
        override val sessionId: String,
        val ts: String,
    ) : RelavoiEvent()

    @Serializable
    @SerialName("call.incoming")
    data class CallIncoming(
        override val sessionId: String,
        val callerNumber: String,
        val ts: String,
    ) : RelavoiEvent()

    @Serializable
    @SerialName("call.answered")
    data class CallAnswered(
        override val sessionId: String,
        val ts: String,
    ) : RelavoiEvent()

    @Serializable
    @SerialName("call.ended")
    data class CallEnded(
        override val sessionId: String,
        val durationSeconds: Int,
        val ts: String,
    ) : RelavoiEvent()

    @Serializable
    @SerialName("sms.received")
    data class SmsReceived(
        override val sessionId: String,
        val ts: String,
    ) : RelavoiEvent()

    /**
     * Fallback for unknown `type` discriminator values — keeps the SDK forward
     * compatible. The raw JSON payload is preserved so host apps can sniff at it.
     */
    @Serializable
    @SerialName("__unknown__")
    data class Unknown(
        override val sessionId: String? = null,
        val type: String,
        val rawPayload: String,
    ) : RelavoiEvent()
}
