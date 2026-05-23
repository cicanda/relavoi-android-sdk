package com.relavoi.sdk.internal

import com.relavoi.sdk.RelavoiException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ApiClient]. We use a fake [com.relavoi.sdk.auth.AuthManager]-like object
 * indirectly: we point the AuthInterceptor at a tiny stub by constructing a real
 * AuthManager whose `/auth/token` endpoint is served by the same MockWebServer.
 */
class ApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/v1").toString()

    private fun newAuth(): com.relavoi.sdk.auth.AuthManager {
        // No EncryptedSharedPreferences in unit tests — use a fake TokenStore subclass.
        return com.relavoi.sdk.auth.AuthManager(
            apiKey = "k",
            apiSecret = "s",
            tenantId = "t",
            tokenStore = FakeTokenStore(),
            baseUrl = baseUrl(),
        )
    }

    @Test
    fun `attaches Authorization header on successful call`() {
        // Token endpoint, then the real call.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-1","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val client = ApiClient(baseUrl(), newAuth())
        val body = client.get("/ping")
        assertEquals("""{"ok":true}""", body)

        server.takeRequest() // /auth/token
        val req = server.takeRequest()
        assertEquals("Bearer jwt-1", req.getHeader("Authorization"))
    }

    @Test
    fun `retries once on 401 with a fresh token`() {
        // 1) initial token, 2) first request → 401, 3) refresh token, 4) retry → 200
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-1","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-2","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val client = ApiClient(baseUrl(), newAuth())
        val body = client.get("/ping")
        assertEquals("""{"ok":true}""", body)

        server.takeRequest() // /auth/token
        val first = server.takeRequest()
        assertEquals("Bearer jwt-1", first.getHeader("Authorization"))
        server.takeRequest() // /auth/token refresh
        val retry = server.takeRequest()
        assertEquals("Bearer jwt-2", retry.getHeader("Authorization"))
    }

    @Test
    fun `throws RateLimited with Retry-After on 429`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-1","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("slow"))

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.get("/ping")
            fail("expected RateLimited")
        } catch (rl: RelavoiException.RateLimited) {
            assertEquals(5L, rl.retryAfterSec)
        }
    }

    @Test
    fun `throws ApiError with body on 500`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-1","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.get("/ping")
            fail("expected ApiError")
        } catch (e: RelavoiException.ApiError) {
            assertEquals(500, e.statusCode)
            assertNotNull(e.body)
            assertTrue(e.body!!.contains("kaboom"))
        }
    }

    /** Minimal in-memory TokenStore stand-in for tests (real one needs Android context). */
    private class FakeTokenStore : com.relavoi.sdk.auth.TokenStore(null as android.content.Context?) {
        private var token: String? = null
        private var exp: Long = 0L
        override fun save(token: String, expiresAtEpochMs: Long) {
            this.token = token; this.exp = expiresAtEpochMs
        }
        override fun load(): Pair<String, Long>? =
            token?.let { it to exp }
        override fun clear() { token = null; exp = 0L }
    }
}
