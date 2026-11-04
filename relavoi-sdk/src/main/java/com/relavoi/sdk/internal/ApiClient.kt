package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException
import com.relavoi.sdk.auth.AuthManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Synchronous HTTP client used by every authenticated API call in the SDK.
 *
 * Methods are deliberately NOT suspend — callers wrap them in
 * `withContext(Dispatchers.IO)` themselves. This keeps the threading policy explicit
 * at the call site (and makes testing trivial since we don't need a dispatcher rule).
 *
 * On non-2xx responses, methods translate the status code into a typed
 * [RelavoiException]:
 *
 *  | Status  | Problem type                 | Exception                                   |
 *  | ------- | ---------------------------- | ------------------------------------------- |
 *  | 400/422 | number-not-supported         | [RelavoiException.NumberNotSupported]       |
 *  | 400/422 | cross-border-not-supported   | [RelavoiException.CrossBorderNotSupported]  |
 *  | 400/422 | anything else                | [RelavoiException.Validation]               |
 *  | 401     |                              | [RelavoiException.Unauthorized]             |
 *  | any     | number-quota-reached         | [RelavoiException.NumberQuotaReached]       |
 *  | 429     |                              | [RelavoiException.RateLimited]              |
 *  | other   |                              | [RelavoiException.ApiError]                 |
 *
 * The quota refusal is matched on its slug at any status, deliberately. It was
 * answered with a 429 and is now a 409, and it must not be read as a rate
 * limit under either: a rate limit says wait, and this one does not clear by
 * waiting. Keying on the status would have mislabelled it for as long as the
 * two sides were out of step, which is the window every release creates.
 *
 * IO failures surface as [RelavoiException.Network].
 *
 * Error responses are RFC 7807 problem documents. Only `type`, `title` and
 * `detail` are read out of one; the raw body is never put into an exception
 * message. That is deliberate rather than tidy: an exception message ends up in
 * a crash reporter, so what goes into it is limited to the three fields whose
 * contents we control, instead of whatever a future endpoint happens to return.
 */
internal class ApiClient(
    private val baseUrl: String,
    authManager: AuthManager,
    enableLogging: Boolean = false,
) {

    private val baseTrimmed: String = baseUrl.trimEnd('/')

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(authManager))
        .apply {
            if (enableLogging) {
                addInterceptor { chain ->
                    val req = chain.request()
                    // Path only, never the query string. /sessions/verify carries the
                    // subscriber's number as a query parameter, so logging the full URL
                    // wrote a plaintext phone number into logcat for anyone who turned
                    // SDK logging on.
                    val safeUrl = req.url.encodedPath
                    Logger.d("→ ${req.method} $safeUrl")
                    val started = System.nanoTime()
                    val resp = chain.proceed(req)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    Logger.d("← ${resp.code} $safeUrl (${ms}ms)")
                    resp
                }
            }
        }
        .build()

    /** GET request returning the response body as a String. */
    fun get(path: String): String = execute(
        Request.Builder().url(fullUrl(path)).get().build()
    )

    /** POST request with a JSON body, returning the response body as a String. */
    fun post(path: String, body: String): String = execute(
        Request.Builder().url(fullUrl(path)).post(body.toRequestBody(JSON_MEDIA)).build()
    )

    /** DELETE request returning the response body as a String. */
    fun delete(path: String): String = execute(
        Request.Builder().url(fullUrl(path)).delete().build()
    )

    /** DELETE request carrying a JSON body (some endpoints read the body on DELETE). */
    fun delete(path: String, body: String): String = execute(
        Request.Builder().url(fullUrl(path)).delete(body.toRequestBody(JSON_MEDIA)).build()
    )

    /** PATCH request with a JSON body, returning the response body as a String. */
    fun patch(path: String, body: String): String = execute(
        Request.Builder().url(fullUrl(path)).patch(body.toRequestBody(JSON_MEDIA)).build()
    )

    /** Compose the full URL — accepts both `/sessions` and `sessions`. */
    private fun fullUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$baseTrimmed/${path.trimStart('/')}"
        }

    private fun execute(request: Request): String {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            throw RelavoiException.Network(io)
        }
        response.use { resp ->
            val raw = resp.body?.string()
            if (resp.isSuccessful) {
                return raw ?: ""
            }
            throw translate(resp.code, raw, resp.header("Retry-After"))
        }
    }

    private fun translate(code: Int, body: String?, retryAfter: String?): RelavoiException {
        val problem = parseProblem(body)
        val detail = problem?.detail

        // Before the status is looked at. See the table above.
        if (problem?.slug == "number-quota-reached") {
            return RelavoiException.NumberQuotaReached(
                detail ?: "This workspace is holding every proxy number its plan allows at once."
            )
        }

        return when (code) {
            401 -> RelavoiException.Unauthorized(detail ?: "no detail")
            400, 422 -> when (problem?.slug) {
                "number-not-supported" ->
                    RelavoiException.NumberNotSupported(detail ?: "That phone number is not supported.")
                "cross-border-not-supported" ->
                    RelavoiException.CrossBorderNotSupported(
                        detail ?: "Both numbers in a session must be from the same country."
                    )
                else -> RelavoiException.Validation(detail ?: "validation failed")
            }
            429 -> RelavoiException.RateLimited(retryAfter?.toLongOrNull())
            else -> RelavoiException.ApiError(code, detail)
        }
    }

    /**
     * Pull the last path segment of the problem `type` URL, plus `detail`.
     *
     * The slug is matched rather than the whole URL so the error identity does
     * not depend on the hostname, which differs between environments. A body
     * that is not a problem document gives null and the caller falls back to a
     * status-based mapping.
     */
    private fun parseProblem(body: String?): Problem? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = PROBLEM_JSON.parseToJsonElement(body).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNullSafe()
            val detail = obj["detail"]?.jsonPrimitive?.contentOrNullSafe()
            Problem(slug = type?.substringAfterLast('/'), detail = detail)
        } catch (_: Throwable) {
            null
        }
    }

    private data class Problem(val slug: String?, val detail: String?)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val PROBLEM_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
            try { content.ifBlank { null } } catch (_: Throwable) { null }
    }
}
