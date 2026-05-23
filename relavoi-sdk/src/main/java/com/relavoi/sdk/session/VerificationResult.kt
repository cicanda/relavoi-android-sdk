package com.relavoi.sdk.session

import kotlinx.serialization.Serializable

/**
 * Result of `GET /v1/sessions/verify`. Returned by both [SessionManager.verify] and
 * [com.relavoi.sdk.verification.CallVerificationManager.verify].
 *
 * @property verified True if the currently-active call originates from a Relavoi-masked
 *  proxy number known to belong to this tenant.
 * @property context Optional human-readable banner string (e.g. `"Your Chowdeck rider
 *  is calling"`). Null for unverified results.
 * @property sessionId The session id that backs the verified call, when applicable.
 * @property expiresAt ISO-8601 timestamp at which the verification result becomes stale.
 */
@Serializable
data class VerificationResult(
    val verified: Boolean,
    val context: String? = null,
    val sessionId: String? = null,
    val expiresAt: String? = null,
)
