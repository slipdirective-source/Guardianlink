plugins {
    // 2.0.21: KGP 1.9.22's KotlinAndroidTarget decorates against AGP's
    // removed BaseVariant API (NoClassDefFoundError with AGP 8.5.2).
    // 2.0.x is the documented-compatible KGP for AGP 8.5.
    kotlin("jvm") version "2.0.21"
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