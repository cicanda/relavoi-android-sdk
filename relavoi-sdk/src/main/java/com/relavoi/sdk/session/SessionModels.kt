package com.relavoi.sdk.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Lifecycle states of a masking session, exactly matching the wire format. */
@Serializable
enum class SessionState {
    @SerialName("PENDING") PENDING,
    @SerialName("ACTIVE") ACTIVE,
    @SerialName("GRACE_PERIOD") GRACE_PERIOD,
    @SerialName("EXPIRED") EXPIRED,
    @SerialName("FAILED") FAILED,
}

/** Which direction(s) the proxy permits calls/SMS to flow. */
@Serializable
enum class DirectionMode {
    @SerialName("BIDIRECTIONAL") BIDIRECTIONAL,
    @SerialName("A_TO_B_ONLY") A_TO_B_ONLY,
    @SerialName("B_TO_A_ONLY") B_TO_A_ONLY,
}

/** Recording consent prompt mode. Per NDPR, NONE is only allowed when recording is off. */
@Serializable
enum class ConsentPrompt {
    @SerialName("DEFAULT") DEFAULT,
    @SerialName("CUSTOM") CUSTOM,
    @SerialName("NONE") NONE,
}

/**
 * A masking session as returned by the server. Field names use camelCase on the wire
 * (the API contract); the Kotlin properties match.
 *
 * Phone numbers for the two parties are NEVER returned — only the [proxyNumber] is
 * visible to the SDK, by design.
 */
@Serializable
data class Session(
    val id: String,
    val tenantId: String,
    val proxyNumber: String,
    val state: SessionState,
    val directionMode: DirectionMode,
    // Client-attached context. The server allows arbitrary JSON values (nested
    // objects, numbers, arrays), so values are JsonElement rather than String.
    val metadata: Map<String, JsonElement>? = null,
    val gracePeriodMinutes: Int,
    val maxDurationMinutes: Int,
    val recordingEnabled: Boolean,
    val consentPrompt: ConsentPrompt,
    val expiresAt: String,
    val createdAt: String,
    val activatedAt: String? = null,
    val endedAt: String? = null,
    val expiredAt: String? = null,
    val callCount: Int? = null,
    val lastCallAt: String? = null,
)

/** Request body for `POST /sessions`. */
@Serializable
internal data class CreateSessionRequest(
    val agentPhone: String,
    val customerPhone: String,
    val metadata: Map<String, String>? = null,
    val gracePeriodMinutes: Int = 15,
    val directionMode: String = "BIDIRECTIONAL",
    val recordingEnabled: Boolean = false,
    val consentPrompt: String = "NONE",
)

/** Request body for `PATCH /sessions/{id}/target`. */
@Serializable
internal data class SwapTargetRequest(
    val customerPhone: String,
)

/** Paginated `GET /sessions` response. */
@Serializable
data class SessionListResponse(
    val data: List<Session>,
    val pagination: PaginationInfo,
)

/** Cursor-based pagination metadata. */
@Serializable
data class PaginationInfo(
    val count: Int,
    val after: String? = null,
)
