package com.relavoi.sdk.auth

/**
 * A token your own backend minted, handed to the SDK.
 *
 * The SDK caches it until shortly before [expiresInSeconds] elapses and then
 * asks for another, so a provider that is cheap to call stays cheap.
 */
public data class RelavoiToken(
    /** The `accessToken` from `POST /v1/auth/sdk-token`. */
    val accessToken: String,
    /** Seconds from now, as returned by that endpoint. */
    val expiresInSeconds: Int,
)

/**
 * How the SDK asks your app for a token.
 *
 * Your app calls your backend, which holds the API secret and calls
 * `POST /v1/auth/sdk-token`. The secret never reaches the handset, which is
 * the point: anything compiled into an APK can be read back out of it with
 * `unzip` and `strings`.
 *
 * ```kotlin
 * Relavoi.initialize(
 *     context = this,
 *     tenantId = "your-tenant-uuid",
 *     tokenProvider = { myBackend.relavoiToken() },
 * )
 * ```
 *
 * Called on first use, shortly before the current token expires, and once
 * after a 401. A throw surfaces to the caller rather than being retried in a
 * loop: a failing provider usually means the user is signed out of your app,
 * and the right answer is to stop rather than to hammer.
 *
 * It is a suspending function, so doing the network call inside it is fine.
 */
public fun interface RelavoiTokenProvider {
    public suspend fun fetchToken(): RelavoiToken
}
