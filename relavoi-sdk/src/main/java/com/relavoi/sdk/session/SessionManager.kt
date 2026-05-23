package com.relavoi.sdk.session

import android.content.Context

/**
 * Public API for the session lifecycle subsystem.
 *
 * All suspend methods may throw subclasses of [com.relavoi.sdk.RelavoiException]:
 * [com.relavoi.sdk.RelavoiException.Validation] for bad input,
 * [com.relavoi.sdk.RelavoiException.Unauthorized] for auth failures,
 * [com.relavoi.sdk.RelavoiException.Network] for transport errors,
 * [com.relavoi.sdk.RelavoiException.ApiError] for server errors.
 */
interface SessionManager {

    /**
     * Create a new masking session.
     *
     * @param agentPhone E.164 phone number of party A (typically the agent/driver).
     * @param customerPhone E.164 phone number of party B (typically the end customer).
     * @param metadata Optional opaque key/value pairs the server stores alongside the
     *  session (e.g. `{"orderId": "ORD-1234"}`).
     * @param gracePeriodMinutes Minutes after [end] is called during which the proxy
     *  still routes calls. Default 15.
     * @param directionMode Which direction(s) the proxy permits. Default bidirectional.
     * @param recordingEnabled Whether call recording is active for this session.
     * @param consentPrompt How recording consent is announced. If [recordingEnabled] is
     *  true, this MUST NOT be `NONE` (the server enforces this).
     * @return The created [Session] in `PENDING` state.
     */
    suspend fun create(
        agentPhone: String,
        customerPhone: String,
        metadata: Map<String, String>? = null,
        gracePeriodMinutes: Int = 15,
        directionMode: DirectionMode = DirectionMode.BIDIRECTIONAL,
        recordingEnabled: Boolean = false,
        consentPrompt: ConsentPrompt = ConsentPrompt.NONE,
    ): Session

    /** Fetch the current state of a session by id. Hits the local cache first. */
    suspend fun get(id: String): Session

    /**
     * End a session. The proxy enters its grace period and accepts callbacks for
     * `grace_period_min` minutes before fully expiring.
     */
    suspend fun end(id: String): Session

    /**
     * List sessions for the current tenant.
     *
     * @param state Optional filter by lifecycle state.
     * @param limit Page size (default 20, server caps).
     * @param after Cursor from a previous `pagination.after` for the next page.
     */
    suspend fun list(
        state: SessionState? = null,
        limit: Int = 20,
        after: String? = null,
    ): SessionListResponse

    /**
     * Verify whether the user's currently active call is backed by a Relavoi session.
     * Used by the verification banner. See [VerificationResult].
     */
    suspend fun verify(userPhone: String): VerificationResult

    /**
     * Launch the platform dialer for the session's proxy number. The session must have
     * been previously fetched (or created) so its `proxyNumber` is in the local cache.
     *
     * The user still has to tap the green call button — this is `ACTION_DIAL`, not
     * `ACTION_CALL`. We deliberately do NOT require `CALL_PHONE`.
     */
    fun initiateCall(sessionId: String, context: Context)
}
