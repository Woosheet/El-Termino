import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(libs.versions.jvm.target.get().toInt())
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    // Termius's local key lives in the system keychain, next to ours.
    implementation(libs.java.keyring)
}

compose.desktop {
    application {
        mainClass = "dev.eltermino.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "El Termino"
            // macOS refuses versions that start with 0.
            packageVersion = "1.0.0"
            description = "Client SSH e SFTP"
            vendor = "Woosheet"
            // What suggestRuntimeModules finds, plus what is only loaded at run time:
            // the EC and EdDSA providers for keys and key exchange, and the extra charsets.
            modules(
                "java.instrument", "java.management", "java.rmi", "java.security.jgss", "java.sql",
                "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets",
            )
            macOS {
                bundleID = "dev.eltermino.desktop"
                iconFile.set(project.file("packaging/icon.icns"))
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
            }
            linux {
                // Debian package names take no spaces or capitals.
                packageName = "el-termino"
                iconFile.set(project.file("packaging/icon.png"))
            }
        }
    }
}
