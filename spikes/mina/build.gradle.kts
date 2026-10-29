// Phase 0 spike: Apache MINA SSHD against the servers in testenv/.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())
}

dependencies {
    testImplementation(project(":spikes:common"))
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.bouncycastle.prov)
    testImplementation(libs.bouncycastle.pkix)
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
