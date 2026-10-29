import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())

    android {
        namespace = "dev.eltermino.core.ssh"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")

    // Android and desktop both run on a JVM, so the MINA implementation lives in jvmSharedMain.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        named("jvmSharedMain").configure {
            dependencies {
                implementation(libs.sshd.core)
                implementation(libs.sshd.sftp)
                implementation(libs.bouncycastle.prov)
                implementation(libs.bouncycastle.pkix)
                implementation(libs.sshd.putty)
            }
        }
        named("desktopTest").configure {
            dependencies {
                implementation(kotlin("test"))
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
                runtimeOnly(libs.slf4j.simple)
            }
        }
    }
}

val testenvDir = rootProject.layout.projectDirectory.dir("testenv").asFile.absolutePath

// Integration tests run against the servers in testenv/ (see testenv/README.md).
tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
    systemProperty("testenv.dir", testenvDir)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}
