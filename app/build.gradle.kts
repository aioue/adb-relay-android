plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Family Help bakes the tunnel URL and session token at build time — mum never pastes either.
// Bake with: ./gradlew assembleRelease -Pfamilyhelp.relayUrl=wss://… -Pfamilyhelp.relayToken=…
// The defaults are deliberately invalid so an unbaked build fails visibly instead of dialing nothing.
val bakedRelayUrl: String = (project.findProperty("familyhelp.relayUrl") as String?)
    ?: "wss://NOT-BAKED.invalid/"
val bakedRelayToken: String = (project.findProperty("familyhelp.relayToken") as String?)
    ?: "NOT-BAKED"

android {
    namespace = "org.androidgradletools.adbrelayandroid"
    compileSdk = 34

    defaultConfig {
        // Distinct from upstream so it can coexist with (or replace) any adb-relay install.
        applicationId = "net.aioue.familyhelp"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        versionName = "3.0-familyhelp"
        // A70 is arm64-v8a; armeabi-v7a kept for older spares.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        buildConfigField("String", "RELAY_URL", "\"$bakedRelayUrl\"")
        buildConfigField("String", "RELAY_TOKEN", "\"$bakedRelayToken\"")
    }

    buildTypes {
        release {
            // Minify off for the mum build: avoid stripping OkHttp/Mux classes mid-project (plan §Security).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        // SessionController/DialProto log via android.util.Log; JVM unit tests get no-ops.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests: the android.jar stub throws from constructors even with
    // returnDefaultValues, and DialProto/MuxCodec tests parse DIAL JSON on the JVM.
    testImplementation("org.json:json:20231013")
}