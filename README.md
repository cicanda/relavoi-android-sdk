# Relavoi Android SDK

Kotlin SDK for embedding Relavoi's privacy telephony into Android apps. Targets minSdk 24, compileSdk 34, Kotlin/JVM 17.

## What it gives you

| Subsystem | What it does |
|---|---|
| `Relavoi.sessions` | Create / get / end / list masking sessions; `initiateCall()` launches `ACTION_DIAL` with the proxy number |
| `Relavoi.verification` | `verify(userPhone)` returns the verified-call banner data; `isCallActive()` reads from a `TelephonyCallback`/`PhoneStateListener` shim |
| `Relavoi.push` | Register/deactivate FCM tokens via `/devices/token` |
| `Relavoi.events` | OkHttp WebSocket with exponential-backoff reconnect; sealed `RelavoiEvent` hierarchy |
| `Relavoi.presence` | Auto online/background/offline transitions via `ProcessLifecycleOwner` |
| `FloatingBubbleService` | `SYSTEM_ALERT_WINDOW` overlay (green/red border by verification) drawn on top of the native dialer |

## Build

The gradle wrapper jar is **not committed** (binary file). On a fresh checkout, install Gradle once (`brew install gradle` on macOS) and run from the repo root:

```bash
gradle wrapper --gradle-version 8.7
./gradlew :relavoi-sdk:assembleRelease
./gradlew :relavoi-sdk:test
./gradlew :sample-app:assembleDebug
```

## Install (from a consumer app)

Once published to Maven Central (see `relavoi-sdk/build.gradle.kts` — uses `maven-publish`):

```kotlin
// app/build.gradle.kts
dependencies {
  implementation("com.relavoi:sdk:0.1.0")
}
```

## Quick start

```kotlin
// Application.onCreate()
Relavoi.initialize(
  context = applicationContext,
  apiKey = BuildConfig.RELAVOI_API_KEY,
  apiSecret = BuildConfig.RELAVOI_API_SECRET,
  tenantId = BuildConfig.RELAVOI_TENANT_ID,
  config = RelavoiConfig(enableLogging = true),
)

// Then anywhere
lifecycleScope.launch {
  val session = Relavoi.sessions.create(
    agentPhone = "+2348011111111",
    customerPhone = "+2348022222222",
    gracePeriodMinutes = 15,
  )
  Log.d("MyApp", "proxy=${session.proxyNumber}")
}
```

## Required Android permissions

Declared in the SDK manifest and merged into the consuming app:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

The consuming app must:
1. **Runtime-request** `READ_PHONE_STATE` on API 23+ (required by `CallVerificationManager` to detect call state).
2. **Route the user** to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` if they want the floating-bubble UX (`SYSTEM_ALERT_WINDOW`).

## Architecture

```
src/main/java/com/relavoi/sdk/
  Relavoi.kt              public object entry point (initialize + lazy subsystems)
  RelavoiConfig.kt        baseUrl / webSocketUrl / enableLogging / queue cap
  RelavoiException.kt     sealed hierarchy (NotInitialized, Unauthorized, Network, …)
  auth/                   AuthManager (mutex-serialized refresh), TokenStore (EncryptedSharedPreferences)
  session/                interface + impl singleton, @Serializable models
  verification/           interface + impl, CallStateObserver (API 31+ + legacy fallback)
  push/                   PushTokenManager (per-user dedup), RelavoiFirebaseService (open subclass)
  events/                 OkHttp WebSocket + sealed RelavoiEvent + Unknown fallback
  presence/               DefaultLifecycleObserver wired to ProcessLifecycleOwner
  offline/                SQLite-backed queue, bounded by config cap
  overlay/                FloatingBubbleService (programmatic LinearLayout + drag handler)
  internal/               ApiClient (sync OkHttp, 401-retry), AuthInterceptor, Logger, PhoneUtils
sample-app/               minimal demo activity (5 buttons)
```

## Sample app

`sample-app/` is a one-activity Android app with buttons for Create session / Verify / Set presence / End session / Init call. It uses dev credentials baked into `BuildConfig` via `app/build.gradle.kts` — swap for real values via `buildConfigField` before shipping.

## Privacy

Phone numbers are **never logged in plaintext**. Every log site that touches a phone number routes through `PhoneUtils.maskPhone()` (returns `+****1234`). E.164 validation rejects non-conforming inputs at the SDK boundary.

## Related Repositories

- [relavoi-backend](https://github.com/cicanda/relavoi-backend) — API server (this SDK is a client of)
- [relavoi-ios-sdk](https://github.com/cicanda/relavoi-ios-sdk) — iOS SDK (matching feature set)
- [relavoi-docs](https://github.com/cicanda/relavoi-docs) — Documentation site
- [relavoi-dashboard](https://github.com/cicanda/relavoi-dashboard) — Tenant web dashboard
- [relavoi-admin](https://github.com/cicanda/relavoi-admin) — Operator console
- [relavoi-infra](https://github.com/cicanda/relavoi-infra) — Terraform infrastructure
