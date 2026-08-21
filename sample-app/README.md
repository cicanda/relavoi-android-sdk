# Relavoi Android Sample App

Developer test harness for the Relavoi Android SDK. Not a production UI — a column
of buttons that each exercise one SDK feature against the **live backend**
(`https://api.relavoi.com/v1`) using the **Bolt Nigeria** test tenant.

## Credentials (one-time)

Secrets are **not** committed. Copy the template and fill in the Bolt Nigeria test
key/secret (shared out-of-band):

```
cp sample-app/secrets.properties.example sample-app/secrets.properties
# then edit sample-app/secrets.properties and set RELAVOI_API_KEY / RELAVOI_API_SECRET
```

`secrets.properties` is gitignored. `build.gradle.kts` reads it into `BuildConfig`
at build time; the tenant ID (`f656ac1b-3b5d-4af0-8ff1-c4cbc1076144`) and base URL
(`https://api.relavoi.com/v1`) default to the live values, so only the key/secret
need filling in. The SDK derives the WebSocket URL from the base URL.

## Run it

1. Open `relavoi-android-sdk/` in Android Studio.
2. Select the **`sample-app`** run configuration.
3. Run on a device or emulator (min SDK 24).
4. The app initializes the SDK on launch and logs every operation to the on-screen
   log view. Tap the buttons top-to-bottom: Create Session → Get Session →
   Initiate Call → Verify → Connect Events → Update Presence → End Session, etc.

## Test numbers (pre-filled in the UI)

- Agent phone: `+2347067379297`
- Customer phone: `+2348162662319`

## Permissions

- `READ_PHONE_STATE` is requested at launch (needed for call-state detection /
  verification).
- `SYSTEM_ALERT_WINDOW` (overlay, for the floating verification bubble) is granted
  from system settings — use the "Check Overlay / Phone Permission" button, which
  opens the settings screen if it isn't granted yet.

## Firebase / push notifications (optional)

The **"Register Push Token"** button calls `FirebaseMessaging.getInstance().token`
and then `Relavoi.push.registerToken(...)`. This is the **only** feature that needs
Firebase. Without configuration it reports an error in the log — that's expected,
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
