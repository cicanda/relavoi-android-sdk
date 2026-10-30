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
    fun `throws ApiError on 500 and does not surface a raw body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-1","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("kaboom"))

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.get("/ping")
            fail("expected ApiError")
        } catch (e: RelavoiException.ApiError) {
            // The status code is kept, because it is the actionable part.
            assertEquals(500, e.statusCode)
            // The body is not, and this is deliberate. A 5xx from the API is a
            // problem document whose `detail` we read; a 5xx from anything in
            // front of it is an error page, and an error page commonly echoes the
            // request URI. /sessions/verify carries a subscriber's number in its
            // query string, so echoing that body into an exception message put a
            // phone number one crash report away from a third party.
            assertTrue("raw body must not be surfaced", e.body == null || !e.body!!.contains("kaboom"))
        }
    }

    @Test
    fun `reads detail out of a problem document on 500`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-1","expiresIn":900}"""))
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                """{"type":"https://api.relavoi.com/errors/internal","status":500,"detail":"Something went wrong."}"""
            )
        )

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.get("/ping")
            fail("expected ApiError")
        } catch (e: RelavoiException.ApiError) {
            assertEquals(500, e.statusCode)
            assertEquals("Something went wrong.", e.body)
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

    // ─── Typed errors from the problem document ─────────────────────────────────
    // The server owns the number rule, so these two refusals are the SDK's only
    // way to tell a caller "that number cannot be used" as opposed to "your
    // request was malformed". Matching is on the slug, not the whole type URL, so
    // it survives the hostname differing between environments.

    private fun problem(slug: String, detail: String): String =
        """{"type":"https://api.relavoi.com/errors/$slug","title":"Unprocessable Entity","status":422,"detail":"$detail"}"""

    @Test
    fun `maps number-not-supported to NumberNotSupported`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"j","expiresIn":900}"""))
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody(problem("number-not-supported", "Relavoi currently connects Nigerian mobile numbers only."))
        )

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.post("/sessions", "{}")
            fail("expected NumberNotSupported")
        } catch (e: RelavoiException.NumberNotSupported) {
            assertEquals("Relavoi currently connects Nigerian mobile numbers only.", e.message)
        }
    }

    @Test
    fun `maps cross-border-not-supported to CrossBorderNotSupported`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"j","expiresIn":900}"""))
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody(problem("cross-border-not-supported", "Both people in a session must have numbers from the same country."))
        )

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.post("/sessions", "{}")
            fail("expected CrossBorderNotSupported")
        } catch (e: RelavoiException.CrossBorderNotSupported) {
            assertTrue(e.message!!.contains("same country"))
        }
    }

    @Test
    fun `maps any other 422 to Validation`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"j","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody(problem("validation", "agentPhone is not valid.")))

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.post("/sessions", "{}")
            fail("expected Validation")
        } catch (e: RelavoiException.Validation) {
            assertTrue(e.message!!.contains("agentPhone"))
        }
    }

    @Test
    fun `a 422 that is not a problem document still becomes Validation`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"j","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody("<html>gateway</html>"))

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.post("/sessions", "{}")
            fail("expected Validation")
        } catch (e: RelavoiException.Validation) {
            // The raw body is not surfaced; only fields we understand are.
            assertTrue(!e.message!!.contains("<html>"))
        }
    }

    @Test
    fun `the slug is matched on any host`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"j","expiresIn":900}"""))
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"type":"https://staging.example.test/errors/number-not-supported","status":422,"detail":"nope"}"""
            )
        )

        val client = ApiClient(baseUrl(), newAuth())
        try {
            client.post("/sessions", "{}")
            fail("expected NumberNotSupported")
        } catch (e: RelavoiException.NumberNotSupported) {
            assertEquals("nope", e.message)
        }
    }
}
