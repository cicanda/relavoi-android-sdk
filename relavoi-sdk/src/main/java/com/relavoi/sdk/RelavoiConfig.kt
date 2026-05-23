package com.relavoi.sdk

/**
 * Configuration for the Relavoi SDK. Pass an instance to [Relavoi.initialize].
 *
 * @property baseUrl Base URL for the Relavoi REST API. Defaults to production.
 * @property webSocketUrl Optional override for the event-stream WebSocket URL. When null
 *  (the default), the URL is derived from [baseUrl] by swapping the scheme and appending
 *  the `/ws` suffix — see [wsUrl].
 * @property enableLogging When true, the SDK emits debug logs via [android.util.Log] and
 *  attaches OkHttp's logging interceptor. Leave this off in release builds.
 * @property offlineQueueMaxSize Maximum number of actions buffered in the offline queue
 *  before the oldest entries are dropped. See `OfflineQueue`.
 */
data class RelavoiConfig(
    val baseUrl: String = "https://api.relavoi.com/v1",
    val webSocketUrl: String? = null,
    val enableLogging: Boolean = false,
    val offlineQueueMaxSize: Int = 100,
) {
    /**
     * Effective WebSocket URL for the event stream. If [webSocketUrl] is set, it wins.
     * Otherwise we derive from [baseUrl] by swapping http→ws / https→wss and appending /ws.
     */
    val wsUrl: String
        get() {
            webSocketUrl?.let { return it }
            val trimmed = baseUrl.trimEnd('/')
            val swapped = when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> trimmed
            }
            return "$swapped/ws"
        }
}
