package com.relavoi.sdk.session

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        recordingEnabled: Boolean,
        consentPrompt: ConsentPrompt,
    ): Session {
        PhoneUtils.requireValidE164(agentPhone, "agentPhone")
        PhoneUtils.requireValidE164(customerPhone, "customerPhone")

        // Server-side rule: recording on requires a non-NONE consent prompt. We validate
        // client-side too so the user gets immediate feedback instead of a 422.
        if (recordingEnabled && consentPrompt == ConsentPrompt.NONE) {
            throw RelavoiException.Validation(
                "consentPrompt cannot be NONE when recordingEnabled=true"
            )
        }

        val body = json.encodeToString(
            CreateSessionRequest.serializer(),
            CreateSessionRequest(
                agentPhone = agentPhone,
                customerPhone = customerPhone,
                metadata = metadata,
                gracePeriodMinutes = gracePeriodMinutes,
                directionMode = directionMode.name,
                recordingEnabled = recordingEnabled,
                consentPrompt = consentPrompt.name,
            ),
        )
        val api = Relavoi.requireApiClient()
        val raw = withContext(Dispatchers.IO) { api.post("/sessions", body) }
        val session = json.decodeFromString(Session.serializer(), raw)
        cache[session.id] = session
        Logger.d(
            "Created session id=${session.id} proxy=${PhoneUtils.maskPhone(session.proxyNumber)}"
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
        PhoneUtils.requireValidE164(userPhone, "userPhone")
        val tenantId = Relavoi.requireAuthManager().tenantId
        // userPhone is sensitive — the backend hashes it server-side, but we URL-encode
        // and let TLS protect the transport.
        val encoded = Uri.encode(userPhone)
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
        Logger.d("Dialing proxy ${PhoneUtils.maskPhone(session.proxyNumber)} for session $sessionId")
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
