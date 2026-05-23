package com.relavoi.sdk.auth

import com.relavoi.sdk.RelavoiException
import com.relavoi.sdk.internal.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manages the tenant JWT used by all authenticated API calls. The flow:
 *
 * 1. On first call we POST `/auth/token` with the (apiKey, apiSecret) pair.
 * 2. The returned JWT is cached in-memory AND persisted via [TokenStore] for warm-start.
 * 3. Subsequent [getValidToken] calls return the cached JWT if it has > 60s of life left.
 * 4. On expiry — or when [invalidate] is called by the 401 retry path — we refresh once.
 *
 * Concurrent refreshes are serialized via [refreshMutex] so a burst of API calls causes
 * exactly one network round-trip.
 */
internal class AuthManager(
    private val apiKey: String,
    private val apiSecret: String,
    val tenantId: String,
    private val tokenStore: TokenStore,
    baseUrl: String,
    private val enableLogging: Boolean = false,
) {

    private val authBaseUrl: String = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedExpiresAt: Long = 0L

    private val refreshMutex = Mutex()

    // Bare HTTP client — we can't use the main ApiClient because it routes through
    // AuthInterceptor which would call us recursively.
    private val rawClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        if (enableLogging) {
            builder.addInterceptor { chain ->
                val req = chain.request()
                Logger.d("auth → ${req.method} ${req.url}")
                val resp = chain.proceed(req)
                Logger.d("auth ← ${resp.code} ${req.url}")
                resp
            }
        }
        builder.build()
    }

    init {
        // Warm-load any token persisted from a previous process.
        tokenStore.load()?.let { (tok, exp) ->
            cachedToken = tok
            cachedExpiresAt = exp
        }
    }

    /**
     * Return a JWT that is guaranteed to still be valid for at least 60s. Refreshes if
     * the cached token is missing or close to expiry.
     */
    suspend fun getValidToken(): String {
        val now = System.currentTimeMillis()
        val tok = cachedToken
        if (tok != null && cachedExpiresAt - now > REFRESH_BUFFER_MS) {
            return tok
        }
        return refreshToken()
    }

    /**
     * Force a refresh from the server. If multiple coroutines call this concurrently,
     * only the first does network I/O — the rest reuse its result.
     */
    suspend fun refreshToken(): String = refreshMutex.withLock {
        // Double-check inside the lock: another coroutine may have refreshed while we waited.
        val now = System.currentTimeMillis()
        val tok = cachedToken
        if (tok != null && cachedExpiresAt - now > REFRESH_BUFFER_MS) {
            return@withLock tok
        }

        val body = json.encodeToString(
            TokenRequest.serializer(),
            TokenRequest(apiKey = apiKey, apiSecret = apiSecret, tenantId = tenantId),
        )
        val request = Request.Builder()
            .url("$authBaseUrl/auth/token")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val (newToken, expiresInSec) = withContext(Dispatchers.IO) {
            rawClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        throw RelavoiException.Unauthorized("token endpoint rejected credentials (${resp.code})")
                    }
                    throw RelavoiException.ApiError(resp.code, raw)
                }
                val parsed = json.decodeFromString(TokenResponse.serializer(), raw ?: "{}")
                parsed.accessToken to parsed.expiresIn
            }
        }

        val expiresAt = System.currentTimeMillis() + expiresInSec * 1000L
        cachedToken = newToken
        cachedExpiresAt = expiresAt
        tokenStore.save(newToken, expiresAt)
        Logger.d("AuthManager refreshed JWT; expires in ${expiresInSec}s")
        return@withLock newToken
    }

    /** Drop the cached token. Next [getValidToken] call will refresh. */
    fun invalidate() {
        cachedToken = null
        cachedExpiresAt = 0L
        tokenStore.clear()
        Logger.d("AuthManager invalidated JWT cache")
    }

    @Serializable
    private data class TokenRequest(
        val apiKey: String,
        val apiSecret: String,
        val tenantId: String,
    )

    @Serializable
    private data class TokenResponse(
        val accessToken: String,
        val expiresIn: Long,
    )

    companion object {
        // Refresh slightly before actual expiry so calls never race the deadline.
        private const val REFRESH_BUFFER_MS = 60_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
