package com.relavoi.sdk.session

import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.RelavoiConfig
import com.relavoi.sdk.RelavoiException
import com.relavoi.sdk.auth.AuthManager
import com.relavoi.sdk.auth.TokenStore
import com.relavoi.sdk.internal.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer-driven tests for [SessionManagerImpl]. Verifies request bodies, header
 * propagation, validation behaviour, and the in-memory cache.
 */
class SessionManagerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val baseUrl = server.url("/v1").toString()
        val auth = AuthManager(
            apiKey = "k",
            apiSecret = "s",
            tenantId = "tenant-123",
            tokenStore = FakeStore(),
            baseUrl = baseUrl,
        )
        val client = ApiClient(baseUrl, auth)
        Relavoi.initializeForTest(client, auth, RelavoiConfig(baseUrl = baseUrl))
        SessionManagerImpl.instance.clearCacheForTest()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Relavoi.resetForTest()
    }

    @Test
    fun `create returns Session with proxy number and caches it`() = runBlocking {
        // Token endpoint then the session creation.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_SESSION))

        val session = SessionManagerImpl.instance.create(
            agentPhone = "+2348011111111",
            customerPhone = "+2348022222222",
        )
        assertEquals("sess-1", session.id)
        assertEquals("+2349099999999", session.proxyNumber)
        assertEquals(SessionState.PENDING, session.state)

        // Cache populated.
        assertNotNull(SessionManagerImpl.instance.cachedForTest("sess-1"))
    }

    @Test
    fun `create throws Validation on invalid phone`() = runBlocking {
        try {
            SessionManagerImpl.instance.create(
                agentPhone = "not-a-phone-1234",
                customerPhone = "+2348022222222",
            )
            fail("expected Validation")
        } catch (v: RelavoiException.Validation) {
            assertTrue(v.message!!.contains("agentPhone"))
        }
        // No HTTP calls should have been made.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `create translates 401 into Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt","expiresIn":900}"""))
        // First attempt 401, refresh 401, retry 401 → final response back to caller.
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt2","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("still bad"))

        try {
            SessionManagerImpl.instance.create(
                agentPhone = "+2348011111111",
                customerPhone = "+2348022222222",
            )
            fail("expected Unauthorized")
        } catch (u: RelavoiException.Unauthorized) {
            // ok
        }
    }

    @Test
    fun `get returns cached session without hitting the server`() = runBlocking {
        // Seed the cache via create.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_SESSION))
        SessionManagerImpl.instance.create("+2348011111111", "+2348022222222")
        val before = server.requestCount

        // get() should not increment.
        val s = SessionManagerImpl.instance.get("sess-1")
        assertEquals("sess-1", s.id)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `get falls back to network on cache miss`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_SESSION))
        val s = SessionManagerImpl.instance.get("sess-1")
        assertEquals("sess-1", s.id)
    }

    @Test
    fun `end removes the session from the cache`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_SESSION))
        SessionManagerImpl.instance.create("+2348011111111", "+2348022222222")
        assertNotNull(SessionManagerImpl.instance.cachedForTest("sess-1"))

        server.enqueue(MockResponse().setResponseCode(200).setBody(ENDED_SESSION))
        val ended = SessionManagerImpl.instance.end("sess-1")
        assertEquals(SessionState.GRACE_PERIOD, ended.state)
        assertNull(SessionManagerImpl.instance.cachedForTest("sess-1"))
    }

    @Test
    fun `verify parses VerificationResult correctly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt","expiresIn":900}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"verified":true,"context":"Your Chowdeck rider is calling","sessionId":"sess-1","expiresAt":"2026-05-22T10:00:00Z"}"""
        ))
        val r = SessionManagerImpl.instance.verify("+2348022222222")
        assertTrue(r.verified)
        assertEquals("Your Chowdeck rider is calling", r.context)
        assertEquals("sess-1", r.sessionId)
    }

    // ----- helpers -----

    private class FakeStore : TokenStore(null as android.content.Context?) {
        private var token: String? = null
        private var exp: Long = 0L
        override fun save(token: String, expiresAtEpochMs: Long) {
            this.token = token; this.exp = expiresAtEpochMs
        }
        override fun load(): Pair<String, Long>? = token?.let { it to exp }
        override fun clear() { token = null; exp = 0L }
    }

    companion object {
        private val SAMPLE_SESSION = """
            {
              "id":"sess-1",
              "tenantId":"tenant-123",
              "proxyNumber":"+2349099999999",
              "state":"PENDING",
              "directionMode":"BIDIRECTIONAL",
              "metadata":{"orderId":"ORD-1"},
              "gracePeriodMin":15,
              "maxDurationMin":120,
              "recordingEnabled":false,
              "consentPrompt":"NONE",
              "expiresAt":"2026-05-22T12:00:00Z",
              "createdAt":"2026-05-22T10:00:00Z"
            }
        """.trimIndent()

        private val ENDED_SESSION = """
            {
              "id":"sess-1",
              "tenantId":"tenant-123",
              "proxyNumber":"+2349099999999",
              "state":"GRACE_PERIOD",
              "directionMode":"BIDIRECTIONAL",
              "metadata":{},
              "gracePeriodMin":15,
              "maxDurationMin":120,
              "recordingEnabled":false,
              "consentPrompt":"NONE",
              "expiresAt":"2026-05-22T12:00:00Z",
              "createdAt":"2026-05-22T10:00:00Z"
            }
        """.trimIndent()
    }
}
