plugins {
    // KGP 2.0.x pairs with AGP 8.5 (kept current for the android-app module).
    kotlin("jvm") version "2.0.21"
    // Declared apply-false so android-app applies both versionless: the
    // versioned declarations live here and the module inherits them from
    // the parent plugin scope. This keeps KGP and AGP on the same visible
    // plugin classpath — a versionless kotlin-android resolved from the
    // root buildscript classpath alone cannot see AGP types at decoration
    // time (NoClassDefFoundError: BaseVariant).
    kotlin("android") version "2.0.21" apply false
    // Resolves through the pluginManagement resolutionStrategy in
    // settings.gradle.kts (portal 404s the AGP marker; mapped to the real
    // module on Google's Maven).
    id("com.android.application") version "8.5.2" apply false
    application
}

group = "guardianlink"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
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

application {
    mainClass.set("guardianlink.MainKt")
}