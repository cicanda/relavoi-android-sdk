package com.relavoi.sdk.internal

import com.relavoi.sdk.auth.AuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches `Authorization: Bearer <jwt>` to every outbound
 * request EXCEPT the `/auth/token` endpoint (to avoid the obvious circular dependency).
 *
 * On a 401 response, it invalidates the cached token, fetches a fresh one, and retries
 * the request exactly once. If the retry also returns 401, the original 401 is propagated
 * up to the caller (which translates it to [com.relavoi.sdk.RelavoiException.Unauthorized]).
 */
internal class AuthInterceptor(private val authManager: AuthManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Don't attempt to authenticate the auth endpoint itself.
        if (original.url.encodedPath.endsWith("/auth/token")) {
            return chain.proceed(original)
        }

        val firstToken = runBlocking { authManager.getValidToken() }
        val firstRequest = original.newBuilder()
            .header("Authorization", "Bearer $firstToken")
            .build()
        val firstResponse = chain.proceed(firstRequest)

        if (firstResponse.code != 401) {
            return firstResponse
        }

        // 401 path: invalidate, refresh, retry once. Body must be closed before we
        // dispatch the retry to avoid a leaked connection.
        firstResponse.close()
        Logger.w("Got 401 — invalidating token and retrying once.")
        authManager.invalidate()
        val freshToken = try {
            runBlocking { authManager.refreshToken() }
        } catch (t: Throwable) {
            Logger.w("Token refresh failed during 401 retry: ${t.message}")
            // Re-run the original (without auth) so the caller gets a real 401 to translate.
            return chain.proceed(firstRequest)
        }
        val retryRequest = original.newBuilder()
            .header("Authorization", "Bearer $freshToken")
            .build()
        return chain.proceed(retryRequest)
    }
}
