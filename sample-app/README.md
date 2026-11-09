# Relavoi Android Sample App

Developer test harness for the Relavoi Android SDK. Not a production UI: a column
of buttons that each exercise one SDK feature against the backend at
`https://api.relavoi.com/v1`, using a workspace of your own.

## Credentials: use a workspace of your own

This harness does not ship with a workspace, and the keys to ours are not
shared. A sandbox key is free and takes about a minute, and it means the
sessions you create while trying the SDK are yours rather than mixed into
somebody else's test data.

1. Sign up at <https://app.relavoi.com/signup>.
2. Open **API & Keys** in the dashboard and press **Generate sandbox keys**.
   The key and the secret are shown once, when they are generated, so copy
   both before leaving the page. Regenerating invalidates the previous pair.
3. The workspace id is on that same page, inside the ready-made SDK snippets.

Sandbox keys talk to the same backend as live ones and need no approval. What
they will not do is place a real call: a sandbox session is routed and recorded
exactly as a live one but never reaches the carrier, which is what makes it
safe to run this harness against the numbers below.

Then put the three values in a gitignored properties file:

```
cp sample-app/secrets.properties.example sample-app/secrets.properties
# then edit sample-app/secrets.properties and set RELAVOI_API_KEY,
# RELAVOI_API_SECRET and RELAVOI_TENANT_ID
```

`secrets.properties` is gitignored, and `build.gradle.kts` reads it into
`BuildConfig` at build time. Only the base URL has a working default; the key,
the secret and the workspace id are all placeholders until you fill them in,
and the app will fail to initialise while they are. The SDK derives the
WebSocket URL from the base URL.

## Run it

1. Open `relavoi-android-sdk/` in Android Studio.
2. Select the **`sample-app`** run configuration.
3. Run on a device or emulator (min SDK 24).
4. The app initializes the SDK on launch and logs every operation to the on-screen
   log view. Tap the buttons top-to-bottom: Create Session → Get Session →
   Initiate Call → Verify → Connect Events → Update Presence → End Session, etc.

## Test numbers (pre-filled in the UI)

- Agent phone: `+2349000000001`
- Customer phone: `+2349000000002`

## Permissions

- `READ_PHONE_STATE` is requested at launch (needed for call-state detection /
  verification).
- `SYSTEM_ALERT_WINDOW` (overlay, for the floating verification bubble) is granted
  from system settings. Use the "Check Overlay / Phone Permission" button, which
  opens the settings screen if it isn't granted yet.

## Firebase / push notifications (optional)

The **"Register Push Token"** button calls `FirebaseMessaging.getInstance().token`
and then `Relavoi.push.registerToken(...)`. This is the **only** feature that needs
Firebase. Without configuration it reports an error in the log. That's expected,
and every other feature works without Firebase.

To make push work:

1. Create a Firebase project at <https://console.firebase.google.com>.
2. Add an Android app with package name `com.relavoi.sdk.sample`.
3. Download the generated **`google-services.json`** and drop it into
   `sample-app/` (the module root).
4. Apply the Google Services plugin:
   - In the root `build.gradle.kts` plugins/classpath, add
     `com.google.gms.google-services` (version `4.4.2`).
   - At the top of `sample-app/build.gradle.kts`, add
     `id("com.google.gms.google-services")` to the `plugins { }` block.
5. Rebuild. The token fetch will now succeed and register against the tenant.
