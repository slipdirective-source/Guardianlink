plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "1.9.22"
}

android {
    namespace = "guardianlink.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "guardianlink.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            // Release signing is deployment scope: supply a keystore via
            // signingConfigs before any market-facing build.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The engine: pure Kotlin/JVM, zero android.* imports (grep-verified).
    // The app is a thin adapter — the ONLY execution path is
    // ConciergeInterface.execute, called by ActionDispatcher.
    implementation(project(":"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
