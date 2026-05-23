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

        // Sample-app dev creds — replace with real values before shipping.
        // BuildConfig fields are surfaced as `com.relavoi.sdk.sample.BuildConfig.*`.
        buildConfigField("String", "RELAVOI_API_KEY", "\"sk_test_relavoi_dev_0123456789abcdef\"")
        buildConfigField("String", "RELAVOI_API_SECRET", "\"secret_test_relavoi_dev_fedcba9876543210\"")
        buildConfigField("String", "RELAVOI_TENANT_ID", "\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\"")
        buildConfigField("String", "RELAVOI_BASE_URL", "\"http://10.0.2.2:3000/v1\"") // emulator → host
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
}
