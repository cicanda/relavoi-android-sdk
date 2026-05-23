package com.relavoi.sdk.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.relavoi.sdk.Relavoi
import com.relavoi.sdk.RelavoiConfig
import com.relavoi.sdk.session.Session
import kotlinx.coroutines.launch

/**
 * Sample harness exercising the public Relavoi SDK surface. Intentionally minimal —
 * no view binding, no Material niceties, just enough to demonstrate the flow.
 */
class MainActivity : AppCompatActivity() {

    // Dev fixtures — Nigerian E.164 numbers.
    private val agentPhone = "+2348011111111"
    private val customerPhone = "+2348022222222"

    private var lastSession: Session? = null

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    private val phonePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusText.text = if (granted) {
            "READ_PHONE_STATE granted"
        } else {
            "READ_PHONE_STATE denied — verification will degrade"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)

        Relavoi.initialize(
            context = applicationContext,
            apiKey = BuildConfig.RELAVOI_API_KEY,
            apiSecret = BuildConfig.RELAVOI_API_SECRET,
            tenantId = BuildConfig.RELAVOI_TENANT_ID,
            config = RelavoiConfig(
                baseUrl = BuildConfig.RELAVOI_BASE_URL,
                enableLogging = true,
            ),
        )

        ensurePhoneStatePermission()

        findViewById<Button>(R.id.btnCreate).setOnClickListener { onCreate() }
        findViewById<Button>(R.id.btnVerify).setOnClickListener { onVerify() }
        findViewById<Button>(R.id.btnPresence).setOnClickListener { onPresence() }
        findViewById<Button>(R.id.btnEnd).setOnClickListener { onEnd() }
        findViewById<Button>(R.id.btnDial).setOnClickListener { onDial() }
    }

    private fun ensurePhoneStatePermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun onCreate() {
        statusText.text = "Creating session…"
        lifecycleScope.launch {
            try {
                val s = Relavoi.sessions.create(
                    agentPhone = agentPhone,
                    customerPhone = customerPhone,
                    metadata = mapOf("orderId" to "ORD-${System.currentTimeMillis()}"),
                )
                lastSession = s
                statusText.text = "Session ${s.id} → proxy ${s.proxyNumber} (${s.state})"
            } catch (t: Throwable) {
                statusText.text = "Create failed: ${t.message}"
            }
        }
    }

    private fun onVerify() {
        statusText.text = "Verifying…"
        lifecycleScope.launch {
            try {
                val r = Relavoi.verification.verify(customerPhone)
                resultText.text = "verified=${r.verified} context=${r.context ?: "—"}"
            } catch (t: Throwable) {
                resultText.text = "Verify failed: ${t.message}"
            }
        }
    }

    private fun onPresence() {
        Relavoi.presence.setUserPhone(customerPhone)
        statusText.text = "Presence enabled for $customerPhone (lifecycle drives transitions)"
    }

    private fun onEnd() {
        val s = lastSession ?: run {
            statusText.text = "No session to end — create one first"
            return
        }
        statusText.text = "Ending ${s.id}…"
        lifecycleScope.launch {
            try {
                val ended = Relavoi.sessions.end(s.id)
                statusText.text = "Ended ${ended.id} → ${ended.state}"
                lastSession = null
            } catch (t: Throwable) {
                statusText.text = "End failed: ${t.message}"
            }
        }
    }

    private fun onDial() {
        val s = lastSession ?: run {
            statusText.text = "No session to dial — create one first"
            return
        }
        try {
            Relavoi.sessions.initiateCall(s.id, this)
        } catch (t: Throwable) {
            statusText.text = "Dial failed: ${t.message}"
        }
    }
}
