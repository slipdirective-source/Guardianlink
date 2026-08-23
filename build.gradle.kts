plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "guardianlink"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
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