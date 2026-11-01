package com.relavoi.sdk.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.RelavoiException
import com.relavoi.sdk.internal.Logger
import com.relavoi.sdk.internal.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [SessionManager] implementation. Accessed via [instance] — never `new` it
 * directly (the constructor is `private`).
 */
class SessionManagerImpl private constructor() : SessionManager {

    /**
     * In-memory cache of sessions the app has touched. Allows synchronous reads from
     * [initiateCall] and avoids redundant `GET /sessions/{id}` round-trips after a
     * `create`.
     */
    private val cache = ConcurrentHashMap<String, Session>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun create(
        agentPhone: String,
        customerPhone: String,
        metadata: Map<String, String>?,
        gracePeriodMinutes: Int,
        directionMode: DirectionMode,
    ): Session {
        val agent = PhoneUtils.requireNumberShaped(agentPhone, "agentPhone")
        val customer = PhoneUtils.requireNumberShaped(customerPhone, "customerPhone")

        // Server-side rule: recording on requires a non-NONE consent prompt. We validate
        // client-side too so the user gets immediate feedback instead of a 422.

        val body = json.encodeToString(
            CreateSessionRequest.serializer(),
            CreateSessionRequest(
                agentPhone = agent,
                customerPhone = customer,
                metadata = metadata,
                gracePeriodMinutes = gracePeriodMinutes,
                directionMode = directionMode.name,
            ),
        )
        val api = Relavoi.requireApiClient()
        val raw = withContext(Dispatchers.IO) { api.post("/sessions", body) }
        val session = json.decodeFromString(Session.serializer(), raw)
        cache[session.id] = session
        Logger.d(
            "Created session id=${session.id}"
        )
        return session
    }

    override suspend fun get(id: String): Session {
        require(id.isNotBlank()) { "id must not be blank" }
        cache[id]?.let { return it }
        val api = Relavoi.requireApiClient()
        val raw = withContext(Dispatchers.IO) { api.get("/sessions/$id") }
        val session = json.decodeFromString(Session.serializer(), raw)
        cache[session.id] = session
        return session
    }

    override suspend fun swapTarget(id: String, customerPhone: String): Session {
        require(id.isNotBlank()) { "id must not be blank" }
        val customer = PhoneUtils.requireNumberShaped(customerPhone, "customerPhone")
        val api = Relavoi.requireApiClient()
        val body = json.encodeToString(
            SwapTargetRequest.serializer(),
            SwapTargetRequest(customerPhone = customer),
        )
        val raw = withContext(Dispatchers.IO) { api.patch("/sessions/$id/target", body) }
        val session = json.decodeFromString(Session.serializer(), raw)
        // The cached copy still names the old target.
        cache[session.id] = session
        Logger.d("Swapped target for session id=${session.id}")
        return session
    }

    override suspend fun end(id: String): Session {
        require(id.isNotBlank()) { "id must not be blank" }
        val api = Relavoi.requireApiClient()
        val raw = withContext(Dispatchers.IO) { api.post("/sessions/$id/end", "{}") }
        val session = json.decodeFromString(Session.serializer(), raw)
        cache.remove(session.id)
        Logger.d("Ended session id=${session.id}")
        return session
    }

    override suspend fun list(
        state: SessionState?,
        limit: Int,
        after: String?,
    ): SessionListResponse {
        val params = buildList {
            add("limit=$limit")
            state?.let { add("state=${it.name}") }
            after?.let { add("after=$it") }
        }.joinToString("&")
        val path = if (params.isEmpty()) "/sessions" else "/sessions?$params"
        val api = Relavoi.requireApiClient()
        val raw = withContext(Dispatchers.IO) { api.get(path) }
        return json.decodeFromString(SessionListResponse.serializer(), raw)
    }

    override suspend fun verify(userPhone: String): VerificationResult {
        val canonical = PhoneUtils.requireNumberShaped(userPhone, "userPhone")
        val tenantId = Relavoi.requireAuthManager().tenantId
        // Send the number as the caller gave it, minus separators — the backend
        // normalises it and hashes it server-side with a per-tenant salt, so a
        // client-side hash could never match. URLEncoder (not android.net.Uri) so
        // this is unit-testable on the JVM. '+' must be percent-encoded, which
        // URLEncoder does.
        //
        // This number is in the query string, which is why ApiClient logs the
        // path without it.
        val encoded = URLEncoder.encode(canonical, "UTF-8")
        val path = "/sessions/verify?userPhone=$encoded&tenantId=$tenantId"
        val api = Relavoi.requireApiClient()
        val raw = withContext(Dispatchers.IO) { api.get(path) }
        return json.decodeFromString(VerificationResult.serializer(), raw)
    }

    override fun initiateCall(sessionId: String, context: Context) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val session = cache[sessionId]
            ?: throw RelavoiException.Validation(
                "session $sessionId not cached — call get() or create() first"
            )
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${session.proxyNumber}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The session id is the correlation key here. The proxy number used to be
        // logged masked to its last four digits, which is still part of a number.
        Logger.d("Dialing proxy for session $sessionId")
        context.startActivity(intent)
    }

    /** Test hook: clear the in-memory cache. */
    internal fun clearCacheForTest() {
        cache.clear()
    }

    /** Test hook: peek into the cache without doing a fetch. */
    internal fun cachedForTest(id: String): Session? = cache[id]

    companion object {
        val instance: SessionManagerImpl by lazy { SessionManagerImpl() }
    }
}
