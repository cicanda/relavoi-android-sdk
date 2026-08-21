package com.relavoi.sdk.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.RelavoiConfig
import com.relavoi.sdk.events.RelavoiEvent
import com.relavoi.sdk.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer test harness for the Relavoi Android SDK against the live backend
 * (api.relavoi.com, Bolt Nigeria test tenant). Not a production UI — a column of
 * buttons that each exercise one SDK feature and append the result to a log view.
 *
 * The UI is built programmatically on purpose: no layout XML to keep in sync.
 */
class MainActivity : AppCompatActivity() {

    // Dev fixtures — Nigerian E.164 test numbers.
    private val defaultAgentPhone = "+2347067379297"
    private val defaultCustomerPhone = "+2348162662319"
    private val defaultUserPhone = "+2347067379297"

    private var currentSessionId: String? = null

    private lateinit var agentInput: EditText
    private lateinit var customerInput: EditText
    private lateinit var userInput: EditText
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private var eventsListenerAttached = false

    private val phonePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> log("READ_PHONE_STATE ${if (granted) "granted" else "denied (verification degrades)"}") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        Relavoi.initialize(
            context = applicationContext,
            apiKey = BuildConfig.RELAVOI_API_KEY,
            apiSecret = BuildConfig.RELAVOI_API_SECRET,
            tenantId = BuildConfig.RELAVOI_TENANT_ID,
            // Leave webSocketUrl unset so the SDK derives the WS endpoint from baseUrl.
            config = RelavoiConfig(
                baseUrl = BuildConfig.RELAVOI_BASE_URL,
                enableLogging = true,
            ),
        )
        log("SDK initialized → ${BuildConfig.RELAVOI_BASE_URL} (tenant ${BuildConfig.RELAVOI_TENANT_ID})")

        ensurePhoneStatePermission()
    }

    // ─── UI construction ──────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setPadding(24, 24, 24, 24)
        }

        // Scrollable controls area (top half).
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        agentInput = labeledInput(controls, "Agent phone", defaultAgentPhone)
        customerInput = labeledInput(controls, "Customer phone", defaultCustomerPhone)
        userInput = labeledInput(controls, "User phone (verify/presence/push)", defaultUserPhone)

        button(controls, "Create Session") { onCreateSession() }
        button(controls, "Get Session") { onGetSession() }
        button(controls, "Initiate Call (native dialer)") { onInitiateCall() }
        button(controls, "Verify Call") { onVerify() }
        button(controls, "Check Overlay / Phone Permission") { onCheckPermissions() }
        button(controls, "Register Push Token (needs Firebase)") { onRegisterPush() }
        button(controls, "Connect Events (WebSocket)") { onConnectEvents() }
        button(controls, "Update Presence") { onPresence() }
        button(controls, "End Session") { onEndSession() }
        button(controls, "List Sessions") { onListSessions() }
        button(controls, "Clear Log") { logText.text = "" }

        val controlsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(controls)
        }

        // Scrollable log area (bottom half).
        logText = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF111111.toInt())
            setPadding(12, 12, 12, 12)
            addView(logText)
        }
        logText.setTextColor(0xFFCCFFCC.toInt())

        root.addView(controlsScroll)
        root.addView(TextView(this).apply { text = "── SDK log ──"; setPadding(0, 12, 0, 4) })
        root.addView(logScroll)
        return root
    }

    private fun labeledInput(parent: LinearLayout, label: String, prefill: String): EditText {
        parent.addView(TextView(this).apply { text = label; setPadding(0, 8, 0, 0) })
        val et = EditText(this).apply {
            setText(prefill)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        parent.addView(et)
        return et
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        parent.addView(b)
        return b
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        runOnUiThread {
            logText.append("[$ts] $message\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun ensurePhoneStatePermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    // ─── Sessions ─────────────────────────────────────────────────────────────

    private fun onCreateSession() {
        val agent = agentInput.text.toString().trim()
        val customer = customerInput.text.toString().trim()
        log("Creating session ($agent → $customer)…")
        lifecycleScope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    Relavoi.sessions.create(
                        agentPhone = agent,
                        customerPhone = customer,
                        metadata = mapOf("orderId" to "SDK-TEST-001", "type" to "delivery"),
                    )
                }
                currentSessionId = s.id
                log("Session created: ${s.id}")
                log("  proxy=${s.proxyNumber} state=${s.state} dir=${s.directionMode}")
            } catch (t: Throwable) {
                log("ERROR create: ${t.message}")
            }
        }
    }

    private fun onGetSession() {
        val id = currentSessionId ?: return log("No session yet — Create Session first")
        log("Getting session $id…")
        lifecycleScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { Relavoi.sessions.get(id) }
                log("Session $id: state=${s.state} proxy=${s.proxyNumber} calls=${s.callCount ?: 0}")
                log("  created=${s.createdAt} expires=${s.expiresAt}")
            } catch (t: Throwable) {
                log("ERROR get: ${t.message}")
            }
        }
    }

    private fun onInitiateCall() {
        val id = currentSessionId ?: return log("No session yet — Create Session first")
        try {
            // Synchronous; opens the native dialer pre-filled with the proxy number.
            Relavoi.sessions.initiateCall(id, this)
            log("initiateCall($id) → native dialer opened with proxy number")
        } catch (t: Throwable) {
            log("ERROR initiateCall: ${t.message}")
        }
    }

    private fun onEndSession() {
        val id = currentSessionId ?: return log("No session yet — Create Session first")
        log("Ending session $id…")
        lifecycleScope.launch {
            try {
                val ended = withContext(Dispatchers.IO) { Relavoi.sessions.end(id) }
                log("Session ended: ${ended.id} → ${ended.state}")
                currentSessionId = null
            } catch (t: Throwable) {
                log("ERROR end: ${t.message}")
            }
        }
    }

    private fun onListSessions() {
        log("Listing sessions…")
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { Relavoi.sessions.list() }
                log("Sessions: ${resp.pagination.count} returned")
                resp.data.forEach { log("  ${it.id.take(8)}… ${it.state} proxy=${it.proxyNumber}") }
            } catch (t: Throwable) {
                log("ERROR list: ${t.message}")
            }
        }
    }

    // ─── Verification ─────────────────────────────────────────────────────────

    private fun onVerify() {
        val phone = userInput.text.toString().trim()
        log("Verifying $phone…  callActive=${Relavoi.verification.isCallActive()}")
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Relavoi.verification.verify(phone) }
                log("verified=${r.verified} context=${r.context ?: "—"} session=${r.sessionId ?: "—"}")
            } catch (t: Throwable) {
                log("ERROR verify: ${t.message}")
            }
        }
    }

    private fun onCheckPermissions() {
        // READ_PHONE_STATE via the SDK helper.
        log("READ_PHONE_STATE: ${if (Relavoi.verification.hasPhoneStatePermission(this)) "granted" else "denied"}")
        // Overlay (SYSTEM_ALERT_WINDOW) via platform API — the SDK's floating bubble
        // needs it; the SDK does not expose a helper, so we use Settings directly.
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        if (canOverlay) {
            log("Overlay permission (SYSTEM_ALERT_WINDOW): GRANTED")
        } else {
            log("Overlay permission: NOT granted — opening system settings")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // ─── Push ─────────────────────────────────────────────────────────────────

    private fun onRegisterPush() {
        val phone = userInput.text.toString().trim()
        log("Fetching FCM token…")
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    log("FCM token fetch failed: ${task.exception?.message}")
                    log("  → Add google-services.json from the Firebase Console (see README).")
                    return@addOnCompleteListener
                }
                val token = task.result
                log("FCM token: ${token.take(24)}…")
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Relavoi.push.registerToken(userPhone = phone, fcmToken = token) }
                        log("Push token registered for $phone")
                    } catch (t: Throwable) {
                        log("ERROR registerToken: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            // FirebaseApp not initialized (no google-services.json) — expected in this sample.
            log("Firebase not configured: ${t.message}")
            log("  → Add google-services.json from the Firebase Console (see README). Other features work without it.")
        }
    }

    // ─── Events ───────────────────────────────────────────────────────────────

    private fun onConnectEvents() {
        if (!eventsListenerAttached) {
            Relavoi.events.addListener { event -> log("EVENT ${describe(event)}") }
            eventsListenerAttached = true
            log("Event listener attached")
        }
        Relavoi.events.connect()
        log("events.connect() called — connected=${Relavoi.events.isConnected()}")
    }

    private fun describe(e: RelavoiEvent): String = when (e) {
        is RelavoiEvent.SessionCreated -> "session.created ${e.sessionId} proxy=${e.proxyNumber}"
        is RelavoiEvent.SessionActivated -> "session.activated ${e.sessionId}"
        is RelavoiEvent.SessionExpired -> "session.expired ${e.sessionId}"
        is RelavoiEvent.CallIncoming -> "call.incoming ${e.sessionId} caller=${e.callerNumber}"
        is RelavoiEvent.CallAnswered -> "call.answered ${e.sessionId}"
        is RelavoiEvent.CallEnded -> "call.ended ${e.sessionId} dur=${e.durationSeconds}s"
        is RelavoiEvent.SmsReceived -> "sms.received ${e.sessionId}"
        is RelavoiEvent.Unknown -> "unknown type=${e.type}"
    }

    // ─── Presence ─────────────────────────────────────────────────────────────

    private fun onPresence() {
        val phone = userInput.text.toString().trim()
        // Presence is automatic once a user phone is set (SDK observes app lifecycle).
        Relavoi.presence.setUserPhone(phone)
        log("Presence user set to $phone — SDK auto-reports online/background/offline on lifecycle")
    }
}
