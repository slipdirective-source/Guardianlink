plugins {
    // Versions come from the root project's apply-false declarations —
    // keep these versionless so KGP and AGP share one visible plugin
    // classpath (see root build.gradle.kts). Same as android-app.
    id("com.android.application")
    kotlin("android")
}

// Dependency repositories: google() carries the androidx / security-crypto
// artifacts; the root project's mavenCentral() does not apply here.
repositories {
    google()
    mavenCentral()
}

android {
    // Per ANDROID-PORT-MAP §e step 2: namespace distinct from the scaffold
    // (guardianlink.android), and a FRESH applicationId so v0.2 and the
    // scaffold can coexist on one phone during development.
    namespace = "guardianlink.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "guardianlink.app.v2"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
    }

    buildTypes {
        getByName("debug") {
            // No applicationIdSuffix: the v2 id is already distinct from
            // the scaffold's guardianlink.android(.debug).
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

// The pure-Kotlin core (guardianlink.v2.core) is JVM-testable; run its
// JUnit 5 suites as local unit tests. This closes the scaffold's CI test
// hole for the new module (ANDROID-PORT-MAP §e step 5).
tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    // The engine: pure Kotlin/JVM, zero android.* imports. Governed
    // engine execution goes through ConciergeInterface via
    // GovernedActionBridge — there is no second write path.
    implementation(project(":"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // JNA: required by the uniFFI-generated Kotlin bindings to the
    // Rust glzk crate (src/main/kotlin/uniffi/glzk/glzk.kt) — it is
    // the bridge's own generated dependency surface, pinned exactly.
    // The @aar artifact is the Android variant.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Same JUnit 5 line the engine's own tests use (root build.gradle.kts).
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}
