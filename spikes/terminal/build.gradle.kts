// Phase 0 spike: terminal emulator candidates, rendered headless to PNG snapshots.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // kmp-terminal's JVM artifacts are Java 21 bytecode (class major 65).
    jvmToolchain(21)
}

dependencies {
    testImplementation(project(":spikes:common"))
    testImplementation(libs.kmp.terminal.core)
    testImplementation(libs.kmp.terminal.ui)
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.sshd.core)
    testImplementation(libs.bouncycastle.prov)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val testenvDir = rootProject.layout.projectDirectory.dir("testenv").asFile.absolutePath

tasks.test {
    useJUnitPlatform()
    systemProperty("testenv.dir", testenvDir)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
}
