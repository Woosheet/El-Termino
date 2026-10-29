import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())

    android {
        namespace = "dev.eltermino.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")

    // jvmSharedMain wires the JVM implementations (MINA, files) for both apps.
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
            api(project(":core:ssh"))
            api(project(":core:data"))
            api(project(":core:vault"))
            api(project(":core:terminal"))
            implementation(project(":core:importer"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        named("desktopTest").configure {
            dependencies {
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(libs.junit.jupiter)
                implementation(compose.desktop.currentOs)
                runtimeOnly(libs.junit.platform.launcher)
                runtimeOnly(libs.slf4j.simple)
            }
        }
    }
}

val testenvDir = rootProject.layout.projectDirectory.dir("testenv").asFile.absolutePath

// End-to-end tests drive the real connection flow against testenv/ (see testenv/README.md).
tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
    systemProperty("testenv.dir", testenvDir)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    // How long SessionSoakTest keeps its ten sessions busy: -PsoakSeconds=14400 for four hours.
    systemProperty("soak.seconds", providers.gradleProperty("soakSeconds").getOrElse("20"))
    testLogging.showStandardStreams = providers.gradleProperty("soakSeconds").isPresent
}
