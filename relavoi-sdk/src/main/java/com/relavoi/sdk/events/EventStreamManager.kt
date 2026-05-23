package com.relavoi.sdk.events

/**
 * Real-time event stream from the Relavoi backend via WebSocket. Use this to drive
 * UI updates when sessions activate, calls are answered, etc.
 *
 * Lifecycle:
 *  1. Add at least one listener via [addListener] (otherwise the stream auto-disconnects).
 *  2. Call [connect] to open the WebSocket. Reconnection on transport failures is
 *     handled automatically with exponential backoff.
 *  3. Call [disconnect] explicitly when the app goes to background and you don't need
 *     events anymore.
 */
interface EventStreamManager {

    /** Open the WebSocket (idempotent). */
    fun connect()

    /** Close the WebSocket (idempotent). Cancels in-flight reconnect attempts. */
    fun disconnect()

    /** True if the WebSocket is currently open. */
    fun isConnected(): Boolean

    /** Subscribe to events. Listeners receive every event the server sends. */
    fun addListener(listener: EventListener)

    /** Unsubscribe. If no listeners remain, the stream is auto-disconnected. */
    fun removeListener(listener: EventListener)
}
