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
     * The server returned a non-2xx HTTP status that has no more specific type.
     *
     * [statusCode] is the raw HTTP code. [body] is the `detail` of the RFC 7807
     * problem document, or null when the response was not one. It is not the raw
     * response body: an error page from in front of the API commonly echoes the
     * request URI, and one of ours carries a phone number in its query string.
     */
    class ApiError(val statusCode: Int, val body: String?) :
        RelavoiException("API error $statusCode. ${body ?: "No further detail."}")

    /** Client-side validation failed (e.g. a field that is not number-shaped). */
    class Validation(detail: String) : RelavoiException("Validation: $detail")

    /**
     * The API refused a phone number because Relavoi does not currently connect
     * numbers of that kind. Today that means anything other than a Nigerian
     * mobile number.
     *
     * This is a server decision, not a client one: the supported countries live
     * in the platform's countries table and can change without a new SDK
     * release, so do not try to pre-empt it with a local check. Show [message]
     * to the user, or your own wording for it.
     *
     * [message] never contains the number that was refused.
     */
    class NumberNotSupported(detail: String) : RelavoiException(detail)

    /**
     * The API refused the session because the two numbers are not in the same
     * country. Both parties must be in one country, and cross-border masking is
     * a separate product that is not enabled.
     *
     * [message] never contains either number.
     */
    class CrossBorderNotSupported(detail: String) : RelavoiException(detail)

    /**
     * The workspace is holding every proxy number its plan allows it to hold at
     * once, and each of them is already in a live session with one of the two
     * people in this request.
     *
     * **Do not retry this on a timer.** It is not a rate limit, which is why it
     * is a type of its own rather than [RateLimited]: no interval makes it
     * clear. It clears when one of the workspace's sessions ends, or when a
     * request for more numbers is approved, which is a change of state. The API
     * sends no Retry-After with it.
     *
     * A proxy number carries many sessions at once but never the same person
     * twice, so this is the point at which another number is genuinely needed.
     * If an app hits this often, the workspace should ask for a higher limit
     * from the Numbers page in its dashboard.
     *
     * [message] never contains either number.
     */
    class NumberQuotaReached(detail: String) : RelavoiException(detail)

    /**
     * The server returned 429 Too Many Requests. [retryAfterSec] is parsed from the
     * Retry-After header when present.
     */
    class RateLimited(val retryAfterSec: Long?) :
        RelavoiException("Rate limited; retry after ${retryAfterSec ?: "?"}s")
}
