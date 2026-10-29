plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())

    android {
        namespace = "dev.eltermino.core.importer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")

    sourceSets {
        // Reading Termius's local data: only Termius for desktop keeps it where we can read it.
        named("desktopMain").configure {
            dependencies {
                implementation(libs.bouncycastle.prov)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        named("desktopTest").configure {
            dependencies {
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

tasks.named<Test>("desktopTest") {
    useJUnitPlatform()
}
