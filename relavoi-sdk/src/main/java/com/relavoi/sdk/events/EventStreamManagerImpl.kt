package com.relavoi.sdk.events

import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.internal.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default WebSocket-backed [EventStreamManager]. Uses OkHttp's built-in WebSocket
 * client. Reconnects with exponential backoff (500ms → 30s cap), resetting the backoff
 * on every successfully received message.
 *
 * Auto-disconnect: when the last listener is removed, the socket is closed. Re-adding
 * a listener does NOT auto-reconnect — call [connect] explicitly.
 */
class EventStreamManagerImpl private constructor() : EventStreamManager {

    private val listeners = CopyOnWriteArrayList<EventListener>()
    private val connectedFlag = AtomicBoolean(false)

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var currentBackoffMs: Long = INITIAL_BACKOFF_MS

    @Volatile
    private var shouldRun: Boolean = false

    override fun connect() {
        if (shouldRun) return
        shouldRun = true
        scope.launch { openSocket() }
    }

    override fun disconnect() {
        shouldRun = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        connectedFlag.set(false)
        Logger.d("EventStream disconnected")
    }

    override fun isConnected(): Boolean = connectedFlag.get()

    override fun addListener(listener: EventListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: EventListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            Logger.d("EventStream: no listeners left → auto-disconnecting")
            disconnect()
        }
    }

    private suspend fun openSocket() {
        val token = try {
            Relavoi.requireAuthManager().getValidToken()
        } catch (t: Throwable) {
            Logger.w("EventStream cannot fetch token: ${t.message}")
            scheduleReconnect()
            return
        }
        val wsUrl = Relavoi.config.wsUrl
        val request = Request.Builder()
            .url("$wsUrl?token=$token")
            .build()

        Logger.d("EventStream opening WebSocket to $wsUrl")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectedFlag.set(true)
                Logger.d("EventStream WebSocket open (code=${response.code})")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                currentBackoffMs = INITIAL_BACKOFF_MS // success resets backoff
                val event = parse(text)
                listeners.forEach {
                    try {
                        it.onEvent(event)
                    } catch (t: Throwable) {
                        Logger.e("Listener threw in onEvent: ${t.message}", t)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // We don't expect binary frames; ignore.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectedFlag.set(false)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedFlag.set(false)
                Logger.d("EventStream WebSocket closed (code=$code reason=$reason)")
                if (shouldRun) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectedFlag.set(false)
                Logger.w("EventStream WebSocket failure: ${t.message}", t)
                if (shouldRun) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        val delayMs = currentBackoffMs
        currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        Logger.d("EventStream reconnecting in ${delayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isActive && shouldRun) openSocket()
        }
    }

    private fun parse(text: String): RelavoiEvent {
        return try {
            json.decodeFromString(RelavoiEvent.serializer(), text)
        } catch (t: Throwable) {
            // Unknown type discriminator or malformed payload — wrap as Unknown.
            val type = runCatching {
                val obj = json.parseToJsonElement(text) as? JsonObject
                obj?.get("type")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "<unknown>"
            val sessionId = runCatching {
                val obj = json.parseToJsonElement(text) as? JsonObject
                obj?.get("sessionId")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            RelavoiEvent.Unknown(sessionId = sessionId, type = type, rawPayload = text)
        }
    }

    companion object {
        val instance: EventStreamManagerImpl by lazy { EventStreamManagerImpl() }

        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
