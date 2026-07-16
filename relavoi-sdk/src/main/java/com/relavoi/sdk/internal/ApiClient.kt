package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException
import com.relavoi.sdk.auth.AuthManager
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
 *  | Status  | Exception                          |
 *  | ------- | ---------------------------------- |
 *  | 400/422 | [RelavoiException.Validation]      |
 *  | 401     | [RelavoiException.Unauthorized]    |
 *  | 429     | [RelavoiException.RateLimited]     |
 *  | other   | [RelavoiException.ApiError]        |
 *
 * IO failures surface as [RelavoiException.Network].
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
                    Logger.d("→ ${req.method} ${req.url}")
                    val started = System.nanoTime()
                    val resp = chain.proceed(req)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    Logger.d("← ${resp.code} ${req.url} (${ms}ms)")
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

    private fun translate(code: Int, body: String?, retryAfter: String?): RelavoiException = when (code) {
        401 -> RelavoiException.Unauthorized(body ?: "no detail")
        400, 422 -> RelavoiException.Validation(body ?: "validation failed")
        429 -> RelavoiException.RateLimited(retryAfter?.toLongOrNull())
        else -> RelavoiException.ApiError(code, body)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
