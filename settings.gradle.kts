pluginManagement {
    resolutionStrategy {
        eachPlugin {
            // 2026-10-15: the Gradle Plugin Portal does not resolve the
            // AGP marker — it 303-redirects the marker POM to Maven
            // Central, which hosts zero com.android.application artifacts
            // (verified: all AGP versions 404). Map the marker straight to
            // the real module on Google's Maven instead. Immune to portal
            // flakiness; the kotlin-android marker gets the same treatment
            // for consistency.
            if (requested.id.id == "com.android.application") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
            // NOTE: do NOT map org.jetbrains.kotlin.android here. The portal
            // serves JetBrains markers fine, and mapping it collides with
            // the kotlin-jvm plugin already on the classpath ("already on
            // the classpath with an unknown version"). Only AGP needs the
            // workaround.
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "guardianlink"

// The REST+JSON sidecar: a thin HTTP adapter over the engine.
// It compiles against the root project's engine classes and adds no
// engine code of its own. Existing modules are untouched.
include("guardianlink-sidecar")

// The Android execution layer: VpnService TUN + in-process engine.
// Pure-logic modules stay free of android.* imports so they remain
// compilable and testable on JVM (Termux); this module is the only
// Android-coupled code in the repo.
include("android-app")
