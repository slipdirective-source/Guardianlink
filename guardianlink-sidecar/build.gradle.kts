plugins {
    kotlin("jvm") version "1.9.22"
}

group = "guardianlink"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // The engine. The sidecar's ONLY engine entry point is
    // ConciergeInterface.execute — see GuardianLinkEngine.kt.
    implementation(project(":"))
    // Minimal JSON for the fixed API schema. Single tiny jar, no
    // transitive dependencies. The HTTP server itself is the JDK's
    // built-in com.sun.net.httpserver — no web framework.
    implementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
