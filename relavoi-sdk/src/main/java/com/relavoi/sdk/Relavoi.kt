package com.relavoi.sdk

import android.content.Context
import com.relavoi.sdk.auth.AuthManager
import com.relavoi.sdk.auth.TokenStore
import com.relavoi.sdk.events.EventStreamManager
import com.relavoi.sdk.events.EventStreamManagerImpl
import com.relavoi.sdk.internal.ApiClient
import com.relavoi.sdk.internal.Logger
import com.relavoi.sdk.presence.PresenceManager
import com.relavoi.sdk.push.PushTokenManager
import com.relavoi.sdk.session.SessionManager
import com.relavoi.sdk.session.SessionManagerImpl
import com.relavoi.sdk.verification.CallVerificationManager
import com.relavoi.sdk.verification.CallVerificationManagerImpl

/**
 * Entry point for the Relavoi Android SDK.
 *
 * Call [initialize] once at application startup (e.g. in `Application.onCreate`).
 * Subsystems are exposed as lazy properties and will throw
 * [RelavoiException.NotInitialized] if accessed before [initialize] is called.
 *
 * Example:
 * ```
 * Relavoi.initialize(
 *   context = applicationContext,
 *   apiKey = "sk_live_...",
 *   apiSecret = "secret_...",
 *   tenantId = "tenant-uuid",
 * )
 * val session = Relavoi.sessions.create(agentPhone, customerPhone)
 * ```
 */
object Relavoi {

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    internal var appContext: Context? = null
        private set

    @Volatile
    internal var config: RelavoiConfig = RelavoiConfig()
        private set

    @Volatile
    internal var authManager: AuthManager? = null
        private set

    @Volatile
    internal var apiClient: ApiClient? = null
        private set

    /**
     * Initialize the Relavoi SDK. Safe to call from any thread.
     *
     * @param context The host application context. The SDK only retains the application
     *  context, never an Activity, so this cannot leak.
     * @param apiKey Tenant API key (e.g. `sk_live_xxx`). Required.
     * @param apiSecret Tenant API secret. Required.
     * @param tenantId Tenant UUID. Required.
     * @param config Optional SDK configuration. Defaults to production endpoints.
     *
     * Subsequent calls are idempotent: they overwrite the cached config and credentials
     * and log a warning. This makes hot-reload during development painless but means
     * production code should call this exactly once.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        apiKey: String,
        apiSecret: String,
        tenantId: String,
        config: RelavoiConfig = RelavoiConfig(),
    ) {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(apiSecret.isNotBlank()) { "apiSecret must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }

        synchronized(this) {
            if (initialized) {
                Logger.w("Relavoi.initialize() called more than once — overwriting prior config.")
            }
            val appCtx = context.applicationContext
            this.appContext = appCtx
            this.config = config

            val tokenStore = TokenStore(appCtx)
            val auth = AuthManager(
                apiKey = apiKey,
                apiSecret = apiSecret,
                tenantId = tenantId,
                tokenStore = tokenStore,
                baseUrl = config.baseUrl,
                enableLogging = config.enableLogging,
            )
            this.authManager = auth
            this.apiClient = ApiClient(
                baseUrl = config.baseUrl,
                authManager = auth,
                enableLogging = config.enableLogging,
            )

            // Install the call-state observer eagerly so verification works immediately.
            CallVerificationManagerImpl.initializeObserver(appCtx)

            initialized = true
            Logger.i("Relavoi SDK initialized for tenant=$tenantId baseUrl=${config.baseUrl}")
        }
    }

    /** True if [initialize] has been called successfully. */
    @JvmStatic
    fun isInitialized(): Boolean = initialized

    private fun requireInit() {
        if (!initialized) throw RelavoiException.NotInitialized()
    }

    /** Session lifecycle subsystem. */
    @JvmStatic
    val sessions: SessionManager
        get() {
            requireInit()
            return SessionManagerImpl.instance
        }

    /** Real-time event stream over WebSocket. */
    @JvmStatic
    val events: EventStreamManager
        get() {
            requireInit()
            return EventStreamManagerImpl.instance
        }

    /** Active-call verification subsystem (the Revolut-style banner). */
    @JvmStatic
    val verification: CallVerificationManager
        get() {
            requireInit()
            return CallVerificationManagerImpl.instance
        }

    /** Foreground/background presence reporting. */
    @JvmStatic
    val presence: PresenceManager
        get() {
            requireInit()
            return PresenceManager.instance
        }

    /** FCM device-token registration. */
    @JvmStatic
    val push: PushTokenManager
        get() {
            requireInit()
            return PushTokenManager.instance
        }

    /**
     * Internal accessor used by subsystem singletons to reach the shared [ApiClient].
     * Throws if the SDK hasn't been initialized.
     */
    internal fun requireApiClient(): ApiClient {
        requireInit()
        return apiClient ?: throw RelavoiException.NotInitialized()
    }

    /**
     * Test-only entry point that bypasses Context-dependent initialization. Lets unit
     * tests wire a pre-built [ApiClient] and [AuthManager] into the singleton without
     * touching Android's Keystore.
     */
    internal fun initializeForTest(
        apiClient: ApiClient,
        authManager: AuthManager,
        config: RelavoiConfig = RelavoiConfig(),
    ) {
        synchronized(this) {
            this.apiClient = apiClient
            this.authManager = authManager
            this.config = config
            this.appContext = null
            initialized = true
        }
    }

    /** Test-only: reset to uninitialized. */
    internal fun resetForTest() {
        synchronized(this) {
            apiClient = null
            authManager = null
            appContext = null
            config = RelavoiConfig()
            initialized = false
        }
    }

    /** Internal accessor for the auth manager. */
    internal fun requireAuthManager(): AuthManager {
        requireInit()
        return authManager ?: throw RelavoiException.NotInitialized()
    }

    /** Internal accessor for the application context. */
    internal fun requireContext(): Context {
        requireInit()
        return appContext ?: throw RelavoiException.NotInitialized()
    }
}
