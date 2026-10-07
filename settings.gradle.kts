rootProject.name = "guardianlink"

// The REST+JSON sidecar: a thin HTTP adapter over the engine.
// It compiles against the root project's engine classes and adds no
// engine code of its own. Existing modules are untouched.
include("guardianlink-sidecar")
