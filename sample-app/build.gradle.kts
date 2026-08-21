import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.relavoi.sdk.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.relavoi.sdk.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Credentials come from a gitignored `secrets.properties` in this module so
        // real keys never land in git (these are public repos). Copy
        // `secrets.properties.example` → `secrets.properties` and fill in the Bolt
        // Nigeria test key/secret. Tenant ID and base URL are not secrets, so they
        // default to the live values here.
        // BuildConfig fields are surfaced as `com.relavoi.sdk.sample.BuildConfig.*`.
        val secretsFile = project.file("secrets.properties")
        val secrets = Properties().apply {
            if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
        }
        fun secret(key: String, default: String): String = secrets.getProperty(key) ?: default

        buildConfigField("String", "RELAVOI_API_KEY", "\"${secret("RELAVOI_API_KEY", "YOUR_API_KEY")}\"")
        buildConfigField("String", "RELAVOI_API_SECRET", "\"${secret("RELAVOI_API_SECRET", "YOUR_API_SECRET")}\"")
        buildConfigField("String", "RELAVOI_TENANT_ID", "\"${secret("RELAVOI_TENANT_ID", "f656ac1b-3b5d-4af0-8ff1-c4cbc1076144")}\"")
        buildConfigField("String", "RELAVOI_BASE_URL", "\"${secret("RELAVOI_BASE_URL", "https://api.relavoi.com/v1")}\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":relavoi-sdk"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Firebase Cloud Messaging — needed only for the "Register Push Token" button.
    // Without a google-services.json (and the google-services plugin) FirebaseApp
    // will not auto-initialize, so that one button reports an error at runtime;
    // every other SDK feature works without Firebase. See README for setup.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
