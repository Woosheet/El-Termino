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

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The release workflow passes the tag's version with -PappVersion; macOS refuses versions that start with 0.
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
require(Regex("[1-9][0-9]*\\.[0-9]+\\.[0-9]+").matches(appVersion)) { "appVersion must look like 1.2.3, got $appVersion" }

compose.desktop {
    application {
        mainClass = "dev.eltermino.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "El Termino"
            packageVersion = appVersion
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

// The DMG to hand out: the app next to a link to Applications, built by packaging/make-dmg.sh.
tasks.register<Exec>("packageShareableDmg") {
    group = "compose desktop"
    description = "Builds a DMG with the app and a link to Applications, ready to drag in."
    dependsOn("createDistributable")
    val app = layout.buildDirectory.dir("compose/binaries/main/app/El Termino.app")
    val dmg = layout.buildDirectory.file("dmg/El Termino-$appVersion.dmg")
    val script = file("packaging/make-dmg.sh")
    val icon = file("packaging/icon.icns")
    inputs.dir(app)
    inputs.files(script, icon)
    outputs.file(dmg)
    commandLine("sh", script.path, app.get().asFile.path, icon.path, dmg.get().asFile.path)
}
