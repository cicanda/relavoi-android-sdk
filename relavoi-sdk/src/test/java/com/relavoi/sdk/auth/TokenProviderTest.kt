package com.relavoi.sdk.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The point of the token provider is that the SDK can work with no secret in
 * the process at all. These assert exactly that.
 */
class TokenProviderTest {

    /**
     * A TokenStore that keeps everything in memory. TokenStore accepts a null
     * context already, for exactly this, so no Robolectric is needed.
     */
    private class FakeStore : TokenStore(null) {
        var saved: Pair<String, Long>? = null
        override fun save(token: String, expiresAtEpochMs: Long) {
            saved = token to expiresAtEpochMs
        }

        override fun load(): Pair<String, Long>? = saved
        override fun clear() {
            saved = null
        }
    }

    @Test
    fun `asks the provider for a token and caches it`() = runBlocking {
        val asked = AtomicInteger(0)
        val auth = AuthManager(
            source = TokenSource.Provider {
                asked.incrementAndGet()
                RelavoiToken(accessToken = "device-token", expiresInSeconds = 900)
            },
            tenantId = "tenant-uuid",
            tokenStore = FakeStore(),
            baseUrl = "https://api.relavoi.com/v1",
        )

        assertEquals("device-token", auth.getValidToken())
        assertEquals("device-token", auth.getValidToken())
        // Cached: a provider that costs a network round trip should not be
        // called twice for one token's lifetime.
        assertEquals(1, asked.get())
    }

    @Test
    fun `a provider token is not persisted`() = runBlocking {
        val store = FakeStore()
        val auth = AuthManager(
            source = TokenSource.Provider {
                RelavoiToken(accessToken = "device-token", expiresInSeconds = 900)
            },
            tenantId = "tenant-uuid",
            tokenStore = store,
            baseUrl = "https://api.relavoi.com/v1",
        )
        auth.getValidToken()

        // Nothing stored: the provider can always mint another, and a
        // persisted one would outlive a sign-out in the host app.
        assertEquals(null, store.saved)
    }

    @Test
    fun `a blank token from the provider is an error`() = runBlocking {
        val auth = AuthManager(
            source = TokenSource.Provider {
                RelavoiToken(accessToken = "", expiresInSeconds = 900)
            },
            tenantId = "tenant-uuid",
            tokenStore = FakeStore(),
            baseUrl = "https://api.relavoi.com/v1",
        )
        val threw = try {
            auth.getValidToken()
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        // It must not carry on and send an empty Authorization header on every
        // request afterwards.
        assertTrue(threw)
    }
}
