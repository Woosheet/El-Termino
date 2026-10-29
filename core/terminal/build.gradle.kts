plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())

    android {
        namespace = "dev.eltermino.core.terminal"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":core:ssh"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kmp.terminal.core)
            implementation(libs.kmp.terminal.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        named("desktopTest").configure {
            dependencies {
                implementation(compose.desktop.currentOs)
                // A real PTY from the testenv servers for the live rendering test.
                implementation(libs.sshd.core)
                implementation(libs.bouncycastle.prov)
                runtimeOnly(libs.slf4j.simple)
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

val testenvDir = rootProject.layout.projectDirectory.dir("testenv").asFile.absolutePath

tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
    systemProperty("testenv.dir", testenvDir)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}
