package com.relavoi.sdk

/**
 * Base type for all errors thrown by the Relavoi SDK. All public suspend functions
 * may throw subclasses of this; host apps should catch [RelavoiException] for a
 * single error boundary or pattern-match on the sealed subclasses for granular
 * handling.
 */
sealed class RelavoiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Thrown when an SDK subsystem is accessed before [Relavoi.initialize] has run. */
    class NotInitialized : RelavoiException(
        "Relavoi SDK is not initialized. Call Relavoi.initialize(...) at app startup."
    )

    /**
     * Authentication failed. Either the API key/secret is wrong or the JWT was rejected
     * even after a refresh attempt.
     */
    class Unauthorized(detail: String) : RelavoiException("Unauthorized: $detail")

    /** Underlying transport error (IOException, timeout, DNS, etc.). */
    class Network(cause: Throwable) : RelavoiException("Network error: ${cause.message}", cause)

    /**
     * The server returned a non-2xx HTTP status. [statusCode] is the raw HTTP code and
     * [body] is the raw response body (may be JSON Problem Details per RFC 7807).
     */
    class ApiError(val statusCode: Int, val body: String?) :
        RelavoiException("API error: $statusCode — ${body ?: "<no body>"}")

    /** Client-side validation failed (e.g. malformed phone number). */
    class Validation(detail: String) : RelavoiException("Validation: $detail")

    /**
     * The server returned 429 Too Many Requests. [retryAfterSec] is parsed from the
     * Retry-After header when present.
     */
    class RateLimited(val retryAfterSec: Long?) :
        RelavoiException("Rate limited; retry after ${retryAfterSec ?: "?"}s")
}
