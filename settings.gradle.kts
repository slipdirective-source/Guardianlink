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
